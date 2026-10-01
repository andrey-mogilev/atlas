---
name: web-ui-demo
description: Record or play concise video demonstrations of Atlas's web UI. Use manually when the user asks to create or view a demo, and automatically after UI validation immediately before opening or updating a UI change's pull request; do not use for changes with no visible web behavior unless explicitly requested.
---

# Web UI demo

Record or show user-visible Atlas behavior. The skill supports two modes:

- **Manual demo:** When explicitly invoked, record the requested scenario or locate and play an existing demo. A pull request is not required.
- **UI-change evidence:** When completing a branch that changes visible UI behavior, record the final behavior immediately before opening or updating the pull request. Attach the current demo and screenshots either to the pull request or to an AIr Automation run, then link the evidence from the pull request description. Repeat this after follow-up commits that alter what a reviewer would see.

## Prepare the scenario

- Finish implementation, automated tests, and ordinary browser validation first.
- Identify the shortest realistic path that demonstrates the changed behavior and its result. Include a relevant error, empty, loading, or responsive state when that state is part of the change.
- Use deterministic, non-sensitive sample data. Never record credentials, tokens, personal data, unrelated tabs, notifications, or private repository content.
- Start Atlas using the documented local command and confirm the exact branch revision is running. Record the browser viewport only, unless another surface is essential to understanding the feature.

## Record

Describe the walkthrough in `tests/ui-demo.scenario.js`, including its input, filter, and key screenshot moments. Implement or update its interactions in `tests/ui-demo.spec.js`, then run `npm run demo:ui`. Do not use a separate screen-capture workflow. The Playwright test owns the viewport, video and screenshot capture, recording-only focus styles, visible cursor, click ripples, pacing, and assertions.

Run `npm install` when dependencies are absent. Run `npm run demo:install` when Playwright reports that Chromium or FFmpeg is missing.

## Pacing and visual emphasis

- Type visibly rather than filling a field instantly. Use roughly 80–120 ms between characters, with a short pause before typing and after the completed value can be read.
- Move the pointer along an eased path instead of jumping directly to controls. Hover briefly before clicking, scroll smoothly, and wait for each transition or loading state to settle before the next action.
- Hold important starting and result states for about 1–2 seconds. Prefer a slightly longer, understandable recording over a fast sequence that must be replayed.
- Before an important interaction, add a recording-only focus treatment around the relevant control or result: a high-contrast 3–4 px border or outline, rounded corners, and a subtle translucent backdrop or glow. Keep it visible long enough to direct attention, then remove it or move it to the next target.
- For dense or small content, use browser zoom between 110% and 125% or a stable magnified callout. Prefer a focus border when zoom would reflow the page, hide context, or cause distracting layout movement.
- Do not edit product source solely to add recording highlights. Inject temporary presentation styles through the browser automation layer, and ensure they do not cover labels, values, validation messages, or pointer targets.
- Use one emphasis effect at a time. Avoid flashing, repeated pulsing, abrupt zoom, or decorative motion that competes with the behavior being demonstrated.

Review the complete video before continuing. Re-record it if text is unreadable, the changed behavior is ambiguous, the run contains sensitive or unrelated material, or the recording no longer matches the latest UI revision. A screenshot does not replace the video.

The test stores all captures together in the repository's ignored `demos/` directory. It uses the shared prefix `<mangled-branch>-YYYY-MM-DD`: lowercase the current branch, replace each run of non-alphanumeric characters with `-`, and trim leading or trailing separators. The video is `<prefix>.webm`; key moments declared by the scenario are `<prefix>-<moment>.png`. A rerun on the same branch and UTC date replaces that branch-day set; captures from other branches or dates remain intact. Report the absolute paths. Do not commit demo artifacts.

## View a demo

When the user asks to see a demo, list or identify the relevant files in `demos/`. If the request is ambiguous, prefer the newest matching recording. Play or preview the selected local video and its key screenshots in Codex, and provide their absolute paths. Do not require, create, or update a pull request for manual viewing.

## Add it to the pull request

This section applies only when the demo is evidence for a UI-changing branch or the user explicitly asks to add a demo to a pull request. A manual invocation does not imply permission to create or modify a pull request.

Record after the final UI-affecting change and just before opening or updating the pull request. Attach the branch-day video and key screenshots from `demos/` either directly to the pull request or to an AIr Automation run. In the PR description, complete the `## Video demonstration` section by stating what the evidence shows and adding links to the attached artifacts or the run that contains them. Verify that reviewers can open or play the linked evidence.

If a later commit changes visible behavior, create a replacement recording from the latest revision and update the same PR section. Remove obsolete links so the description presents one authoritative current demonstration.

Do not mark the pull request ready for review until the linked evidence is present, current, viewable, and free of sensitive information. Evidence hosted by an AIr Automation run satisfies this requirement; once the other completion and CI requirements are met, mark the pull request ready for review rather than leaving it in draft. If neither attachment location is available, report the concrete blocker instead of claiming completion.
