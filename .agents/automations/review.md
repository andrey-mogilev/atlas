When triggered on a PR for the first time:
- Carefully review the changed code, PR description, and linked issue, if one exists.
- Check the code for bugs.
- Check whether the added or updated tests cover the primary changed functionality and relevant edge cases.
- Check whether all applicable requirements from the issue are implemented.
- Report any findings worth fixing. If there are none, report a short review summary and recommend approving the PR.

If triggered by new changes or comments on a previously reviewed PR:
- Review the new diff and check it for regressions or new issues.
- Re-check previously reported findings. Resolve only findings that are verified as fixed.
- If no unresolved findings remain, leave a short review summary and recommend approving the PR.
