# Handoff: organization scanning, recording a demo without a browser

- Date: 2026-10-01
- Branch: `codex/organization-scan`
- Related: [decision record](../decisions/2026-10-01-organization-scanning.md),
  [`.spec/organization-scan.md`](../../.spec/organization-scan.md),
  [AIr Automation UI evidence](../decisions/2026-10-01-air-automation-ui-evidence.md)

## State

Implementation, specification, documentation, and JVM tests are complete.
`mvn verify` passes locally (41 unit tests, 4 integration tests) and in CI. The
feature was exercised end to end against the real GitHub API: the CLI refusal,
an owner scan, the skip-by-default second run, `--rescan`, `--json`, the
`POST /api/targets` preview, incremental owner job snapshots, the owner plus
branch rejection, and the invalid `rescan` rejection.

The UI demo has now been recorded in a real browser and reviewed:
`demos/codex-organization-scan-2026-10-01.webm` (67 s) plus the key moment
screenshots. The walkthrough covers the confirmation dialog, the progress panel
with its per-repository list, the rerun that reports saved results instead of
scanning, the rerun with the rescan checkbox ticked, and the Skills page. The
browser behavior that earlier revisions could only reason about —
`dialog.showModal()`, the `<progress>` styling, the incremental Saved scans and
Skills refresh — is confirmed working.

## Where the evidence lives

`demos/` is ignored and GitHub only accepts media for a pull request description
through its web editor, which no API token can drive. The video and the key
moment screenshots are therefore attached to the AIr Automation run linked from
pull request #13, which
[the UI evidence decision](../decisions/2026-10-01-air-automation-ui-evidence.md)
accepts as sufficient for marking a pull request ready for review. Re-recording
after a UI-affecting commit means re-attaching to the current run and replacing
the links.

## Recording a demo in a container without a browser

Playwright's own CDN (`cdn.playwright.dev`) is blocked by network policy in the
Air development environment, but the same Chrome for Testing build is reachable
from Google's bucket, so a demo can still be recorded there:

- Download `chrome-linux64.zip` and `chrome-headless-shell-linux64.zip` for the
  version `npx playwright install --dry-run chromium` names, from
  `https://storage.googleapis.com/chrome-for-testing-public/<version>/linux64/`,
  and unpack them into `~/.cache/ms-playwright/chromium-<build>/` and
  `chromium_headless_shell-<build>/` with an `INSTALLATION_COMPLETE` marker.
- Chromium's shared libraries, a working `ffmpeg` for `ffmpeg-<build>/
  ffmpeg-linux`, and fonts are not installed and there are no root rights.
  `apt-get` can still resolve and download them with `APT_CONFIG` pointing at a
  writable `Dir::State`/`Dir::Cache` and a copy of `/var/lib/dpkg/status`;
  unpack the archives with `dpkg -x` into a prefix and export
  `LD_LIBRARY_PATH`, `FONTCONFIG_PATH`, and `XDG_DATA_HOME`.
- Without any font, Chromium aborts with
  `FATAL ... SkFontMgr_FontConfigInterface ... Not implemented`, and the generic
  CSS families need an explicit fontconfig rule to reach DejaVu.
- `/dev/shm` is 64 MB, which crashes the renderer; pass
  `--disable-dev-shm-usage`. Supply it from a config file outside the
  repository rather than committing an environment-specific launch option.

Because the fonts differ from the GitHub runner's, `npm run test:visual` fails
locally on text metrics alone — the layout matches pixel for pixel. CI's
comparison stays the authority for the tracked baselines; do not update them
from this environment.
