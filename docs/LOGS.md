# Orchestrator work log

Keep the three most recent completed work sessions only. Add a concise entry after a session is complete, including its date, what changed, executed evidence, and the next improvement. Do not copy proposed or failed checks as if they passed.

## 2026-11-27 — Standby and Stage video PR candidate

- Ported Android Standby and Windows Stage screensaver/focused-video work selectively onto the current main baseline. Added a one-click close button to focused video. Preserved existing release version defaults and excluded the unregistered Windows `.scr` candidate. Hardened test fixtures so queue-changing checks require an explicit dedicated-emulator opt-in and ordinary Standby UI tests guard emulator use and restore onboarding preferences.
- **Executed evidence:** Android debug and Stage app assembly, Stage and debug instrumentation APK assembly, focused Android Standby policy and shared provider tests, and desktop playback and screensaver tests passed. On a dedicated Android 15 emulator, the Stage UI suite reported 11 tests with one assumption skip; Stage and normal entry were rerun after test-safety changes. A muted local service fixture passed video/queue transition and pause/exit checks; an opt-in live YouTube service test passed audio progress, a Cinema frame, pause and exit. Simulated charging and not-charging policy checks passed and the emulator battery override was reset. Paused/no-track Standby remained open across portrait-to-landscape-to-portrait rotation. In a newly packaged, isolated Windows Stage image, the focused video X closed the player with one click while the muted test song continued in the compact player; the video was still switching, so a rendered Windows video frame was not established.
- **Remaining limits:** Android live video during rotation, audible output, physical devices, and complete real-time idle/charging entry remain unverified. Windows real video frames, next-track continuity, saver failure recovery, background activation while Twitch plays, display scaling, focus/window restoration and multi-monitor behavior need native acceptance. The `.scr` candidate remains outside this branch, and no release or production deployment occurred.
- **Next improvement:** Complete those Android and Windows acceptance checks and reconcile the separate `.scr` path before merging or release.

## 2026-11-12 — Published 0.2.7 and recorded acceptance limits

- Published Android 0.2.7/code18 and Windows 0.2.7 from source commit `1222d03d284b9d1a234b369ca504617ec9f7ca84`; recorded the release assets and checksums in [the release record](releases/0.2.7.md).
- **Executed evidence:** Public GitHub asset metadata matched the local sizes and hashes; release APK alignment/signature checks and an in-place emulator install and launch passed. The Windows public updater helper from 0.2.6 discovered, downloaded and verified the MSI and its identity. Build, test and Stage evidence is listed in the release record.
- **Documentation and review:** Updated local release records and current-status docs. PR #1 targets main with the published source commit; GitHub reports it mergeable but exposes no check runs or commit statuses for that head. These documentation edits are local and are not part of the PR.
- **Next improvement:** Complete the installed Windows in-app update, interactive installation, restart and library/DPAPI preservation checks; verify live provider and native keyboard behavior. The user stopped Computer Use before installed-app acceptance.

## 2026-11-12 — Verified candidate guidance and settings

- Prepared 0.2.7/code18 candidate version fields, corrected Android light-theme contrast and desktop Space focus recovery, and added an actual isolated-Stage screenshot to the Windows Spotify tutorial. Made Windows Settings scrollable so lower controls remain reachable.
- **Executed checks:** Shared and Android unit tests and Android release lint passed; the full desktop suite and Android debug compilation passed. The focused linked-playlist repository test passed on an Android emulator. In Windows Stage, the tutorial screenshot appeared and Settings scrolled to the Spotify controls. This was a preview, not an installed-app or updater test.
- **Next improvement:** Complete independent review, artifact builds and signing, installed Windows update/data-preservation acceptance, and publication verification.
