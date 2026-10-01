# Organization and user scanning design

- Date: 2026-10-01
- Status: accepted
- Contract: [`.spec/organization-scan.md`](../../.spec/organization-scan.md)

## Context

GitHub issue [#11](https://github.com/andrey-mogilev/atlas/issues/11) asked for
GitHub owner URLs to be detected, refused in the CLI without an opt-in option,
and expanded in the web interface behind a confirmation dialog with progress and
a force-rescan checkbox. The scanner previously only spoke to the Git client and
treated every URL as a repository, so a one-segment URL failed as an
inaccessible repository.

## Decisions

- **Owner detection is syntactic and offline.** A canonical `http(s)`
  `github.com` URL with exactly one valid-login path segment is an owner target.
  The CLI can therefore refuse it with exit 2 without a network request, and no
  list of reserved GitHub paths has to be maintained: whether the login exists
  and whether it is a user or an organization is answered by the GitHub API only
  when the scan is actually allowed.
- **Repository enumeration uses the GitHub REST API over the JDK HTTP client.**
  No dependency was added. JSON is parsed with snakeyaml's `SafeConstructor`,
  which the project already depends on; compact GitHub JSON was verified to
  parse correctly, and the safe constructor prevents Java type instantiation
  from a remote response. Rejected: adding a JSON library, and hand-writing a
  parser, because both add surface for no gain.
- **Repository identity comes from the API's `html_url`.** That is the
  `.git`-less spelling the README documents and users paste, so an owner scan
  matches repositories a user scanned by hand instead of creating a second
  identity. Differently spelled identities of one repository (`.git` suffix,
  letter case, SSH form) remain separate, as in the base specification.
- **Skipping already scanned repositories is the default in both adapters.**
  The issue defined that default for the web checkbox; the CLI follows it so one
  `ScanService` behavior and one specification cover both. `--rescan` exists
  because otherwise the CLI could never refresh an owner.
- **`--branch` cannot be combined with `--scan-organizations`** (exit 2, and
  category 2 for the web job). One branch name has no defined meaning across
  many repositories and would otherwise mass-produce branch-resolution
  failures. Each repository uses its own default branch.
- **A repository failure is an outcome, not an abort.** Owner scans report
  `scanned`, `skipped`, or `failed` per repository and keep going; the CLI exits
  1 when anything failed. Rejected: aborting on first failure, which would make
  an account containing one empty repository unscannable.
- **Progress reuses the existing job polling.** The owner job republishes a
  complete, internally consistent snapshot after every repository instead of
  introducing server-sent events or a second transport, so the single-job,
  ten-job-retention, and security contracts are unchanged.

## Consequences

- `ScanService` now owns a `GitHubApi`; `scanOwnedRepositories` is a top-level
  function taking an injected scan operation, mirroring `scanRemote`, so owner
  behavior is testable without network access.
- `WebServer` gained `ownerPlan` and `scanOwner` constructor hooks. They are
  declared before `scan` so the existing trailing-lambda test construction still
  binds to `scan`.
- An owner scan occupies the single scan worker for a long time; further
  submissions keep receiving HTTP 409.

## Review condition

Revisit if GitHub Enterprise Server, non-GitHub hosts, or concurrent
repository scanning are requested, or if the 500-repository default bound
proves wrong in practice.
