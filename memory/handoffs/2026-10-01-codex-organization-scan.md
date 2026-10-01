# Handoff: organization scanning, browser evidence outstanding

- Date: 2026-10-01
- Branch: `codex/organization-scan`
- Related: [decision record](../decisions/2026-10-01-organization-scanning.md),
  [`.spec/organization-scan.md`](../../.spec/organization-scan.md)

## State

Implementation, specification, documentation, and JVM tests are complete.
`mvn verify` passes locally (41 unit tests, 4 integration tests). The feature
was also exercised end to end against the real GitHub API: the CLI refusal,
an owner scan, the skip-by-default second run, `--rescan`, `--json`, the
`POST /api/targets` preview, incremental owner job snapshots, the owner plus
branch rejection, and the invalid `rescan` rejection were all verified by hand.

## Blocker: no browser in the development environment

Playwright's Chromium download is blocked by network policy in the environment
this change was prepared in, and no system Chromium and no package-install
rights are available:

```text
Download failed: server returned code 403 body 'Blocked by network policy'.
URL: https://cdn.playwright.dev/builds/cft/.../chrome-linux64.zip
```

Consequences, both of which need a machine with a browser:

1. `npm run test:visual` could not be run, so the tracked Chromium baselines in
   `tests/visual.spec.js-snapshots/` were not re-reviewed locally. The new DOM
   is hidden by default (`#owner-progress` is `hidden`, `#owner-dialog` is a
   closed `<dialog>`), so the idle Scans and Skills pages are expected to be
   unchanged. If CI reports a mismatch, review the uploaded
   `visual-test-results` artifact and update baselines deliberately with
   `npm run test:visual:update`.
2. `npm run demo:ui` could not record the video that `AGENTS.md` and the
   `web-ui-demo` skill require for a visible UI change, and
   `tests/ui-demo.scenario.js` and `tests/ui-demo.spec.js` were deliberately
   left unchanged rather than committing a demo script that was never executed.

## Remaining work

- Record the demo on a machine with Chromium: extend
  `tests/ui-demo.scenario.js` and `tests/ui-demo.spec.js` with the owner
  confirmation dialog, the rescan checkbox, and the progress panel, run
  `npm run demo:ui`, review the video, and add it to the pull request's
  `Video demonstration` section before marking the pull request ready.
- Confirm the visual baselines on that machine.

## Not verified anywhere

Browser behavior of `dialog.showModal()`, the `<progress>` styling, and the
Skills-page live refresh were reasoned about and syntax-checked (`node --check`,
element-id cross-check against `index.html`) but never rendered.
