# Orchestrator work log

Keep the three most recent completed work sessions only. Add a concise entry after a session is complete, including its date, what changed, executed evidence, and the next improvement. Do not copy proposed or failed checks as if they passed.

## 2026-11-28 — Cache retry after playback interruption

- Fixed a same-window retry bookkeeping edge: a previously failed cache item can retry after foreground video suspends preparation, including when preparation resumes before cancellation cleanup finishes. This does not identify the cause of the owner's observed one-time failure, which had already begun retrying on its own.
- **Executed evidence:** Focused StagePreparationTest passed, including a deterministic suspension/resume-during-cleanup regression. The normal Android debug app assembled for an emulator preview. Independent review checked priority, cancellation and avoidance of tight retry loops.
- **Remaining limits:** The original transfer exception was not retained; real recurrence and longer network/video acceptance remain unverified. No release or merge to main.
- **Next improvement:** Verify the candidate on Windows and the emulator, and investigate repeated cache failures without exposing resolved URLs or personal library data.

## 2026-11-28 — Prevent empty Standby

- Promoted focused Windows video and audio/video caching to production mode, retaining audio-only playback and connected-device guards. Guarded Android foreground Standby and Windows Stage screensaver entry against empty, paused or reconnecting playback; disabled manual controls with an explanation. Android system Dream exits when its local session has no actively playing song or cannot connect; its preview also exits without playback.
- **Executed evidence:** Desktop suite, Android debug compilation, and focused Standby/player unit tests passed. Stage app and instrumentation APK assembled. The desktop suite and synthetic VLC cache checks passed; a muted VLC check using the production video-capable player passed generated-audio queue and offline-audio flows and live YouTube audio/seek. A real-source native video check decoded a frame, retained the occurrence across audio/video/audio switches and observed no non-playing state in 20 one-second samples. Five targeted Stage UI tests passed on a dedicated read-only emulator, covering no-playback manual/automatic blocking and active-fixture entry, touch and accessibility exit. The emulator was shut down. The combined candidate includes published 0.2.7.1 fixes; desktop tests, VLC cache checks, Android targeted tests and Stage APK assembly passed on the combined tree. The same five Stage UI tests passed on the combined candidate in a dedicated read-only emulator; an independent merge review found no actionable regression.
- **Remaining limits:** Natural system Dream and no-session preview, native Windows screensaver behavior, longer and weak-network video continuity, sub-second buffering and complete production acceptance are unverified. Production-video integration is a candidate pending native acceptance; no release or merge to main.
- **Next improvement:** Verify Windows app UI, connected Move and weak-network video, and natural Android system Dream behavior on the combined commit.

## 2026-11-28 — Opt-in cache and video buffering mitigation

- Added default-off Windows media-cache Settings, audio-only or audio/video Stage choices, +1/+2 preparation priority, validated live video capture, cancellation at opt-out, independent-group guards, and embedded-player creation for Stage transfers. Stage cancels speculative transfers while video is loading or network-backed to reduce competition; normal Windows currently supports the audio cache only.
- **Executed evidence:** Desktop tests and both generated-audio native VLC cache checks passed with dummy output. Android debug compilation and the focused YouTubeDownloader unit test passed; the full data unit test run was stopped after it did not finish. Independent review found cancellation and group-boundary issues, which were addressed and re-reviewed without further findings in those fixes.
- **Remaining limits:** Connected Move-to-video native acceptance, real video continuity and weak-network buffering were not tested, and the user's interruption has not been reproduced. PR #2 is based on main without published 0.2.7.1 fixes; Android dream and Windows packaged Stage acceptance are also incomplete. Temporary directories may remain after abrupt exit. No release or merge.
- **Next improvement:** Complete final review, reconcile 0.2.7.1 into the integration branch, and verify actual Stage video and playlist continuity before production promotion.
