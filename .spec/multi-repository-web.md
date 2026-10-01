# Multi-repository web explorer — feature specification

## 1. Purpose

Extend the local web interface from a single-scan result viewer into a
repository-oriented skill explorer. Repositories are the top-level hierarchy;
each repository owns its saved scans, and the main page combines skills from
the latest scan of every enabled repository.

This feature changes the web interface and its HTTP read model. It does not
change the `skill-atlas scan` CLI contract, repository identity rules, scan
execution, skill discovery, stable location IDs, or persistence semantics in
the base specification.

Scanning a whole GitHub user or organization adds repositories to this corpus
one at a time. Its confirmation dialog, rescan choice, progress reporting, and
incremental refresh are specified in
[`organization-scan.md`](organization-scan.md); everything below applies
unchanged to the repositories it creates.

## 2. Terminology and selection rules

- A **repository** is an existing `repositories` record identified by its
  canonical URL. Local folders are repositories under their normalized
  `file://` identity.
- A repository's **latest scan** is its persisted scan with the greatest
  `scanned_at`; a greater scan ID breaks a timestamp tie. Branch and commit do
  not otherwise affect this choice.
- An **enabled repository** is one whose skills are included in the main
  explorer. Enablement is browser UI state and does not alter or delete stored
  repositories, scans, or skills.
- The **active corpus** is the grouped findings from exactly one latest scan
  per enabled repository. A repository with no completed persisted scan cannot
  appear in the repository selector.

When a repository has several scans, only its latest scan contributes to the
main page by default. Older scans remain available on the Scans page. Opening
an older scan there is an inspection action and does not replace the latest
scan used by the main page.

## 3. Information architecture

The web interface has two navigable pages served by the same local process:

1. **Skills** is the default page. It contains the repository side panel,
   combined skill results, filtering, and related-skill exploration.
2. **Scans** contains the scan submission form, running-job status, and saved
   scan history. The scan form and scan-history panel are removed from Skills.

Navigation between Skills and Scans is always visible, keyboard accessible,
and indicates the current page. Direct navigation and browser refresh preserve
the selected page. No external frontend framework or build step is introduced.

## 4. Repository side panel

Skills shows a side panel headed **Repositories** before the main results in
keyboard and screen-reader order. It lists every repository that has at least
one completed persisted scan, ordered by latest-scan time descending and then
canonical URL in bytewise UTF-8 order.

Each row contains:

- a labelled checkbox controlling whether that repository is enabled;
- a human-readable repository label, falling back to the canonical URL when a
  shorter unambiguous label cannot be derived;
- the latest scan's branch, scan time, unique-skill count, and location count;
- the canonical URL where needed to disambiguate equal labels.

The panel also contains an **All repositories** checkbox:

- selecting it enables every listed repository;
- clearing it disables every listed repository;
- it is checked when all repositories are enabled, unchecked when none are
  enabled, and indeterminate when only some are enabled;
- clearing an individual repository leaves all other selections unchanged.

On first use, all repositories are enabled. The browser persists the enabled
canonical repository identities locally. On later loads, identities that still
exist retain their state and newly discovered repositories start enabled.
Missing identities are discarded. If browser storage is unavailable, the page
remains functional with all repositories enabled for that page load.

An empty database shows an explicit state directing the user to the Scans
page. Disabling all repositories is valid and shows an explicit “No
repositories selected” state rather than falling back to all repositories.
The side panel remains usable at mobile widths, where it may collapse behind a
labelled control without changing selection semantics.

## 5. Combined skill explorer

The main panel shows the active corpus. Existing within-scan duplicate grouping
continues to apply independently to each repository's latest scan. Groups are
not merged across repositories, even when normalized contents are identical,
because repository provenance and stable location identities must remain
visible.

Each skill card shows its repository label in addition to its existing name,
description, representative path, locations, IDs, and source links. Ordering is
deterministic: repository order from section 4, followed by the existing
representative-path order within that repository's scan.

The summary reports enabled-repository, unique-skill, and location counts. The
skill and location totals are sums of the per-repository latest-scan results.
Changing repository selection updates the summary and cards without a page
reload or source rescan. A scan that completes while Skills is open may trigger
a repository/list refresh, but partially completed jobs never enter the active
corpus.

The verbose CLI-text view is scan-specific and is not synthesized for a
multi-repository corpus. It remains available when inspecting an individual
scan on the Scans page.

