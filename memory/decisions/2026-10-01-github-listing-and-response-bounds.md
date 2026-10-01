# GitHub listing endpoint and response bounds for owner scans

- Date: 2026-10-01
- Status: accepted
- Authoritative contract:
  [`.spec/organization-scan.md`](../../.spec/organization-scan.md) sections 3
  and the limits list;
  [`.spec/multi-repository-web.md`](../../.spec/multi-repository-web.md)
  section 8

## Context

Three defects in the first implementation of owner scanning were found by
review and each was confirmed against a live system rather than argued from
the documentation alone.

1. `GET /users/<login>/repos` is public-only *by definition*, including when a
   token is sent. Measured on 2026-10-01 with a valid token for
   `andrey-mogilev`: the public endpoint returned 3 repositories and no private
   one, while `GET /user/repos?affiliation=owner` returned 4 including 1
   private. A documented promise that a token reveals private repositories was
   therefore false for user accounts.
2. `HttpRequest.Builder.timeout` bounds the response headers, not a body read
   through `BodyHandlers.ofInputStream`. A local `HttpServer` that sent headers,
   one byte, and then held the connection made `send` return after 20126 ms
   against a 500 ms request timeout.
3. `GET /api/repository-results` accepts 100 repository IDs, but an owner scan
   can persist up to `SKILL_SCAN_MAX_OWNER_REPOSITORIES` (500), all enabled by
   default, so the Skills page reported an invalid selection and rendered
   nothing above 100 repositories.
4. The first attempt at (1) made `GET /user` a precondition of every
   user-account scan. A GitHub App installation token — what a workflow's
   `GITHUB_TOKEN` is — gets HTTP 403 from that endpoint, so the attempted fix
   aborted scans that had worked before it, including public-only ones. An
   identity probe must never be able to fail a scan.

## Decision

- Enumerate from a *list* of listing endpoints chosen from the account type and
  the credentials, merged into one set keyed by canonical URL. Organizations
  keep `/orgs/<login>/repos`, which is already private-aware. A user account
  that is the token's own account uses `/user/repos?affiliation=owner`. Every
  other user account merges the public endpoint with
  `/user/repos?affiliation=collaborator`, because a private repository owned by
  someone else is reachable through collaboration.
  `affiliation=organization_member` is deliberately absent: those repositories
  are owned by an organization, so they cannot appear in a user's listing, and
  including it would page through every repository the token can reach. A token
  that cannot name a user is an installation token, so
  `/installation/repositories` is merged in for it. Filter listing entries by
  the resolved login, because the authenticated and installation endpoints both
  return repositories the requested account does not own.
- Distinguish an *absent* endpoint from a *failed* one, and treat only absence
  as skippable. Absence is HTTP 401, 403, or 404 whose body does not report a
  rate limit, and only on a listing's first page. `GET /user` is an identity
  probe that resolves to "no identity" on absence, and the installation listing
  is skipped on absence, so a token without an installation still scans its
  primary listing. Everything else — a rate limit sharing HTTP 403, a transient
  status, an unreadable body, any failure on a later page — propagates.
- Bound the whole GitHub exchange with one deadline and collect the body with a
  size-limited `BodySubscriber`, cancelling the response when either bound is
  reached. Never rely on a request timeout to bound a body.
- Keep the 100-ID server limit and batch on the client, concatenating responses
  in request order.

## Consequences

- Another account's private repositories are visible exactly when the token
  holder collaborates on them. Repositories with no affiliation at all remain
  invisible, which is a property of the GitHub API rather than of the chosen
  endpoints.
- A token is still never passed to Git. An authenticated owner scan therefore
  *plans* a private repository and then reports it as a per-repository `code 3`
  failure unless the user's Git credentials can clone it. Documented in the
  README and the specification instead of being hidden.
- `githubApiRequest` takes the deadline and the size bound as parameters so
  tests can use short ones; the production defaults are unchanged at 30 s and
  2 MiB.
- Raising the server's ID limit stays available if a future page needs it, but
  the client would still need batching for an unbounded corpus.

## Review triggers

Revisit if GitHub changes what `/users/<login>/repos` returns for an
authenticated request, if `GET /user` becomes available to installation
tokens, if a token is ever handed to Git, or if the combined-results route's
ID limit changes.

## Generalizations worth keeping

Credential *kinds* differ in which endpoints they can reach, not only in what
those endpoints return. Any new GitHub call added here must state whether it is
required or merely informative, because treating an informative call as
required converts a narrower credential into a total failure — and tolerating a
required call's failure converts an incomplete answer into a confident wrong
one. Both mistakes were made here in succession, in that order.

"Not permitted" and "not right now" share HTTP 403 on this API. Any code that
treats 403 as a permanent answer has to exclude the rate-limit case explicitly,
or it will silently drop data whenever the limit is reached.
