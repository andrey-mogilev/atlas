---
name: web-ui-demo
description: Record or play concise video demonstrations of Atlas's web UI. Use manually when the user asks to create or view a demo, and automatically after UI validation immediately before opening or updating a UI change's pull request; do not use for changes with no visible web behavior unless explicitly requested.
---

# Web UI demo

Record or show user-visible Atlas behavior. The skill supports two modes:

- **Manual demo:** When explicitly invoked, record the requested scenario or locate and play an existing demo. A pull request is not required.
- **UI-change evidence:** When completing a branch that changes visible UI behavior, record the final behavior immediately before the pull request and attach the current demo to its description. Repeat this after follow-up commits that alter what a reviewer would see.

## Prepare the scenario

- Finish implementation, automated tests, and ordinary browser validation first.
- Identify the shortest realistic path that demonstrates the changed behavior and its result. Include a relevant error, empty, loading, or responsive state when that state is part of the change.
- Use deterministic, non-sensitive sample data. Never record credentials, tokens, personal data, unrelated tabs, notifications, or private repository content.
- Start Atlas using the documented local command and confirm the exact branch revision is running. Record the browser viewport only, unless another surface is essential to understanding the feature.

## Record

Use the available browser automation or screen-recording facility. Prefer WebM or MP4, a readable viewport, and a short recording that starts immediately before the interaction and ends after the result is visible. Omit setup, build output, and idle time.

## Pacing and visual emphasis

- Type visibly rather than filling a field instantly. Use roughly 80–120 ms between characters, with a short pause before typing and after the completed value can be read.
- Move the pointer along an eased path instead of jumping directly to controls. Hover briefly before clicking, scroll smoothly, and wait for each transition or loading state to settle before the next action.
- Hold important starting and result states for about 1–2 seconds. Prefer a slightly longer, understandable recording over a fast sequence that must be replayed.
- Before an important interaction, add a recording-only focus treatment around the relevant control or result: a high-contrast 3–4 px border or outline, rounded corners, and a subtle translucent backdrop or glow. Keep it visible long enough to direct attention, then remove it or move it to the next target.
- For dense or small content, use browser zoom between 110% and 125% or a stable magnified callout. Prefer a focus border when zoom would reflow the page, hide context, or cause distracting layout movement.
- Do not edit product source solely to add recording highlights. Inject temporary presentation styles through the browser automation layer, and ensure they do not cover labels, values, validation messages, or pointer targets.
- Use one emphasis effect at a time. Avoid flashing, repeated pulsing, abrupt zoom, or decorative motion that competes with the behavior being demonstrated.

Review the complete video before continuing. Re-record it if text is unreadable, the changed behavior is ambiguous, the run contains sensitive or unrelated material, or the recording no longer matches the latest UI revision. A screenshot does not replace the video.

Store every completed capture in `/Users/andrey.mogilev/Projects/Videos/`. Create the directory if it does not exist. Use a descriptive, filesystem-safe filename containing the Atlas feature and recording date, preserve earlier recordings unless the user asks to replace or delete them, and report the absolute path. Do not commit the video unless the user explicitly requires versioned media.

## View a demo

When the user asks to see a demo, list or identify the relevant files in `/Users/andrey.mogilev/Projects/Videos/`. If the request is ambiguous, prefer the newest matching recording. Play or preview the selected local video in Codex and provide its absolute path. Do not require, create, or update a pull request for manual viewing.

## Add it to the pull request

This section applies only when the demo is evidence for a UI-changing branch or the user explicitly asks to add a demo to a pull request. A manual invocation does not imply permission to create or modify a pull request.

Record after the final UI-affecting change and just before creating the draft pull request. In the PR description, add a `## UI demonstration` section that states what the recording shows and embeds or links the uploaded video from `/Users/andrey.mogilev/Projects/Videos/`. Prefer uploading through GitHub's PR editor so the media remains accessible to reviewers; verify the rendered description opens or plays it.

If a later commit changes visible behavior, create a replacement recording from the latest revision and update the same PR section. Remove obsolete links so the description presents one authoritative current demonstration.

Do not mark the pull request ready for review until the video is present, current, viewable, and free of sensitive information. If recording or upload is unavailable, leave the PR in draft and report the concrete blocker instead of claiming completion.
