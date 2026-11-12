# Orchestrator work log

Keep the three most recent completed work sessions only. Add a concise entry after a session is complete, including its date, what changed, executed evidence, and the next improvement. Do not copy proposed or failed checks as if they passed.

## 2026-11-12 — Published 0.2.7 and recorded acceptance limits

- Published Android 0.2.7/code18 and Windows 0.2.7 from source commit `1222d03d284b9d1a234b369ca504617ec9f7ca84`; recorded the release assets and checksums in [the release record](releases/0.2.7.md).
- **Executed evidence:** Public GitHub asset metadata matched the local sizes and hashes; release APK alignment/signature checks and an in-place emulator install and launch passed. The Windows public updater helper from 0.2.6 discovered, downloaded and verified the MSI and its identity. Build, test and Stage evidence is listed in the release record.
- **Documentation and review:** Updated local release records and current-status docs. PR #1 targets main with the published source commit; GitHub reports it mergeable but exposes no check runs or commit statuses for that head. These documentation edits are local and are not part of the PR.
- **Next improvement:** Complete the installed Windows in-app update, interactive installation, restart and library/DPAPI preservation checks; verify live provider and native keyboard behavior. The user stopped Computer Use before installed-app acceptance.

## 2026-11-12 — Verified candidate guidance and settings

- Prepared 0.2.7/code18 candidate version fields, corrected Android light-theme contrast and desktop Space focus recovery, and added an actual isolated-Stage screenshot to the Windows Spotify tutorial. Made Windows Settings scrollable so lower controls remain reachable.
- **Executed checks:** Shared and Android unit tests and Android release lint passed; the full desktop suite and Android debug compilation passed. The focused linked-playlist repository test passed on an Android emulator. In Windows Stage, the tutorial screenshot appeared and Settings scrolled to the Spotify controls. This was a preview, not an installed-app or updater test.
- **Next improvement:** Complete independent review, artifact builds and signing, installed Windows update/data-preservation acceptance, and publication verification.

## 2026-11-11 — Focus-safe desktop shortcuts and transport icons

- Added Space play/pause, Ctrl+Left/Right previous/next, Ctrl+Shift+Left/Right ten-second seek, Ctrl+Up/Down laptop volume, and Ctrl+M mute. Playback routes through the selected local, remote, or group target; unavailable remote focus never falls back to the laptop. Held Space repeats are suppressed until keyup. The shared compact/full transport uses accessible icons, and shortcuts bubble so text entry can consume typing.
- **Executed checks:** Candidate `:desktop:compileKotlin` and focused `DesktopPlaybackRoutingTest` plus `DesktopKeyboardShortcutsTest` passed (42s). Tests cover remote available/unavailable routing, group/Stage/local routes, key mapping, and Space repeat suppression. No interactive keyboard/text-field acceptance was run.
- **Remaining limits:** Native Windows key propagation through text fields and controls needs installed-app acceptance.
- **Next improvement:** Verify keyboard behavior in installed Windows Echo and complete the remaining release checks.
