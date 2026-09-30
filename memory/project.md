# Stable project context

This file is intentionally concise. Add verified knowledge only when it is
durable, useful across tasks, and not already clear from authoritative project
documentation.

## Authoritative sources

- The command and behavior contract is maintained in `.spec/readme.md`.
- User-facing setup and operating guidance is maintained in `README.md`.
- Build and verification use Maven; the standard full validation command is
  `mvn verify`.

## Repository workflow

Repository changes use a dedicated `codex/*` branch and a pull request to
`main`. A change is complete only after its documentation and implementation
are updated, local validation succeeds, and CI is green, as detailed in
`AGENTS.md`.
