# Next session handoff — 2026-10-29

The owner asked to stop feature implementation in this session and continue in a fresh chat. First fix the installed Windows upgrade, then implement functional Move and Play together. Use Sol for architecture/design/review and Luna for coding. Android testing is emulator-only; preserve credentials, library data and the signing identity.

## First priority: the installed Windows app still reports 0.2.1

Fresh inspection confirmed `C:\Users\adars\AppData\Local\EchoMusicDesktop\app\EchoMusic.cfg` contains `-Djpackage.app-version=0.2.1`. Both personal Start-menu shortcuts (`Programs\EchoMusic.lnk` and `Programs\Unknown\EchoMusic.lnk`) target `C:\Users\adars\AppData\Local\EchoMusicDesktop\EchoMusic.exe`. No EchoMusic process was returned at inspection. Do not assume this is merely the old portable preview being launched.

The real 0.2.1-baseline updater test selected public 0.2.2, downloaded the complete MSI, and passed size/SHA256/native product/version/UpgradeCode/x64 validation. It deliberately did not launch installation. Thus download acceptance does **not** prove the user's installed app upgraded. Investigate the installed app's update state, installer launch/error handling, MSI registration/upgrade behavior and Windows Installer events. Reproduce the real interactive update, retain a verbose MSI log, and verify the same installed path and Start-menu launch report 0.2.2 afterward. Let the owner handle normal system prompts; do not use quiet installation, uninstall, clear data or replace files with a portable preview.

Published release: <https://github.com/addy1243/Echo/releases/tag/v0.2.2>.

- Local MSI: `build/releases/0.2.2/EchoMusic-Windows-x64-0.2.2.msi`.
- MSI SHA256: `08a1bf18d2a42a02351d981d9fdb6d606b71c76d8239c54c842248963e72aed7` (68,160,723 bytes).
- Installed program: `%LOCALAPPDATA%\EchoMusicDesktop`; shared user data/credentials: `%LOCALAPPDATA%\EchoMusic`. Keep these separate.
- Updater evidence: `build/player-release-public-windows-update.log` (passed in 5m52s).
- Public APK/MSI download/checksum evidence: `build/player-release-public-verification.log`.
- Android public updater passed code12→code13 on emulator-5556 (145.661s), without accepting installation: `build/player-release-public-android-update.log`.

## Current release and next requested behavior

Immutable v0.2.2 source: `3d143945750932681249041a31da64de38dc9332`. Post-publication docs were pushed in `fcd37ed`. The default branch is `codex/playlist-discovery`, remote `addy1243/Echo`. Do not replace published tags/assets. See [release record](releases/0.2.2.md).

0.2.2 ships the approved unified selected-device player UI and existing remote transport controls. Move, Play together, audio relay and output routing are **not implemented**. The owner expected those features and requested a follow-up called 0.2.2.5; the orchestrator proposed **0.2.3** because the existing Windows updater/release contract requires three numeric components. No 0.2.3 code or artifacts have been prepared.

The owner clarified that Move must carry the current song, position and remaining playback queue. Play together must use a shared queue and coordinate song changes across selected devices. Saved playlists/favorites/history remain separate Firebase library sync records; a playback queue transfer must not overwrite saved playlists. Preserve duplicate occurrences and actual shuffle traversal. Expose unsupported source/queue entries explicitly; never silently omit them.

Read [approved design](CONNECTED_PLAYER_DESIGN.md), [current connected behavior](CONNECTED_PLAYBACK.md), ARCHITECTURE.md, DECISIONS.md, AGENT_TASKS.md and INTEGRATION_STATUS.md before shared-contract changes. The latest Sol analysis suggested a parallel v2 RTDB subtree, conditional ETag CAS for cooperative operation/group fencing, actual staged native preparation, and Android service-owned participation. Existing v1 rules cannot carry queue/group payloads. These are investigation directions, not completed implementation or a finalized API contract. No revised Firebase rules were published. Do not turn a chain of PLAY/PAUSE commands into a claimed reliable transfer or promise acoustic synchronization without measurements.

The original design prototype is `C:\Users\adars\.codex\visualizations\2026\10\26\01a1796a-e2df-75d2-a2f2-eb4445261468\echo-player-design.html`. Preserve the approved compact picker, inline dropdowns and three circular Android actions. Do not restore the large device-card rail.

Before release, verify actual native Windows ↔ Android-emulator transfer/group behavior, stale sessions, preparation failure, disconnect/rejoin, queue duplicates, independent queue preservation, and supported group transport. Report implemented versus verified versus unavailable precisely. Any future rules requiring owner Console publication must be prepared and tested before asking for that final setup step.
