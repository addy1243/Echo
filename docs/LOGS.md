# Orchestrator work log

Keep the three most recent completed work sessions only. Add a concise entry after a session is complete, including its date, what changed, executed evidence, and the next improvement. Do not copy proposed or failed checks as if they passed.

## 2026-11-17 — Publish 0.2.7.1

- Isolated the requested Spotify, Android recovery and artwork, and Windows Space fixes from published 0.2.7. Android is version 0.2.7.1/code19; the Windows MSI and app use 0.2.8 for compatibility with existing updaters.
- **Executed checks:** Core, data, Android playback and app, and desktop unit suites passed. Spotify parser tests and Android debug compilation passed. Review identified and prompted fixes for the recovery helper and backup durability. Artifact and runtime acceptance remain separate.
- **Release evidence:** Published the four assets at `v0.2.7.1` from `6ba33c20a6fd9ed0f6a74bfd5201a3ca5dd1bab2`. Android release build/lint, original signer and package checks, and an in-place 0.2.7 to 0.2.7.1 emulator upgrade and launch passed. MSI identity, public asset checks, updater helper download, and installed 0.2.7 app discovery/download/verification passed. See [the release record](releases/0.2.7.1.md).
- **Next improvement:** Complete interactive Windows installation, restart and data/credential preservation, and verify emulator recovery and native keyboard behavior.

## 2026-11-12 — Verified candidate guidance and settings

- Prepared 0.2.7/code18 candidate version fields, corrected Android light-theme contrast and desktop Space focus recovery, and added an actual isolated-Stage screenshot to the Windows Spotify tutorial. Made Windows Settings scrollable so lower controls remain reachable.
- **Executed checks:** Shared and Android unit tests and Android release lint passed; the full desktop suite and Android debug compilation passed. The focused linked-playlist repository test passed on an Android emulator. In Windows Stage, the tutorial screenshot appeared and Settings scrolled to the Spotify controls. This was a preview, not an installed-app or updater test.
- **Next improvement:** Complete independent review, artifact builds and signing, installed Windows update/data-preservation acceptance, and publication verification.

## 2026-11-11 — Focus-safe desktop shortcuts and transport icons

- Added Space play/pause, Ctrl+Left/Right previous/next, Ctrl+Shift+Left/Right ten-second seek, Ctrl+Up/Down laptop volume, and Ctrl+M mute. Playback routes through the selected local, remote, or group target; unavailable remote focus never falls back to the laptop. Held Space repeats are suppressed until keyup. The shared compact/full transport uses accessible icons, and shortcuts bubble so text entry can consume typing.
- **Executed checks:** Candidate `:desktop:compileKotlin` and focused `DesktopPlaybackRoutingTest` plus `DesktopKeyboardShortcutsTest` passed (42s). Tests cover remote available/unavailable routing, group/Stage/local routes, key mapping, and Space repeat suppression. No interactive keyboard/text-field acceptance was run.
- **Remaining limits:** Native Windows key propagation through text fields and controls needs installed-app acceptance.
- **Next improvement:** Verify keyboard behavior in installed Windows Echo and complete the remaining release checks.