### Starred skills

Every skill card on Skills carries a star control that marks that skill as
starred. The control is a toggle: it stars an unstarred skill and unstars a
starred one, at any time, without a page reload or rescan. It reports its
current state and names the skill and repository it belongs to, so the same
skill in two repositories is starred independently.

Starred skills are listed before unstarred skills. Within each of those two
groups the deterministic ordering in this section still applies, so starring
reorders the list without otherwise disturbing repository or path order. The
reported repository, skill, and location counts are unaffected by starring.

A star is a per-skill mark on a stable location identity. A card counts as
starred when any location in its duplicate group is starred, so a star survives
a later scan that adds or removes a copy of the same contents. Starring a card
marks every location currently in the group; unstarring clears every current
location. Storing all current location identities ensures that removing the
representative copy does not discard the group's star while another copy
remains.

Stars are browser UI state, like repository enablement: they are persisted
locally, survive reloads, and are never written to SQLite or sent to the server.
Stars for skills outside the active corpus are retained rather than discarded,
because a disabled repository's skills must keep their stars when it is enabled
again. If browser storage is unavailable, starring still works for that page
load.

Starring is a Skills-page affordance. Inspecting an individual scan on the
Scans page presents that scan's own findings in its persisted order and offers
no star control, so scan inspection continues to match the verbose formatter
output.

## 6. Filtering and related skills

The existing name/description filter applies to every skill in the active
corpus. Counts and the no-match state reflect the filtered combined results.
Changing the enabled repository set reapplies the current filter; it does not
clear the query.

Filtering and starring compose: a filter still hides every skill that does not
match it, and the matching starred skills are listed before the matching
unstarred ones. Starring never reveals a skill that the filter excludes, and
filtering never changes which skills are starred.

Selecting a skill computes related skills against every other skill in the
active corpus, including skills from other enabled repositories. The existing
tokenization, weighting, cosine-similarity formula, rounding, and lexical-score
explanation remain unchanged. Each related result includes its repository label
and path. Similarity ties follow the combined deterministic ordering in section
5. Related skills are ranked by similarity only; stars do not reorder that
panel, and starring a skill does not change the similarity corpus.

Disabling the repository that contains the selected skill closes the related
skills panel. Disabling another repository recomputes the ranking over the
remaining active corpus. Filtering affects which cards are visible but does not
reduce the similarity corpus; choosing a related result clears the filter only
when necessary to reveal that result, matching existing behavior.

## 7. Scans page

The Scans page preserves the current scan form, one-job-at-a-time execution,
job recovery, errors, and latest-50 history limit. History is presented under
its repository hierarchy rather than as one flat list:

- repository groups use the ordering from section 4;
- scans within a repository are newest first by `scanned_at`, then scan ID;
- only the latest scan in each repository group is expanded or shown in detail
  initially when multiple scans exist;
- users can expand and inspect any older scan without changing repository
  enablement or the main page's latest-scan rule.

Inspecting a scan shows the same persisted findings and exact verbose formatter
output as the current history detail. Completing a scan refreshes its repository
group and makes that scan the latest when its ordering values qualify. Existing
idempotency remains: rescanning a recorded repository, branch, and commit reuses
the original scan record and timestamp, so it does not become latest merely
because it was requested again.

## 8. HTTP read contract

Existing scan-job routes and their security rules remain unchanged. The web UI
adds repository-oriented reads; exact route naming may follow implementation
conventions, but the response model must provide:

- every persisted repository with canonical identity and latest-scan summary;
- the latest persisted result for each requested repository;
- all saved scans grouped or groupable by repository for the Scans page;
- an individual persisted scan result for scan inspection.

The combined-results response must retain repository identity on every finding
or enclosing result so equal paths and names from different repositories cannot
be confused. It returns stored data only and never contacts or executes a
repository. Unknown repository/scan IDs return 404; malformed or excessive
repository selections return 400. The existing token, host/origin checks,
request limits, cache controls, content security policy, and safe text rendering
apply to both pages and all new routes.

The combined-results route accepts at most 100 repository IDs per request, while
an organization scan can save many more. The Skills page therefore splits its
selection into requests of at most 100 IDs and concatenates the responses in
request order, so a selection of any size renders rather than failing as an
invalid selection.

