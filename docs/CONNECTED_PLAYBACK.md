# Connected playback (0.2.2)

Published 0.2.2 adds a unified player interface for account-scoped current-song visibility and remote **play, pause, previous, next and absolute seek**. Android and Windows retain independent players and queues. This does not transfer audio or start synchronized listening, output groups or mixing. Install the [published 0.2.2 release](https://github.com/addy1243/Echo/releases/tag/v0.2.2) on both clients.

## Published unified player — 2026-10-29

The approved [unified connected player design](CONNECTED_PLAYER_DESIGN.md) is implemented in [published release 0.2.2](releases/0.2.2.md). The earlier 0.2.1 behavior is documented below as historical.

- Windows removes the permanent remote-device card rail. The bottom player shows the selected local or remote session; its compact device menu keeps offline records under Other devices. Move and Together expand in place, with accurate unavailable explanations.
- Android mini and full player share selected remote metadata, artwork and transport. A top-bar device entry works without a local queue. Devices, Move and Together are three circular labeled actions below full-player transport; the mini player reveals the same actions through its compact Devices button. Profile retains consent/settings.
- Selection changes the viewed/controlled player without starting or stopping either native engine. Local Play selects this device. Missing targets retain their last metadata with Reconnecting and disabled controls. Account changes/sign-out clear account-associated selection.
- Existing remote play/pause/previous/next and absolute seek use fresh device/session/track/queue guards. Seek dragging captures its target and is cancelled on a target or occurrence change. Remote volume, queue, shuffle, repeat and timers are not offered by v1.
- Snapshot title/artist remain visible without a portable track ID. Artwork uses an exact library match or a validated canonical YouTube ID; other sources keep a placeholder. Metadata does not become a playable local file.

The implementation reuses the existing RTDB protocol, rules and native engines. Remote progress follows confirmed snapshots, normally on state changes/five-second heartbeats; smooth synchronized progress, transfer, groups, audio relay, endpoint routing and closed-phone control remain unimplemented.

Local verification: all **208 JVM tests** pass (25 app, 66 data, 24 playback, 93 desktop), Android debug APK/test APK and lint pass, and the separate Windows app image builds with the existing GPL Temurin runtime. Actual Windows launch, laptop-sized bottom player, compact picker and in-place Move expansion were visually checked. Two emulator UI checks pass, including idle-player device selection, remote metadata, retained disconnect state with disabled controls, expanded mini-player actions and tab navigation (23.513 seconds). Full and mini captures were visually reviewed. Expanded full-player choices scroll into view once after placement; the mini player's whole expanded area is bounded to 40% of screen height and scrolls internally.

The actual Android-emulator/native-Windows paired check passes through the toolbar entry, inline device list and selected remote player (111.862 seconds). Both directions pass acknowledged play/pause, absolute seek and next/previous. The Windows player displayed the emulator's actual fixture song; after disconnection its selected metadata remained with disabled controls and Reconnecting. Selection was returned to the local laptop afterward. Fixture queues, account and opt-in settings were restored by the emulator check. No physical phone was accessed.

The test helper's bounded mailbox cleanup read now specifies `orderBy="createdAt"`. After explicit owner authorization, the fingerprint-restricted cleanup removed **3 offline Windows acceptance registrations** and their command records. A read-only Firestore check confirmed **0 playback-test tracks** in the real library. Real devices and library records were excluded from deletion. Evidence: `build/unified-player-approved-cleanup.log`.

Evidence: `build/unified-player-final-guard-build.log`, `build/unified-player-final-guard-ui.log`, `build/unified-player-native-peer-pass.log`, `build/unified-player-paired-acceptance.log`, and `build/unified-player-{full,mini}.png`.

Current local build and release artifact paths:

- Windows app image: `build/player-release/compose/binaries/main/app/EchoMusic/EchoMusic.exe`.
- Windows MSI: `build/releases/0.2.2/EchoMusic-Windows-x64-0.2.2.msi`.
- Android debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Public Android and Windows updater download/identity checks passed from 0.2.1; system installation was not accepted. See the release record.

## Published 0.2.1 behavior

## Setup and use

1. Use the existing Firebase project `echo-85da4` and sign into the same Google account on both devices. Complete **Connect Firebase / Sync now** once so the app has its Firebase session.
2. The owner supplied the RTDB instance `https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app`. The owner published its separate [database rules](../firebase/database.rules.json) in **Realtime Database → Rules**. Firestore rules and the library database remain unchanged. The local Firebase CLI could not access the project; the owner completed Console publication.
3. On Android, open **Profile → Connected playback** and enable the switch. On Windows, open **Devices & account → Allow remote controls on this laptop**. Each switch defaults off. Turning it on shares the current title, artist and playback state with the same account and allows its other connected Echo devices to control this player.
4. Keep Echo open on both devices. Android participation follows the Activity's STARTED lifecycle; background/closed-phone control is not provided by this initial version. Windows participates while the app runs. Select another device from the player picker to view and control its session; your local player is unaffected.

The app polls indexed, bounded RTDB paths approximately every two seconds and publishes on state changes or a five-second heartbeat. This is a near-real-time first version, not a persistent SDK listener. Disconnected devices lose their control availability after the 15-second heartbeat lease; a failed poll immediately disables the displayed controls. A stop/sign-out attempts a bounded offline update; stale records can remain stored.

## Data and command safeguards

- Firestore continues to store playlists, saved metadata, favorites and bounded history. RTDB contains only per-device playback snapshots, commands and acknowledgments under the verified owner's `users/{uid}/playback` path.
- No audio, local paths, signed/resolved streams, credentials or authentication headers are included in snapshots. Nonportable desktop track IDs are hashed; Android local IDs are omitted. Short-lived Firebase tokens authenticate requests and are never logged in request URLs/errors.
- Each receiving session has its own ID. Commands expire after 20 seconds and carry expected track/queue revisions; stale commands are rejected. A processing acknowledgment prevents an interrupted command from being replayed. If the receiver stops between processing and execution, the sender cannot assume success and must inspect the updated player state before retrying.
- One command per sender waits for an acknowledgment. Processed/expired commands are removed by the receiver after their retention window. The reader caps device/mailbox queries at 32 entries and response bodies at 256 KiB.
- Database rules isolate verified account owners. They do not cryptographically distinguish devices sharing the same Firebase UID. Device opt-in is enforced by Echo; stronger device-specific trust/revocation would require a trusted registration service.

## Verification status

Local RTDB emulator checks pass for anonymous, unverified and cross-account denial; forbidden paths/fields; malformed commands; expired commands; and valid acknowledgments/cleanup. JVM checks cover bounded authenticated requests, database URL restrictions, duplicate prevention, acknowledgments without null fields and stale session/track/queue/seek rejection. A two-client in-memory transport test verifies applied-once control and old-track rejection.

All 204 JVM tests, Android build/lint and the emulator Profile UI check pass. The same-key build is installed only on the dedicated emulator, preserving its library and sign-in. The actual Windows native adapter check passes repeated play/pause, paused seek followed by playback at the requested position, next/previous and stale-occurrence rejection. Android APK and isolated Windows MSI builds pass. Live RTDB controls pass in both directions between two actual native players, and anonymous root access is denied (HTTP401). The actual Android emulator and native Windows player also pass acknowledged play/pause/seek/next/previous in both directions including banner navigation (Android instrumentation 124.578 seconds), with fixture cleanup and queue/preferences restoration. The emulator Profile UI check passes in 11.853 seconds. Android release shrinking passes. Windows installer acceptance and physical-phone behavior remain unverified.

Run local rules checks with:

```powershell
firebase emulators:exec --config firebase/firebase.json --project demo-echo-rules --only database "node firebase/database.rules.test.mjs"
```

The opt-in `:desktop:connectedPlaybackLiveCheck -PechoConnectedLocalOnly=true` exercises the actual native adapter with temporary silent audio and libraries. Without that property the check uses the saved Firebase account, creates two temporary player registrations in RTDB and verifies acknowledged controls in both directions. Neither check changes the normal desktop queue or syncs a library. No physical phone is accessed by these checks.
