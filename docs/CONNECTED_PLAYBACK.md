# Connected playback (0.2.1 candidate)

This adds account-scoped current-song visibility and remote **play, pause, previous, next and seek ±10 seconds**. Android and Windows retain independent players and queues. This does not transfer audio or start synchronized listening, output groups or mixing. The published 0.2.0 apps do not include it; both clients need the new build.

## Setup and use

1. Use the existing Firebase project `echo-85da4` and sign into the same Google account on both devices. Complete **Connect Firebase / Sync now** once so the app has its Firebase session.
2. The owner supplied the RTDB instance `https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app`. The owner published its separate [database rules](../firebase/database.rules.json) in **Realtime Database → Rules**. Firestore rules and the library database remain unchanged. The local Firebase CLI could not access the project; the owner completed Console publication.
3. On Android, open **Profile → Connected playback** and enable the switch. On Windows, open **Devices & account → Allow remote controls on this laptop**. Each switch defaults off. Turning it on shares the current title, artist and playback state with the same account and allows its other connected Echo devices to control this player.
4. Keep Echo open on both devices. Android participation follows the Activity's STARTED lifecycle; background/closed-phone control is not provided by this initial version. Windows participates while the app runs. Choose the other device's card to control its player; your local player is unaffected.

The app polls indexed, bounded RTDB paths approximately every two seconds and publishes on state changes or a five-second heartbeat. This is a near-real-time first version, not a persistent SDK listener. Disconnected devices lose their control availability after the 15-second heartbeat lease; a failed poll immediately disables the displayed controls. A stop/sign-out attempts a bounded offline update; stale records can remain stored.

## Data and command safeguards

- Firestore continues to store playlists, saved metadata, favorites and bounded history. RTDB contains only per-device playback snapshots, commands and acknowledgments under the verified owner's `users/{uid}/playback` path.
- No audio, local paths, signed/resolved streams, credentials or authentication headers are included in snapshots. Nonportable desktop track IDs are hashed; Android local IDs are omitted. Short-lived Firebase tokens authenticate requests and are never logged in request URLs/errors.
- Each receiving session has its own ID. Commands expire after 20 seconds and carry expected track/queue revisions; stale commands are rejected. A processing acknowledgment prevents an interrupted command from being replayed. If the receiver stops between processing and execution, the sender cannot assume success and must inspect the updated player state before retrying.
- One command per sender waits for an acknowledgment. Processed/expired commands are removed by the receiver after their retention window. The reader caps device/mailbox queries at 32 entries and response bodies at 256 KiB.
- Database rules isolate verified account owners. They do not cryptographically distinguish devices sharing the same Firebase UID. Device opt-in is enforced by Echo; stronger device-specific trust/revocation would require a trusted registration service.

## Verification status

Local RTDB emulator checks pass for anonymous, unverified and cross-account denial; forbidden paths/fields; malformed commands; expired commands; and valid acknowledgments/cleanup. JVM checks cover bounded authenticated requests, database URL restrictions, duplicate prevention, acknowledgments without null fields and stale session/track/queue/seek rejection. A two-client in-memory transport test verifies applied-once control and old-track rejection.

All 204 JVM tests, Android build/lint and the emulator Profile UI check pass. The same-key candidate is installed only on the dedicated emulator, preserving its library and sign-in. The actual Windows native adapter check passes repeated play/pause, paused seek followed by playback at the requested position, next/previous and stale-occurrence rejection. Android APK and isolated Windows MSI candidates build successfully. Live RTDB controls pass in both directions between two actual native players, and anonymous root access is denied (HTTP401). The actual Android emulator and native Windows player also pass acknowledged play/pause/seek/next/previous in both directions (Android instrumentation112.945 seconds), with fixture cleanup and queue/preferences restoration. The emulator Profile UI check passes11.853 seconds. Android release shrinking passes. Windows installer acceptance and physical-phone behavior remain unverified.

Run local rules checks with:

```powershell
firebase emulators:exec --config firebase/firebase.json --project demo-echo-rules --only database "node firebase/database.rules.test.mjs"
```

The opt-in `:desktop:connectedPlaybackLiveCheck -PechoConnectedLocalOnly=true` exercises the actual native adapter with temporary silent audio and libraries. Without that property the check uses the saved Firebase account, creates two temporary player registrations in RTDB and verifies acknowledged controls in both directions. Neither check changes the normal desktop queue or syncs a library. No physical phone is accessed by these checks.
