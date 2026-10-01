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

## Decision

- Choose the repository listing endpoint from the account type and the
  credentials, not from the account type alone. Organizations keep
  `/orgs/<login>/repos`, which is already private-aware. A user account that is
  the token's own account uses `/user/repos?affiliation=owner`. Every other
  account keeps the public endpoint. Filter listing entries by the resolved
  login, because the authenticated endpoint can return repositories the
  requested account does not own.
- Bound the whole GitHub exchange with one deadline and collect the body with a
  size-limited `BodySubscriber`, cancelling the response when either bound is
  reached. Never rely on a request timeout to bound a body.
- Keep the 100-ID server limit and batch on the client, concatenating responses
  in request order.

## Consequences

- Another account's private repositories remain invisible. This is a property
  of the GitHub API, not a limitation of the chosen endpoints, so no further
  work can recover them.
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
authenticated request, if a token is ever handed to Git, or if the
combined-results route's ID limit changes.
