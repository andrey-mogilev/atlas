# Skill stars are browser-local UI state

- Date: 2026-10-01
- Status: Accepted
- Scope: web explorer (Skills page)

## Context

GitHub issue #10 asked for starring skills, with starred skills listed first
whether or not a filter is applied. Atlas already had two kinds of state: scan
data persisted in SQLite, and per-browser UI state (repository enablement) kept
in `localStorage` with no server mutation endpoint. The HTTP surface is
read-only apart from `POST /api/scans`.

## Decision

Stars are per-browser UI state, stored in `localStorage` under
`atlas-starred-skills` as a list of stable skill location IDs. No SQLite
column, migration, or mutation route was added, and the ordering is applied in
the browser when the skill list is rendered.

A card counts as starred when any location in its duplicate group is starred,
and starring records every location currently in the group. This matters
because the base specification allows a group's representative ID to change
when the set of locations changes, so keying only on the representative would
silently drop a star after a rescan that removes that copy.

Stored IDs for skills outside the active corpus are retained rather than
pruned, unlike stale repository identities, because a disabled repository's
skills must keep their stars when it is enabled again.

## Consequences

- Stars do not follow a user to another browser or machine, and they are lost
  when site data is cleared. This is stated in the specification and README as
  intended behavior, matching repository enablement.
- The read-only HTTP contract and the database schema are unchanged, so CLI
  behavior and persistence semantics were untouched.
- Ordering is verified by a browser behavior suite rather than Kotlin tests,
  because no server code participates.

## Alternatives rejected

- A `starred` column plus a mutation endpoint: would make stars machine-wide
  and survive clearing site data, but it adds a write route and a schema
  migration to the local read model for a personal display preference, and
  contradicts the existing treatment of enablement.
- Keying stars by repository and path instead of skill ID: paths already have
  stable IDs, and a path key would break when a skill moves.

## Review condition

Revisit if stars must be shared between browsers, machines, or users, or if the
HTTP read model gains other mutation routes.

## Evidence

- Contract: starred-skills rules in `.spec/multi-repository-web.md` section 5
  and acceptance criteria 12–14.
- Implementation: `src/main/resources/web/app.js`.
- Tests: `tests/behavior.spec.js`, `tests/visual.spec.js`.