Repository enablement and starred skills are not written to SQLite and do not
require a server mutation endpoint. The server is the authority for latest-scan
selection and returns a consistent stored snapshot for each response.

## 9. Accessibility and responsive behavior

- Repository checkboxes use native checkbox semantics and expose the
  indeterminate state of **All repositories**.
- The star control is a native toggle button with an accessible name that
  states the action and the skill's name and repository, and with a pressed
  state that reflects whether the skill is starred. It is operable from the
  keyboard, and because toggling reorders the list, focus stays on the star of
  the skill that was just toggled.
- Selection changes and combined counts are announced through a polite live
  region without moving focus.
- Both pages retain labelled controls, visible focus, logical heading order,
  and full keyboard operation.
- At narrow widths, navigation, repository selection, result cards, scan
  history, filtering, and related skills remain usable without horizontal page
  scrolling.

## 10. Acceptance criteria

1. With three repositories and multiple scans per repository, Skills initially
   shows skills from exactly the latest persisted scan of all three.
2. Clearing one repository removes only its skills and locations; selecting it
   again restores its latest-scan findings without rescanning.
3. **All repositories** selects all, clears all, and accurately represents a
   partial selection. Selecting none produces an intentional empty state.
4. A persisted selection survives reload; a newly scanned repository starts
   enabled, and stale stored identities are ignored.
5. Equal skill contents in two repositories produce two repository-attributed
   cards rather than one cross-repository group.
6. Filtering matches names and descriptions across the entire active corpus,
   and its visible counts update after either query or repository changes.
7. Related-skill ranking includes candidates from every enabled repository,
   identifies their repositories, and is recomputed or closed when selection
   changes invalidate its corpus.
8. Skills contains no scan form or scan-history panel. Scans supports creating
   a scan and browsing history grouped by repository, with only the latest scan
   initially detailed in a multi-scan group.
9. Inspecting an older scan does not make it active on Skills. A newly persisted
   scan becomes active only when it is the repository's latest by the defined
   ordering.
10. CLI behavior, scan persistence, duplicate grouping within a scan, local
    access protections, and repository-content safety remain compatible with
    the base specification.
11. Automated tests cover latest-scan selection (including timestamp ties),
    all/one/none repository selection, cross-repository filter and similarity,
    scan grouping, empty states, reload behavior, starring, and
    mobile/accessibility semantics.
12. Starring a skill moves it ahead of every unstarred skill, including ahead
    of skills from a repository that is listed earlier, and leaves the relative
    order of the remaining skills unchanged. Unstarring restores the previous
    order.
13. With a filter applied, only matching skills are listed and the matching
    starred skills come first. Clearing the filter restores the full
    starred-first list.
14. Stars survive a reload without a rescan, are reported by the star control's
    pressed state, and are not present on scan inspection cards. Counts,
    repository selection, and related-skill ranking are unchanged by starring.

### Visual regression contract

The automated browser suite captures reviewed Chromium screenshots of the
empty Scans page, populated Scans page, populated Skills page, a Skills page
with one starred skill, and the narrow mobile Skills layout. It uses
deterministic local fixtures, locale, timezone, color scheme, reduced motion,
viewport sizes, pointer position, and normalized generated values.

A separate browser suite asserts interactive behavior rather than pixels. It
drives the real pages against the same local fixtures and covers the
starred-first order, the interaction between starring and filtering, star
persistence across a reload, keyboard operation and focus after a toggle, and
the absence of a star control on scan inspection cards. Both browser suites run
in continuous integration alongside `mvn verify`.

Normal verification compares rendered screenshots with tracked Linux Chromium
baselines and must not update them. A mismatch fails with expected, actual, and
diff artifacts. Baselines may be regenerated only through the documented
explicit update command after the resulting images have been reviewed. CI uses
the lockfile's Playwright version and uploads comparison artifacts on failure.

## 11. Out of scope

- Combining more than one scan or branch from the same repository on Skills.
- Manually choosing an older scan as a repository's active main-page version.
- Cross-repository duplicate collapsing or a new global skill identity.
- Deleting repositories or scans, renaming repositories, or editing skill
  contents.
- Persisting repository enablement or starred skills across browsers or
  machines, and sharing either with another user.
- Starring from the Scans page, starring a single location inside a duplicate
  group, or filtering the list down to starred skills only.
- Changing the CLI to scan several repositories in one invocation.
