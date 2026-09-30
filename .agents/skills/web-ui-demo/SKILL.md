---
name: web-ui-demo
description: Record and attach a concise video demonstration whenever a change affects Atlas's web UI. Use after UI validation and immediately before opening or updating the change's pull request; do not use for changes with no visible web behavior.
---

# Web UI demo

Produce evidence of the user-visible behavior introduced or changed by the current branch. The recording is part of completing every web UI change, including follow-up commits that alter what a reviewer would see.

## Prepare the scenario

- Finish implementation, automated tests, and ordinary browser validation first.
- Identify the shortest realistic path that demonstrates the changed behavior and its result. Include a relevant error, empty, loading, or responsive state when that state is part of the change.
- Use deterministic, non-sensitive sample data. Never record credentials, tokens, personal data, unrelated tabs, notifications, or private repository content.
- Start Atlas using the documented local command and confirm the exact branch revision is running. Record the browser viewport only, unless another surface is essential to understanding the feature.

## Record

Use the available browser automation or screen-recording facility. Prefer WebM or MP4, a readable viewport, and a short recording that starts immediately before the interaction and ends after the result is visible. Keep normal pointer movement and pacing; omit setup, build output, and idle time.

Review the complete video before continuing. Re-record it if text is unreadable, the changed behavior is ambiguous, the run contains sensitive or unrelated material, or the recording no longer matches the latest UI revision. A screenshot does not replace the video.

Store the local capture outside the Git working tree or in an ignored temporary path. Do not commit the video unless the user or repository documentation explicitly requires versioned media.

## Add it to the pull request

Record after the final UI-affecting change and just before creating the draft pull request. In the PR description, add a `## UI demonstration` section that states what the recording shows and embeds or links the uploaded video. Prefer uploading through GitHub's PR editor so the media remains accessible to reviewers; verify the rendered description opens or plays it.

If a later commit changes visible behavior, create a replacement recording from the latest revision and update the same PR section. Remove obsolete links so the description presents one authoritative current demonstration.

Do not mark the pull request ready for review until the video is present, current, viewable, and free of sensitive information. If recording or upload is unavailable, leave the PR in draft and report the concrete blocker instead of claiming completion.
