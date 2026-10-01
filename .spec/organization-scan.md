# Organization and user scanning — feature specification

## 1. Purpose

Accept a GitHub URL that names a user or an organization instead of a single
repository, and scan every repository that account owns. The CLI requires an
explicit opt-in; the web interface asks for confirmation, reports progress while
the scan runs, and can reuse previously saved results instead of rescanning.

This feature does not change repository identity rules, skill discovery, stable
location IDs, duplicate grouping, or persistence semantics defined in
[`readme.md`](readme.md). Each owned repository is scanned exactly as if its own
URL had been supplied. The repository-oriented web explorer is specified in
[`multi-repository-web.md`](multi-repository-web.md); organization scanning adds
new repositories to the same corpus.

## 2. Owner detection

A target is an **owner target** when its canonical URL is an `https` or `http`
URL for host `github.com` on the default port, without user information, whose
path is exactly one segment that is a valid GitHub login: alphanumerics with
inner hyphens, 1–39 characters, not starting or ending with a hyphen.

- Detection is purely syntactic, so it needs no network request and cannot be
  influenced by a remote response.
- Every other target — a two-segment repository URL, another host, an SSH or
  `git@host:path` spelling, a `file://` URL, or a local folder — keeps its
  current behavior.
- A copied Markdown link resolves to its URL before detection, exactly as for a
  repository target.
- Whether the login is a user, an organization, or does not exist is decided by
  the GitHub API, not by a list of reserved paths.

## 3. Repository enumeration

Owner targets are expanded through the GitHub REST API over HTTPS:

1. `GET /users/<login>` resolves the account's canonical login and `type`.
2. The listing endpoint is chosen so that every repository the credentials can
   reach is included:
   - `type` `Organization` uses `GET /orgs/<login>/repos`, which already returns
     the private and internal repositories a token can see.
   - A user account that is the token's own account, established by comparing
     `GET /user`'s login case-insensitively, uses
     `GET /user/repos?affiliation=owner`, which includes private repositories.
   - Every other user account uses `GET /users/<login>/repos`. That endpoint is
     public-only by definition, and another account's private repositories
     cannot be listed through any endpoint, so nothing is lost.

   Each listing is requested with `per_page=100` and increasing `page` until a
   short page is returned.
3. Each entry contributes its `full_name` and its `html_url`, normalized with
   the shared canonical-URL rules. An entry whose `full_name` does not begin
   with the resolved login is ignored, so an authenticated listing cannot add a
   repository the requested account does not own. Entries without both fields,
   and entries whose URL is itself an owner URL, are ignored. Repeated URLs are
   listed once.
4. Repositories are ordered by bytewise UTF-8 `full_name`, so a scan is
   deterministic and restartable.

Limits and credentials:

- `SKILL_SCAN_MAX_OWNER_REPOSITORIES` (default 500) bounds how many
  repositories one owner scan considers. Reaching the bound, or exhausting the
  internal page ceiling, marks the plan truncated and is reported to the user.
- A token from `SKILL_SCAN_GITHUB_TOKEN`, otherwise `GITHUB_TOKEN`, is sent as a
  bearer token. It makes an organization's private and internal repositories and
  the token holder's own private repositories visible to the enumeration, and it
  raises the API rate limit. Tokens are never stored, logged, or included in any
  message or API response, and are never passed to Git: cloning a private
  repository the token reveals still depends on the user's Git credentials, and
  a repository that cannot be cloned is reported as a per-repository failure.
- Every request is bounded in size (2 MiB) and by one deadline that covers the
  response body as well as its headers. A server that sends headers and then
  stalls its body is abandoned at the deadline and reported as category `3`;
  exceeding the size bound cancels the response and is category `1`. JSON is
  parsed with a YAML safe constructor, so no response can instantiate a Java
  type.
- Enumeration never fetches or executes repository content; cloning remains the
  existing scanner's responsibility.
- API failures map onto the existing exit codes: a missing account, a refused
  request (HTTP 401, 403, 429), and an unreachable API are category `3`; an
  unexpected status or unreadable body is category `1`.

## 4. Scanning owned repositories

An owner scan processes its planned repositories in order and records one
outcome per repository:

- `scanned` — the repository was scanned and persisted now, with its unique
  skill and location counts.
- `skipped` — the repository already had a saved scan and a rescan was not
  requested. Its previously saved unique skill and location counts are reported
  unchanged; no network request is made for it.
- `failed` — the repository could not be scanned. The outcome keeps the normal
  scan exit code and message.

A repository counts as already scanned when its canonical URL matches a stored
repository, which is the same identity used by the Skills page. A failed
repository never ends the owner scan: the remaining repositories are still
processed. An interruption, such as server shutdown, ends the scan with
category `1` instead of reporting fabricated outcomes.

Each repository uses its own default branch. A branch override cannot be
combined with an owner scan, because one branch name has no defined meaning
across many repositories.

## 5. Command-line interface

```text
skill-atlas scan <github-owner-url> --scan-organizations [--rescan] [--verbose | --json] [--color ...]
```

- Without `--scan-organizations`, an owner target is refused with exit `2` and a
  message naming the option. No network request and no database access occurs.
- `--scan-organizations` is also accepted as `-scan-organizations`, the spelling
  used in the original request.
- `--rescan` requires `--scan-organizations` and scans repositories that already
  have saved results instead of reporting them from storage.
