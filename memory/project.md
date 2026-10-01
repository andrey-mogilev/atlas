# Stable project context

This file is intentionally concise. Add verified knowledge only when it is
durable, useful across tasks, and not already clear from authoritative project
documentation.

## Authoritative sources

- The command and behavior contract is maintained in `.spec/readme.md`.
- The repository-oriented web explorer and HTTP read model are maintained in
  `.spec/multi-repository-web.md`.
- User-facing setup and operating guidance is maintained in `README.md`.
- Build and verification use Maven; the standard full validation command is
  `mvn verify`.
- The web interface has two Playwright suites: `npm run test:web` for
  interactive behavior and `npm run test:visual` for screenshot comparison.
  Both run in CI after `mvn verify`.

## Visual baselines

The tracked Chromium baselines in `tests/visual.spec.js-snapshots/` depend on
the font that fontconfig resolves for `sans-serif`, because the page's font
stack lists no font that exists on Linux. They were verified in 2026-10 to
reproduce only in an environment where DejaVu Sans is the default sans-serif,
which is what the CI runner provides. Regenerating baselines on a machine whose
default differs changes every text pixel and produces baselines that fail CI.
Before updating baselines, confirm the unmodified suite passes first; that check
is the cheapest proof that the environment matches CI.

## Repository workflow

Repository changes use a dedicated `codex/*` branch and a pull request to
`main`. A change is complete only after its documentation and implementation
are updated, local validation succeeds, and CI is green, as detailed in
`AGENTS.md`.
