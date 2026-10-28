# Desktop account sync and connected audio

Current status: Android/Desktop library and account sync through Firestore is implemented and verified in published 0.2.0; see [Firebase evidence](FIREBASE.md) and [release acceptance](releases/0.2.0.md). The 0.2.1 candidate implements opt-in RTDB current-song visibility and remote controls while Echo is open; owner-only rules are published and live acknowledged emulator/native-Windows controls pass. Synchronized playback, output groups and audio relay remain future work. See [implementation, setup and verification](CONNECTED_PLAYBACK.md).

Status: design and implementation boundaries, 2026-10-28. Desktop listening and library sync are shipped. The 0.2.1 connected-control candidate is separate from synchronized playback or audio relay, and is not yet a published release.

## What the existing app actually provides

- `core/Models.kt` is portable Kotlin/JVM. Stable provider track IDs and canonical source pages can cross platforms; expiring media URLs must continue to resolve locally.
- `core/Models.kt` provides portable `Track` metadata and stable provider IDs. Android playback is owned by `EchoPlaybackService` and exposed locally through `MediaController`; desktop playback is exposed through `DesktopPlaybackState` and `DesktopPlayback` controls.
- Firestore is the current optional account-sync backend in project `echo-85da4`. It syncs the bounded owner library document, not live playback state. Existing Drive snapshots are legacy data; do not make them the prerequisite or transport for connected playback.
- Android local libraries remain device-wide on sign-out. Firebase identity, consent, token storage and account-switch handling must continue to follow [the account contract](ACCOUNTS.md).

This document describes the boundaries; current implementation and executed checks are recorded in [connected playback](CONNECTED_PLAYBACK.md).

## Delivery order

| Stage | User-visible result | Completion evidence |
|---|---|---|
| 1. Desktop listening | Real desktop search, local/provider playback, persistent library and a desktop player layout | Actual audio progression/seek/pause/next, error handling, restart persistence and packaged launch; shipped in 0.2.0 |
| 2. Complete library sync | Same playlists, likes, saved metadata and history on both clients | Firestore sync and normal emulator/desktop equality verified in 0.2.0; see [Firebase evidence](FIREBASE.md) |
| 3. Devices and remote control | Phone and laptop show each other's live song; either can control the selected player; both can play independent songs | RTDB presence lease, command acknowledgments, reconnect, foreground/background and cross-account denial checks |
| 4. Laptop output groups | One song through all selected laptop outputs; optional independent song/queue per output | Two real outputs, device removal/reconnect, volume and measured drift/latency |
| 5. Cross-device listening group | Same recording plays on selected devices with measured synchronization | Real phone/laptop timing and drift measurements over wired/Bluetooth/network combinations |
| 6. Audio relay and mix | Audio from phone and laptop can reach one chosen receiving output and be mixed | Echo-only relay, authorization, delay, dropout, feedback-loop and audio-level checks |

Stages 3 and 4 can proceed independently after the playback engine exposes the necessary controls. Do not make a polished desktop mockup appear connected before the corresponding transport is working.

## Stage 2: library/account sync — shipped

Firestore in `echo-85da4` is the account library source of truth for the implemented playlist, favorite, saved-track and history sync. Preserve its owner-only rules, explicit consent, bounded payload, merge semantics and local-data protections. Connected playback is a separate RTDB feature; it must not add frequent position writes to the Firestore library document or send audio through either database.

## Stage 3: current devices and independent playback

Use RTDB in the existing Firebase project `echo-85da4` for account-scoped live state and commands; do not add another playback server for the first milestone. RTDB's authenticated client listeners provide the update channel. The clients already hold Firebase sessions and platform playback APIs, so the shared Kotlin REST/OkHttp path can be extended without changing Firestore library sync.

### RTDB data and command contract

Under `/users/{uid}` keep device state separate from the library document:

- `/devices/{deviceId}` contains a replaceable session ID, bounded device label/capabilities, active player state, sequence/revision, stable track ID, safe display metadata, playback state, position, playback rate and server-updated heartbeat. Do not send local paths, temporary/resolved media URLs, request headers, audio, or credentials.
- `/commands/{targetDeviceId}/{commandId}` contains a bounded command (`play`, `pause`, `seek`, `next`, `previous` or track selection), target session, expected state revision and expiry. The target rejects expired/stale/unsupported commands, applies valid commands through its existing player API, then records an applied/rejected acknowledgment. Command IDs make retries idempotent.
- Write state on meaningful player transitions and seeks, with a low-rate heartbeat while active; estimate displayed remote position from the last position, rate and timestamp instead of writing every playback tick. Expired heartbeat means **offline**, not paused. Keep the service usable when RTDB is unavailable.

Rules deny access by default and allow only verified Firebase UID access to that UID's narrowly validated subtree. Never use public test-mode rules. This protects data across accounts. A device ID is client supplied, however, so same-account clients are not distinguishable by these rules; per-device authorization or enforceable remote-control opt-in needs trusted device registration/backend-issued claims. Until then, remote control is a same-account capability and the receiving app must visibly let its owner disable it.