- `--branch` combined with `--scan-organizations` is exit `2`.
- The option has no effect on a repository target.

Compact output reports the owner, its type, how many of the planned repositories
were scanned, the combined unique skill and location totals, the numbered list
of scanned repositories with their counts, a section for repositories reported
from saved results, a section for failures with their codes and messages, and a
truncation note when the plan was bounded. `--verbose` adds each repository URL.
An owner with no visible repositories prints an explicit message.

`--json` emits one object with `schemaVersion` 1 and `kind` `owner`, plus
`owner`, `ownerType`, `target`, `repositoryCount`, `truncated`, `scannedCount`,
`skippedCount`, `failedCount`, `skillCount`, `locationCount`, and
`repositories`. Each repository has `name`, `target`, `status`, `skillCount`, and
`locationCount`; failures add `code` and `message`. Single-repository JSON output
is unchanged and has no `kind` field.

The command exits `0` when every planned repository was scanned or reported from
storage, and `1` when at least one repository failed. Enumeration failures use
their own category and print no report.

## 6. Web interface

### Confirmation

`POST /api/targets` resolves a submitted target before any scan starts. It
returns `{"kind":"repository"}` for everything that is not an owner target,
including malformed targets, so repository errors keep their existing shape and
are still reported by the scan job. For an owner target it returns `kind`
`owner`, `login`, `type`, `target`, `repositoryCount`, `alreadyScannedCount`, and
`truncated`.

The page then shows a modal dialog that names the account, states how many
repositories it owns, warns that each one is cloned in turn and that the scan
can take a long time, says how many already have saved results, and notes
truncation when it applies. The dialog contains a **Rescan repositories that
were already scanned** checkbox, cleared by default, and Cancel and Start
actions. Cancelling, including with Escape, starts nothing. An owner with no
visible repositories is reported without a dialog and without a scan.

### Progress and incremental results

`POST /api/scans` accepts the existing `target` and `branch` fields plus an
optional `rescan` field whose only valid values are `true` and `false`; any
other value is HTTP 400. A scan of an owner target with a non-empty branch fails
with category `2`.

An owner job keeps the existing single-job-at-a-time contract, the existing
ten-job retention, and the existing polling route. While it runs,
`GET /api/jobs/<id>` returns `kind` `owner` together with `login`, `type`,
`target`, `total`, `truncated`, `rescan`, `completed`, `scanned`, `skipped`,
`failed`, `skillCount`, `locationCount`, and `repositories`. The repository list
grows by one consistent entry per finished repository; each entry has `name`,
`target`, `status`, `skillCount`, and `locationCount`, and failures add `code`
and `message`. A snapshot is never partially updated.

The Scans page shows a determinate progress bar over the planned repository
count, a polite live summary, and the growing per-repository list with its
status and counts or failure reason. Saved scan history is refreshed every time
the completed count changes, so repositories and their skills appear while the
scan is still running. When the Skills page is open in the same tab, it refreshes
its repository panel and combined results on the same signal. Partially
completed repositories never enter the corpus, because only persisted scans are
read.

Existing token, host, origin, request-size, cache, content-security, and
text-rendering rules apply unchanged to the new route and the new job fields.

## 7. Acceptance criteria

1. `scan https://github.com/<login>` without the option exits `2`, names
   `--scan-organizations`, and performs no network or database access.
2. `scan https://github.com/<login> --scan-organizations` scans every listed
   repository, reports per-repository counts, and persists each repository under
   its own identity.
3. A second identical scan reports every repository from saved results and
   scans nothing; adding `--rescan` scans them again.
4. A repository that cannot be scanned is reported with its code and message,
   the remaining repositories are still scanned, and the command exits `1`.
5. `--branch` with `--scan-organizations`, and `--rescan` without it, exit `2`.
6. A missing account, a refused API request, and an unreachable API are
   reported with category `3` and never expose a token.
7. The repository limit truncates the plan and the truncation is reported.
8. The web interface requires dialog confirmation before an owner scan, honors
   the rescan checkbox, and starts nothing when the dialog is cancelled.
9. An owner job publishes a growing, consistent progress snapshot, and the page
   shows newly scanned repositories and their skills before the scan finishes.
10. A branch supplied with an owner target fails the job with category `2`, and
    an invalid `rescan` value is rejected with HTTP 400.
11. A token scanning its own user account lists that account's private
    repositories; any other account keeps the public listing.
12. A response that sends headers and then stalls its body is abandoned at the
    deadline rather than blocking the caller.
13. The Skills page loads every repository an owner scan saved, including a
    selection larger than one results request accepts.
14. Automated tests cover owner detection, enumeration paging and limits, the
    listing endpoint chosen per account and credentials, the response deadline
    and size bound, API failure mapping, skip and rescan behavior, failure
    isolation, option validation, report and JSON formatting, the web preview,
    progress, and rejection paths, and a Skills selection above the
    results-endpoint limit.

## 8. Out of scope

- Non-GitHub hosts, GitHub Enterprise Server, and SSH owner spellings.
- Selecting a subset of an owner's repositories, or filtering forks, archived,
  or private repositories.
- Scanning several repositories concurrently, or queuing more than one scan.
- A branch override applied across an owner's repositories.
- Treating differently spelled identities of one repository — `.git` suffix,
  letter case, or SSH form — as the same stored repository.
- Deleting or renaming repositories that an owner no longer exposes.
