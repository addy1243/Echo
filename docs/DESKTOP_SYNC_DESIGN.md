# Desktop account sync and connected audio

Status: design and implementation boundaries, 2026-10-26. This document does not claim any live account transfer, remote control, synchronized playback or audio relay has been implemented or tested. The first desktop milestone is local desktop listening with a desktop layout. The owner requested all modes below; they are separate capabilities, not alternative interpretations of one feature.

## What the existing app actually provides

- `core/Models.kt` is portable Kotlin/JVM. Stable provider track IDs and canonical source pages can cross platforms; expiring media URLs must continue to resolve locally.
- `app/account/DrivePlaylistSync.kt` uploads one private playlist snapshot per device. Its envelope is `echo-device-playlists`, version 1. The existing foreground automatic task polls approximately once per minute. A stored device count is registered snapshots, not evidence that a device is currently online or playing.
- `CloudPlaylistSnapshot.kt` deliberately removes favorites, history, local matches and file paths. Simply adding a desktop Drive client will not sync likes or history.
- `PlaylistSyncMerge.kt` already preserves duplicate occurrences and makes deterministic conflict copies. Missing IDs mean deletion only against a known complete baseline; a previously unseen stale device can currently restore deleted playlists.
- History uses device-local auto-increment `Long` IDs. A start and its terminal event are separate records. Actual listening duration excludes pause/buffering and is measured with a monotonic clock. Current retention is 90 days/5,000 events, with 500 displayed. Clearing history also clears local preference evidence. See [history semantics](HISTORY_NOTES.md).
- Android sign-in delegates to AccountManager. Desktop needs a separate registered installed-app OAuth flow. Android local libraries remain device-wide on sign-out; sync must not silently change that behavior. See [account contract](ACCOUNTS.md).

No Android data or live cloud state is changed by this design.

## Delivery order

| Stage | User-visible result | Completion evidence |
|---|---|---|
| 1. Desktop listening | Real desktop search, local/provider playback, persistent library and a desktop player layout | Actual audio progression/seek/pause/next, error handling, restart persistence and packaged launch |
| 2. Complete library sync | Same playlists, likes and optionally history on both clients | Two-client live sign-in/consent; offline edits, unlikes, deletions, history clear and account switching converge without loss |
| 3. Devices and remote control | Phone and laptop show each other's live song; either can control the selected player; both can play independent songs | Stale/offline indication, command acknowledgments, reconnect, foreground/background and cross-account denial checks |
| 4. Laptop output groups | One song through all selected laptop outputs; optional independent song/queue per output | Two real outputs, device removal/reconnect, volume and measured drift/latency |
| 5. Cross-device listening group | Same recording plays on selected devices with measured synchronization | Real phone/laptop timing and drift measurements over wired/Bluetooth/network combinations |
| 6. Audio relay and mix | Audio from phone and laptop can reach one chosen receiving output and be mixed | Echo-only relay, authorization, delay, dropout, feedback-loop and audio-level checks |

Stages 3 and 4 can proceed independently after the playback engine exposes the necessary controls. Do not make a polished desktop mockup appear connected before the corresponding transport is working.

## Stage 2: the smallest durable account-sync extension

Reuse Drive for library metadata initially; it already has account consent and transport validation. Do not put frequent playback position updates or audio into Drive files. A dedicated real-time service can be introduced for connected playback without first replacing the working library storage.

### Desktop account boundary

Register a Desktop OAuth client under the same Google Cloud application/project as the Android client. Use the system browser, PKCE, unpredictable state and a short-lived loopback callback bound to the loopback interface. Verify returned identity and account binding. Keep refresh credentials in Windows credential protection, never plaintext library JSON, logs or source. Request Drive app-data access separately from ordinary listening and explain the uploaded categories. The same account email alone is not sufficient authorization evidence.

