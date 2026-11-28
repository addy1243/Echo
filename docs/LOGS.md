# Orchestrator work log

Keep the three most recent completed work sessions only. Add a concise entry after a session is complete, including its date, what changed, executed evidence, and the next improvement. Do not copy proposed or failed checks as if they passed.

## 2026-11-28 — Opt-in cache and video buffering mitigation

- Added default-off Windows media-cache Settings, audio-only or audio/video Stage choices, +1/+2 preparation priority, validated live video capture, cancellation at opt-out, independent-group guards, and embedded-player creation for Stage transfers. Stage cancels speculative transfers while video is loading or network-backed to reduce competition; normal Windows currently supports the audio cache only.
- **Executed evidence:** Desktop tests and both generated-audio native VLC cache checks passed with dummy output. Android debug compilation and the focused YouTubeDownloader unit test passed; the full data unit test run was stopped after it did not finish. Independent review found cancellation and group-boundary issues, which were addressed and re-reviewed without further findings in those fixes.
- **Remaining limits:** Connected Move-to-video native acceptance, real video continuity and weak-network buffering were not tested, and the user's interruption has not been reproduced. The local integration candidate includes published 0.2.7.1 fixes; Android dream and Windows packaged Stage acceptance are also incomplete. Temporary directories may remain after abrupt exit. No release or merge.
- **Next improvement:** Complete final review and verify actual Stage video and playlist continuity before production promotion.

## 2026-11-28 — Reuse the current captured stream

- The current VLC audio relay can adopt its own complete validated capture and serve subsequent requests and seeks from a pinned local file, without restarting the player. Active upstream requests and network-backed video continue to hold the conservative transfer rate. Capture discard cleanup no longer holds the relay write lock while performing file or cache cleanup.
- **Executed evidence:** Full `:desktop:test` passed. Deterministic fake-origin tests cover local GET/HEAD/ranges, overlapping upstream requests, pin lifecycle, and blocked cleanup. The synthetic native VLC check passed with dummy output, an origin disabled after capture, a local range probe, and same-player playback; this does not prove VLC itself fetched the probed local range. Independent review found and verified the cleanup fix.
- **Remaining limits:** Real YouTube playback, audible handoff, exact download rates, and weak-network continuity remain unverified. A badge alone cannot prove a different cache preparation is the exact stream representation; only an eligible original stream capture is adopted. Streamed video or an outstanding upstream read retains the conservative rate.
- **Next improvement:** Restart Stage and verify the current-song capture and next-song rate with real recordings; inspect video and seek behavior without disrupting audio.

## 2026-11-28 — Stage adaptive preparation speed

- Shared audio/video preparation now uses a 128 KiB/s ceiling while active playback is network-backed or its source is unknown, and up to 2 MiB/s when paused or all active VLC inputs are local. A video using a streamed audio slave remains network-backed. Buffering still pauses background reads, and in-flight pacing changes without restarting the request.
- **Executed evidence:** Full `:desktop:test` and the generated-audio `:desktop:stageStreamCaptureNativeCheck` passed. Focused deterministic tests cover both cache types, transitions in each direction, local video with audio slaves, buffering, and pacing without accumulated credit. Independent review found no confirmed adaptive-pacing defect.
- **Remaining limits:** No live bandwidth or playback-continuity acceptance was performed; rates are source-based ceilings, not bandwidth measurements. The faster paused rate can compete with separate connected Move/group preparation. A blocked socket read can finish after buffering begins. The currently running Stage process predates this change.
- **Next improvement:** Restart the isolated Stage preview and verify rates and uninterrupted audio/video on real strong and weak connections, including cached audio paired with streamed video.