The current clients use REST, not the Firebase Realtime Database SDK. REST supports authenticated Server-Sent Event streams, but not the SDK's `/.info/connected` and server-side `onDisconnect` presence lifecycle. REST-only presence therefore uses a server-timestamp heartbeat lease; after crashes/disconnects clients show offline once the lease expires, while stale records may remain stored until the device reconnects or a cleanup path runs. RTDB REST streaming authenticates with the Firebase ID token in an `auth` query parameter, so never log the tokenized URL. If reliable immediate disconnect cleanup is required, add the RTDB SDK presence primitives and validate Android/Desktop dependency compatibility before implementation. See [RTDB REST streaming](https://firebase.google.com/docs/reference/rest/database/), [REST authentication](https://firebase.google.com/docs/database/rest/auth), and [RTDB presence](https://firebase.google.com/docs/database/android/offline-capabilities).

### Console setup before live implementation

In Firebase Console, select `echo-85da4`, create a Realtime Database instance in **production/locked mode**, and choose its region. Supply the exact Console-provided `databaseURL`/instance hostname to the implementation before enabling any live feature. Then prepare and emulator-test a separate RTDB rules file: deny by default, owner-only paths, strict key and payload validation, no root listing. Do not deploy permissive rules to make the client work. Console instance creation, rules deployment and live client acceptance are not done by this design document.

Connect only while the Android playback service is active or the desktop app can maintain its session. Do not start an indefinite Android service solely to advertise idle presence. Treat phone and laptop as independent players; remote control does not pause the source or transfer audio unless the user explicitly chooses a transfer/join flow and the target confirms readiness.

This protocol shares track identity/metadata and control intent, **not audio**. For `youtube:<videoId>`, each target resolves the stable recording locally using its own provider; other stable IDs likewise require a playable local/remote source on that device. Metadata alone cannot guarantee the same recording. Same-song group playback additionally needs prebuffering, ready acknowledgments, scheduled start on an estimated common clock, drift correction and hardware measurements. Actual audio relay/mixing is a later separate feature.

Attach Android presence to the existing playback service while music is active. An idle closed app is not guaranteed to stay reachable; label unsupported/background states honestly. Do not launch indefinite background services merely to simulate being online. Android foreground-service types and start restrictions still apply: [service documentation](https://developer.android.com/develop/background-work/services/fgs/service-types).

Before release, verify rule enforcement with the RTDB emulator, then live presence, cross-account denial, command expiry/acknowledgment, reconnect, service foreground/background and opt-out behavior on emulator plus desktop. RTDB transport, separate owner-only rules and platform adapters now exist in the 0.2.1 candidate. A settings toggle alone does not prove a live connection; state/acknowledgments and lease expiry determine availability. Keep local playback usable while setup is pending.

## Stages 4 and 5: same song on multiple outputs/devices

Laptop primary mode is one song on all selected outputs. Each physical endpoint needs its own renderer; the OS default output is not the set of all devices. Windows exposes per-endpoint rendering and applications can route separate streams to different endpoints: [device roles](https://learn.microsoft.com/en-us/windows/win32/coreaudio/device-roles), [rendering streams](https://learn.microsoft.com/en-us/windows/win32/coreaudio/rendering-a-stream). The chosen desktop engine must expose endpoint selection or a lower-level renderer before this can be implemented. Launching two players at the same instant is only an experiment, not proof of synchronization.

For one song across outputs, prefer one decoded timeline feeding independently buffered renderers. Measure output latency and drift, apply per-output delay/resampling, and keep volume controls independent. Handle unplug, Bluetooth profile changes, default-device changes and unavailable/exclusive devices without blasting audio through an unselected speaker. The optional independent-output mode gives each endpoint its own player/queue and selects that player explicitly in the UI.

Cross-device groups need a coordinator, membership/epoch, leader and scheduled start on an estimated common timebase. Estimate clock offset and round-trip delay; prebuffer and require ready acknowledgments; schedule a future start, observe actual position and correct drift gently. Use monotonic clocks for local timing, not wall-clock time for elapsed playback. Revision/epoch checks prevent old leaders or delayed commands from steering a new group. Define behavior for buffering or dropped members and preserve individual queues when leaving a group.

Each member should resolve the same stable recording locally. Different uploads/edits/durations cannot be assumed aligned. Availability failure is explicit; do not silently substitute another recording. Multiple Bluetooth outputs and phone/laptop combinations can have different variable delays. Set and measure a practical synchronization target on actual hardware; do not promise sample-accurate or zero-latency playback from a protocol design.

## Stage 6: combine phone and laptop audio into one output

This is actual audio transport and mixing, beyond remote control. The first scope should be audio produced by Echo, not microphone, notifications or arbitrary system capture. Use explicit send/receive controls and a visible active-relay indication. Start over the same trusted network; public-network support requires signaling and usually NAT traversal/relay infrastructure with bandwidth costs.

Evaluate a mature real-time media implementation rather than inventing a codec/transport. [WebRTC](https://www.w3.org/TR/webrtc/) defines real-time media communication, but Android/JVM library selection, licensing, packaging, signaling and TURN operations still need an implementation spike. A receiver needs independent jitter buffers, decoded PCM mixing, per-source gain, clipping prevention and loop detection. Relaying the mixed output back to its own input must be prohibited. Signed provider URLs are not a substitute for relaying audio; forwarding them also breaks the existing private-source boundary.

For the same recording available on both clients, local synchronized rendering is cheaper than sending audio. Relay is needed for genuinely remote/device-local audio or a combined mix. Only audio Echo can legitimately decode may enter this path; provider restrictions remain in force. Verify start/stop/revoke, latency, dropouts, sleep/reconnect, incompatible formats and two different songs reaching one output before calling it complete.
