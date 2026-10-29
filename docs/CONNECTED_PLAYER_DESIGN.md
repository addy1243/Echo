# Unified connected player — approved design

Design reviewed by Sol product-design and systems-architecture agents on 2026-10-29 and approved by the owner. Luna implemented the first native UI milestone under Sol review, published in [0.2.2](https://github.com/addy1243/Echo/releases/tag/v0.2.2). Transfer, groups, output routing and background participation remain subsequent milestones. Current verification is recorded in [connected playback](CONNECTED_PLAYBACK.md).

The 0.2.1 clients introduced independent native players and acknowledged remote controls. Version 0.2.2 adds the unified player interface while keeping those sessions independent; it does not provide transfer, groups or output routing. See [current behavior](CONNECTED_PLAYBACK.md).

## Product contract

One selected playback context drives artwork, metadata, progress and transport in both mini and full player. That context can be this device, another reachable player or a future group. Selecting another player only changes what the user sees and controls; it never starts, pauses or moves music implicitly.

Three actions must remain distinct:

| Action | Effect |
|---|---|
| Select a player | View and control its existing session. Independent queues keep playing as they were. |
| Move this song… | Prepare another player, start the same recording, then pause the source after observed success. |
| Play together… | Explicitly select members of a coordinated same-song session. |

Owner clarification (2026-10-29): future Move carries the current song, position and remaining playback queue; Play together uses one shared queue for coordinated song changes. Preserve duplicate occurrences and the actual shuffle traversal. Saved playlists remain separate account-library records synced through Firebase; transferring a queue must not create or overwrite a saved playlist. Device-local recordings require explicit portability handling, without silently dropping unavailable entries.

First launch selects this device. Preserve an explicitly selected target through reconnects; never silently redirect commands to the local player. Browsing Play must state its destination. Until portable remote play-track is supported, label it **Play on this device**.

## Replacement layout

### Windows

Remove the large repeated device cards when the replacement is integrated. Use a persistent bottom player: artwork/title on the left, transport and progress in the center, selected-device chip, volume and Queue on the right. A queue panel opens only on request and only for a target supporting it.

The device chip opens a compact anchored **Your players** popover. Its label follows confirmed state: **Playing on**, **Paused on**, **Controlling** or **Reconnecting to**. Active devices appear as short rows with a friendly name, platform icon and Playing/Paused/Ready status. The selected target appears first, followed by this device and other available players. Do not reserve a full-height sidebar for registrations.

Artwork and song title have priority over device controls. The desktop bar uses a compact device icon/short name (for example **Laptop**); the picker carries the full device name and playback status. Output selection belongs inside that picker, without a separate Output control competing for track space. The compact trigger retains the full state/name in its accessible label and tooltip.

At narrow widths, give artwork/title their own full-width row with a two-line title and place transport plus the compact device control below it. Move volume into the picker. Preserve transport and device selection without horizontal scrolling or covering the artwork/title.

### Android

The existing artwork-led full player and mini player render the selected target, even if Android has no local queue. A quiet **Playing on My laptop** status line accompanies the three circular connected actions below transport. Their choices expand inline. The mini player has a compact Devices entry, and the main app bar provides an entry when there is no queue. Remote controls stay in the player instead of navigating to Profile.

Keep local Smart shuffle, queue, sleep timer, downloads, likes and playlist actions. Remote actions appear only when the selected engine advertises support. Likes remain account-library actions; **Download on this phone** must not imply downloading on the remote device. Remote UI metadata must not pretend that the phone's ExoPlayer or media notification is playing audio locally.

### Offline and diagnostic devices

Remembered offline devices belong under collapsed **Other devices**, with last seen and management actions, without repeated disabled transport buttons. Idle online players use **Ready to play**, not an empty large artwork card.

Do not infer test devices from name substrings or delete records to hide the layout problem. Future fixtures need explicit diagnostic metadata/expiry. Legacy unclassified offline records stay collapsed and can be explicitly forgotten.

### Interaction and accessibility

Selecting a row closes the picker and updates the player. Move and Play together expand destination/membership choices like the existing Other devices accordion: the device list remains visible and each choice expands below its own trigger. Do not replace the entire dropdown with a different view or open a modal. Cancel collapses the choices. Opening a remote idle player does not replace its queue.

On Android, put the connected actions together below transport: a quiet selected-device status line and three circular, labeled buttons, **Devices**, **Move**, **Together**. Their choices expand inline in that area. Keep artwork/title clear. The mini player keeps its compact Devices button, which reveals the same action area instead of squeezing three extra buttons beside the song title.

Use native Compose sheets/popovers, visible keyboard focus, Back/Escape dismissal with focus return, text scaling and at least 48dp touch targets. Labels include their target, for example **Pause on My laptop**. State must be understandable without color.

If a selected player disappears, freeze its last confirmed state and show **Reconnecting**; stop extrapolating progress and disable commands. Offer **Choose another player**. Never silently switch to local controls.

## Shared engine and transport contract

Each native engine owns actual playback. A shared selected-player presentation maps confirmed snapshots and capabilities onto the existing player UI. Do not duplicate audio engines merely to display a remote player.

Keep existing Firebase Firestore account-library sync. RTDB carries sanitized playback metadata, commands and acknowledgments, not audio, local paths, credentials or temporary resolved streams. Each participating device resolves a stable recording independently. Laptop-only files require a separate future transfer/relay design.

| State | Required distinction |
|---|---|
| Device registration | Durable installation identity and user label; separate from live reachability. |
| Player session | Fresh runtime ID for one actual engine; invalidates commands after restart. |
| Recording and occurrence | Stable recording identity plus distinct queue occurrence IDs; duplicates remain distinct. |
| Queue and timeline | Queue/traversal revision separate from transport revision and ordered snapshot sequence. |
| Output endpoint | Scoped local speaker/headset identity; not another Echo installation. |
| Capability | Actual session support for seek, gain, queue, portable tracks, preparation, scheduling and routing. |

Validate mutations on each engine's serialized dispatcher. Seek guards the current occurrence/timeline; queue edits also guard queue revision. Gain should not fail merely because the queue changed. Display requested/pending separately from confirmed outcome.

Optional display metadata/capability fields may extend v1 with rules tests. Transfer, groups and enforceable device identity need a separate versioned subtree because permissions and state semantics change, not merely to add artwork. During migration, deduplicate explicitly bound v1/v2 registrations. Legacy controls must not concurrently mutate an engine participating in v2 transfer/groups.

## Availability and background lifecycle

The current Android connection belongs to the Activity. Move active-playback participation into the existing Media3 service before claiming background control. Inject a small session-access contract implemented in app into playback through core/data/Hilt; do not introduce a playback-to-app dependency. The UI observes shared state; exactly one runtime publishes each engine.

An idle, closed or force-stopped phone is not guaranteed remotely wakeable. Do not keep an indefinite media foreground service alive solely to advertise presence.

Portable RTDB REST streaming is the preferred next transport investigation using existing OkHttp. Keep leases, bounded payloads, token renewal, validated redirects, reconnect backoff and expired-command rejection. Streaming events are not proof of instantaneous reachability or synchronized audio timing.

## Transfer state machine

1. Capture the source occurrence, playback intent, timeline and actual upcoming traversal, preserving duplicate occurrences.
2. Validate target capability and recording portability. Replacing independent target playback requires an explicit choice.
3. Prepare the exact recording silently without destroying the target's current queue. Allocation/buffering failure leaves both players unchanged.
4. Abort if source/target sessions, occurrence, revisions or operation deadline change.
5. Commit the current source position and captured intent. For playing intent, target reports **observed started**, not merely accepted. For paused intent, target confirms prepared/selected at the requested position while remaining paused.
6. For playing intent, pause the still-matching source. For paused intent, both remain paused. Report complete only when both outcomes are known.

Preparation failure keeps source audio playing. Lost acknowledgments require reconciliation of both engine states before retry. A failed source pause after target start is a partial result, not success. Preserve displaced queues/cursors for recovery, paused. This prioritizes continuity and can produce a short overlap; an atomic exactly-one-audible guarantee across network partitions is unavailable.

## Play together and audio outputs

Use one fenced coordinator/epoch and one explicit group queue/timeline. Followers must not independently auto-advance, repeat or generate Smart shuffle. Prebuffer the identical recording, measure clock offset/uncertainty using multiple round trips, schedule against monotonic clocks and correct bounded drift only where the engine supports it. RTDB heartbeat timestamps are not an audible synchronization clock.

Show per-member Buffering/Reconnecting states while ready members continue. Rejoining requires readiness/timeline checks. Coordinator lease loss fences further group mutations and advancement until a new epoch is safely established. Joining preserves the independent queue/cursor; leaving restores it paused.

Creating a group from a paused source prepares it paused until explicit Play. Group volume attenuates member levels: effective gain = group level × member trim. Joining and output changes preserve mute and cap initial effective gain at the member's previous level; a default trim must not raise volume unexpectedly. Sliders appear only after acknowledged engine support. Windows LibVLC player gain is not Windows system volume. Output selection requires real enumeration, selection and hot-unplug verification. Do not silently fall back from explicitly selected headphones to speakers.

Same song on multiple devices, same song on several outputs, and different songs on separate outputs are distinct requested behaviors. Keep player sessions separate from output endpoints so these modes can be added without confusing membership. Independent Windows songs require separate engines; multi-output fan-out and tight alignment need a renderer spike and actual latency measurements.

N means an extensible model with a tested capacity, not unlimited simultaneous audio. Start with two devices, then establish a measured group ceiling. Replace the current 32-entry query window with bounded pagination/discovery and explicit backpressure before claiming larger-scale support. Durable registrations and expired session/command cleanup have separate retention policies.

## Trust boundary

Current owner-UID rules isolate accounts but cannot cryptographically distinguish installations sharing that UID. Local opt-in is not enforceable per-device revocation. Preserve that honest limitation for the initial UI change.

Before offering trusted enrollment/revoke or coordinator authority as security guarantees, design a small trusted backend issuing device-scoped identities, registration generations and bounded one-use enrollment challenges. Rules restrict snapshots/acks to their owning device and commands to allowed senders. Keep owner/library auth separate and Admin credentials off clients. This backend is a later design dependency, not required merely to replace the cards.

## Delivery and acceptance gates

1. **Reviewable design:** completed and approved; see the product contract and replacement layout above.
2. **Unified remote player:** implemented and published in 0.2.2 with supported commands. Transfer/group flows remain separate; gain and output routing are unavailable.
3. **Lifecycle/live transport:** service-owned Android participation, bounded streaming/reconnect and opt-out/account-change handling. Verify emulator/native Windows, preserving queues and credentials.
4. **Transfer:** exact recording, readiness, target-busy/source-change races, lost acknowledgments, process death and rollback. Deploy changed rules/backend only with their tests and required owner setup.
5. **Groups:** coordinator fencing, prebuffer/start/seek/next, leave/rejoin and preserved queues. Audible alignment requires actual hardware measurements before a synchronized claim.
6. **Scale/outputs:** pagination beyond 32 registrations, N-member soak/resource tests, endpoint selection/unplug and independent-output audio tests.

Sol owns architecture/design/review; Luna writes implementation. Android testing remains emulator-only. No library reset, credential replacement, physical-phone access or existing asset replacement was part of this release.

This document separates the approved architecture from implementation status. The selected-player UI milestone is published in 0.2.2. It does not establish transfer, group, background or acoustic synchronization support.
