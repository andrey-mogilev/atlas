# Agent Instructions

## Specifications

Keep the project specification up to date for every change. When behavior,
interfaces, requirements, architecture, or operational expectations change,
update the relevant specification or documentation in the same change. If no
specification exists yet, add or expand the appropriate project documentation
to record the intended behavior.

## GitHub workflow

- Prepare each change on a separate `codex/<change-name>` branch based on the
  latest `main`. Do not commit or push directly to `main` unless the user
  explicitly grants an exception for that change.
- Update the implementation and relevant specification/documentation, and run
  appropriate local validation before opening a pull request.
- Commit and push the change branch to GitHub, then open a draft pull request
  targeting `main`. CI currently runs on pull requests and pushes to `main`,
  so a draft pull request is needed to validate a change branch.
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
3. The change is committed and pushed to its separate GitHub branch.
4. A pull request targeting `main` exists, and CI checks for its latest
   revision have been reviewed and are passing.
5. The pull request is ready for review and its link has been provided to the
   user. Merging is not part of completion unless explicitly requested.

If the user explicitly waives the separate-branch/pull-request workflow for a
particular change, follow that exception only for that change. Documentation,
local validation, commit/push, and green CI requirements still apply.
