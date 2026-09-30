# Agent Instructions

## Specifications

Keep the project specification up to date for every change. When behavior,
interfaces, requirements, architecture, or operational expectations change,
update the relevant specification or documentation in the same change. If no
specification exists yet, add or expand the appropriate project documentation
to record the intended behavior.

## Project memory

- Read `memory/README.md` and `memory/project.md` before making architectural
  or behavioral changes. Consult `memory/current.md`, relevant decision
  records, and task handoffs when they apply.
- Update project memory when work reveals durable project knowledge, changes
  an architectural decision, or leaves important follow-up context.
- Record verified facts and decisions, not chat transcripts, transient command
  output, or unsupported speculation. Never store credentials, secrets, or
  personal data in project memory.
- Keep specifications and normal project documentation authoritative. Memory
  should link to those sources instead of duplicating their contracts.
- Prefer a new, uniquely named file in `memory/decisions/` or
  `memory/handoffs/` over concurrent edits to a shared file.
- Follow the maintenance and retirement rules in `memory/README.md`. Remove or
  rewrite stale material when repository evidence supersedes it, and preserve
  uncertain information with an explicit review note rather than guessing.

## GitHub workflow

- Prepare each change on a separate `codex/<change-name>` branch based on the
  latest `main`. Do not commit or push directly to `main` unless the user
  explicitly grants an exception for that change.
- Update the implementation and relevant specification/documentation, and run
  appropriate local validation before opening a pull request.
- For every change that affects visible web UI behavior, use the repository's
  `web-ui-demo` skill. After final UI validation and immediately before opening
  the pull request, record the changed behavior and add the current video to a
  `UI demonstration` section in the pull request description. Replace the
  recording whenever a later commit changes visible behavior. Store recordings
  locally in `/Users/andrey.mogilev/Projects/Videos/`.
- Commit and push the change branch to GitHub, then open a draft pull request
  targeting `main`. CI currently runs on pull requests and pushes to `main`,
  so a draft pull request is needed to validate a change branch.
- Use `.github/pull_request_template.md` for every pull request description.
  Keep all of its sections: a short summary, video demonstration, architecture
  changes, tests run, and known limitations. Fill each section with concrete
  details. When a section does not apply, state that explicitly and explain
  why instead of removing it.
- Review CI checks for the latest pull request revision. If a check fails
  because of the change, diagnose and fix it, push the fix, and repeat until
  the checks pass. Do not treat pending, skipped, or unavailable required
  checks as passing.
- Mark the pull request ready for review only after the work is complete and
  CI is green. Include a summary of the change and validation in the pull
  request, and provide its link when handing the work back to the user.
- Do not merge the pull request unless the user explicitly requests it.
- Do not treat a change as complete solely because it works locally.

## Definition of done

A change is done only when all of the following are true:

1. The implementation and its relevant specification/documentation are
   updated.
2. Appropriate local validation has been run.
3. For a visible web UI change, the pull request description contains a
   reviewed video demonstrating the current revision.
4. The change is committed and pushed to its separate GitHub branch.
5. A pull request targeting `main` exists, and CI checks for its latest
   revision have been reviewed and are passing.
6. The pull request is ready for review and its link has been provided to the
   user. Merging is not part of completion unless explicitly requested.

If the user explicitly waives the separate-branch/pull-request workflow for a
particular change, follow that exception only for that change. Documentation,
local validation, commit/push, and green CI requirements still apply.