Google's installed-app flow is documented in [Desktop OAuth](https://developers.google.com/identity/protocols/oauth2/native-app). Drive app-data is application-scoped; verify that the registered desktop and Android clients actually see the same account's app-data during live acceptance rather than assuming a copied client ID makes them compatible. See [Drive app-data](https://developers.google.com/workspace/drive/api/guides/appdata).

### Versioned format and safe rollout

Add a new full-library sync envelope/file namespace, rather than changing the meaning of the existing version-1 playlist file. Ship support on Android and desktop together before enabling full sync. V1 clients must not be allowed to silently overwrite the new account state: explain the minimum compatible version and require an explicit migration/import path for legacy snapshots. Export a local metadata backup before the first migration; keep it independent of the rolling sync snapshots.

The full state needs:

- Account binding, stable installation ID, format version, device revision and acknowledged merge baselines.
- Playlist metadata, ordered occurrences and tracks, with existing conflict-copy behavior.
- Explicit playlist deletion records retained until all participating devices have acknowledged them; retire long-offline devices explicitly before compacting tombstones. Missing records in a partial/failed fetch are never deletion evidence.
- Likes **and unlikes** with mutation identity and causal revision. A union of liked IDs cannot implement unlike. Concurrent contradictory edits need a documented deterministic rule; use causal order and a stable tie-break for concurrent changes, not wall-clock timestamps alone. Carry track metadata for liked songs not referenced by a playlist.
- History with stable cross-device identity. For existing rows, a persisted installation identity plus row ID can supply a deterministic migration key; reinstall identities must not be reused. New events also need a stable listening-occurrence ID so starts and terminal events stay related. Re-reading snapshots must not create another play or increase listened time.
- An account-history clear operation/epoch acknowledged by every client. Old snapshots must not restore cleared events. Old-epoch events are rejected; an offline client needs to observe the new epoch before its subsequent history can join the account history. Local-only clear and account-wide clear must be distinct actions.

Do not sync derived preference totals: recompute from de-duplicated events, preserving actual listening-time semantics. A synchronized group should mark the common group occurrence so account recommendations can avoid counting the same group listen once per speaker; retain device playback diagnostics separately. Independent songs remain independent listening events.

History upload is a new privacy behavior. Add a clear opt-in and retention explanation; do not silently upload old local history merely because playlist sync was previously enabled. The owner's request authorizes implementing the feature; the migration still needs a comprehensible in-app choice for each existing installation. Disconnecting sync retains local playlists/files and stops further upload. Account switching must not automatically upload the previous account's cloud-derived history to the next account.

Keep current URL/identity/body-size validation, cancellation, rate-limit handling and guarded transactional apply. Never sync audio, signed stream URLs, OAuth credentials, private filesystem paths or another device's numeric MediaStore IDs. Remote-local tracks remain metadata until explicitly matched to a source on the receiving device.

Tests must cover duplicate sync delivery, retries after partial failure, migration preservation, simultaneous playlist edits, stale-device deletes, like/unlike conflicts, liked-only tracks, duplicate history starts/terminals, history clearing against an offline device, invalid identities, interrupted apply and account switching. Two-device live acceptance remains separate from fixture tests.

## Stage 3: current devices and independent playback

Drive snapshots are unsuitable as a live presence/command channel. Google Drive push notifications require a public HTTPS receiving server and report resource changes, not a low-latency player session. See [Drive notification requirements](https://developers.google.com/workspace/drive/api/guides/push). Rapid polling adds quota/latency without solving clock coordination.

Use an authenticated WebSocket service for account-scoped device sessions. [WebSocket](https://www.rfc-editor.org/rfc/rfc6455) supplies two-way transport; Echo must supply authentication, per-account authorization, expiry and command semantics. A local-network prototype can prove timing, but it does not satisfy account-wide use over different networks.

Minimum protocol:

- Stable device ID and replaceable connection/session ID, capability list, human-friendly name and heartbeat lease. Show last seen; an expired lease is offline, not paused.
- One or more player/session IDs per device. Current track ID, occurrence ID, play/pause/buffer/error state, bounded position, rate, queue revision and state sequence. Exchange canonical metadata, not raw private paths or resolved URLs.
- Commands target a specific player and include a unique command ID, expected session/revision and expiry. Acknowledge applied/rejected/unsupported; retries are idempotent. Reject stale commands after reconnect and never treat an optimistic UI update as confirmed playback.
- Remote control is an explicit setting per receiving device. Show the controller/device being targeted. Sign-out, revoke-device and account changes immediately stop access and remove live credentials. Authenticate the backend independently; do not treat arbitrary Google Drive access tokens as backend identity tokens.
- Separate songs are normal independent players under the same account. Starting music on the laptop does not automatically pause the phone unless the user selects transfer/join. Transfer prepares the target and waits for readiness before pausing the source, preserving the source on failure.

Attach Android presence to the existing playback service while music is active. An idle closed app is not guaranteed to stay reachable; label unsupported/background states honestly. Do not launch indefinite background services merely to simulate being online. Android foreground-service types and start restrictions still apply: [service documentation](https://developer.android.com/develop/background-work/services/fgs/service-types).

Owner provisioning needed: a selected service host, HTTPS/WSS domain, verified identity/auth configuration, account access rules, device revocation, storage/retention policy, deployment credentials and cost limits. There is currently no connected-playback server implementation or provisioned service in this repository. These are future implementation requirements, not capabilities that can be enabled by a desktop settings toggle. Keep desktop playback usable while this is unconfigured.

## Stages 4 and 5: same song on multiple outputs/devices

Laptop primary mode is one song on all selected outputs. Each physical endpoint needs its own renderer; the OS default output is not the set of all devices. Windows exposes per-endpoint rendering and applications can route separate streams to different endpoints: [device roles](https://learn.microsoft.com/en-us/windows/win32/coreaudio/device-roles), [rendering streams](https://learn.microsoft.com/en-us/windows/win32/coreaudio/rendering-a-stream). The chosen desktop engine must expose endpoint selection or a lower-level renderer before this can be implemented. Launching two players at the same instant is only an experiment, not proof of synchronization.

For one song across outputs, prefer one decoded timeline feeding independently buffered renderers. Measure output latency and drift, apply per-output delay/resampling, and keep volume controls independent. Handle unplug, Bluetooth profile changes, default-device changes and unavailable/exclusive devices without blasting audio through an unselected speaker. The optional independent-output mode gives each endpoint its own player/queue and selects that player explicitly in the UI.

Cross-device groups need a coordinator, membership/epoch, leader and scheduled start on an estimated common timebase. Estimate clock offset and round-trip delay; prebuffer and require ready acknowledgments; schedule a future start, observe actual position and correct drift gently. Use monotonic clocks for local timing, not wall-clock time for elapsed playback. Revision/epoch checks prevent old leaders or delayed commands from steering a new group. Define behavior for buffering or dropped members and preserve individual queues when leaving a group.

Each member should resolve the same stable recording locally. Different uploads/edits/durations cannot be assumed aligned. Availability failure is explicit; do not silently substitute another recording. Multiple Bluetooth outputs and phone/laptop combinations can have different variable delays. Set and measure a practical synchronization target on actual hardware; do not promise sample-accurate or zero-latency playback from a protocol design.

## Stage 6: combine phone and laptop audio into one output

This is actual audio transport and mixing, beyond remote control. The first scope should be audio produced by Echo, not microphone, notifications or arbitrary system capture. Use explicit send/receive controls and a visible active-relay indication. Start over the same trusted network; public-network support requires signaling and usually NAT traversal/relay infrastructure with bandwidth costs.

Evaluate a mature real-time media implementation rather than inventing a codec/transport. [WebRTC](https://www.w3.org/TR/webrtc/) defines real-time media communication, but Android/JVM library selection, licensing, packaging, signaling and TURN operations still need an implementation spike. A receiver needs independent jitter buffers, decoded PCM mixing, per-source gain, clipping prevention and loop detection. Relaying the mixed output back to its own input must be prohibited. Signed provider URLs are not a substitute for relaying audio; forwarding them also breaks the existing private-source boundary.

For the same recording available on both clients, local synchronized rendering is cheaper than sending audio. Relay is needed for genuinely remote/device-local audio or a combined mix. Only audio Echo can legitimately decode may enter this path; provider restrictions remain in force. Verify start/stop/revoke, latency, dropouts, sleep/reconnect, incompatible formats and two different songs reaching one output before calling it complete.

## Next concrete work after desktop playback

First prove desktop Google sign-in and a **manual playlist-only** transfer with the existing Android version, using a registered Desktop client and the same Cloud project. This validates the real account/app-data boundary without exposing history or changing the Android database. Then implement full-library v2 plus Android migration/tests as a coordinated change. In parallel, make a short local multi-output engine spike to confirm the selected playback engine can route to two actual Windows endpoints; keep its results separate from account sync and cross-device timing claims.
