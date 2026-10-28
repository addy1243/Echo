# Android and desktop feature parity

Current documentation review: **2026-10-28**, incorporating executed acceptance from 2026-10-27. Windows uses desktop navigation, wide tables and a persistent player rather than Android navigation. Equivalent listening/library behavior is the target; Android-only integrations are not copied.

Normal Android-emulator Google/Firebase login succeeded. Its saved sync state, desktop and Firestore matched canonically, with automatic sync enabled while open. Live isolated roundtrips covered duplicate occurrences, a desktop edit and deletion convergence. No physical phone was accessed. Desktop has **88 passing JVM tests**, native playback checks, a real GitHub update-feed check and native MSI identity/data-isolation verification. See [executed evidence](DESKTOP.md) and [Firebase limits](FIREBASE.md).

Android **0.2.0 / code11** and Windows **0.2.0** are being prepared for the owner-authorized combined `v0.2.0` release; publication is pending. Windows advances the 0.1.0 preview for update discovery. Immutable [Android 0.1.9](releases/0.1.9.md) remains documented separately. The 88-test desktop result predates the final release fixes; final candidate results belong in the release record.

## Implemented and remaining work

| Feature | Android | Windows | Remaining work / acceptance limit |
|---|---|---|---|
| YouTube search/playback | NewPipe + Media3 lazy resolution | Shared provider + LibVLC validated loopback streaming | Provider availability is not universal; VLC 3 is an external Windows requirement |
| Local audio | MediaStore and selected files | Selected files | Desktop folder scan/metadata reading absent; phone-local paths cannot play on laptop |
| Commons audio/provider settings | Provider/source attribution and source ordering | Commons entries remain metadata-only; fixed local/YouTube routes | Wire validated Commons resolver and credits/settings |
| Play/pause/seek/previous/next/volume | Implemented | Implemented | Hardware/acoustic acceptance remains separate |
| Repeat and ordinary shuffle | Repeat off/all/one and service shuffle traversal | Repeat off/all/one and fresh shuffled queue | Desktop ongoing-session shuffle toggle absent |
| Smart shuffle/preferences | Recording-related results, retained active occurrence and listening preferences | Shared related/matching/ranking helpers; guarded retained occurrence | Keep duplicate/stale/ended-session regressions; genuine related results required |
| Home discovery/New music | Seeded discovery, monthly searches, saved mix | Seeded discovery, monthly searches, save/play/mix | New music is a search, not verified release dates; full new-screen visual acceptance pending |
| Search-selected playback | Replaces current occurrence, preserving upcoming queue | Implemented | Explicit Play all remains a new queue |
| Append/Play next | Normal/shuffled/Smart append and song actions | Normal/shuffled append, Play next and Add to queue | Desktop Smart append absent |
| Queue jump/remove/reorder/clear | Real traversal, drag/accessibility controls and guards | Occurrence/revision guarded row controls | Desktop drag UI absent; queue/position remain session-only after restart |
| Sleep timer | Service-owned monotonic pause timer | Monotonic pause timer, replace/cancel | OS sleep behavior is separate |
| Playlist/favorites/history | Ordered duplicates, edits, likes, actual listened time, clear | Same library operations; confirmed delete/clear and detailed events | Desktop history display is larger than Android's latest500; abrupt termination can lose unfinished listen |
| Spotify public/file imports | Bounded public preview and CSV/account-data JSON | Reuses parsers with preview/confirmation and atomic new playlists | Partial/unknown counts explicit; no Spotify audio or automatic Spotify sync |
| Recording matching | Guarded manual/local and conservative automatic matching | Guarded manual/local and conservative automatic matching | Ambiguity remains manual; cancellation/stale edits preserve the playlist |
| Echo backup import/export | Metadata transfer excluding audio/credentials/history | Additive import with new playlist IDs, duplicate/favorite preservation; safe export | Backup behavior differs from account sync; local sources need reassociation |
| Manual offline audio | Private verified files; foreground download service | Private verified files, progress, Cancel/Retry/Remove | Desktop completion requires Echo open; live remote desktop download/disk-full acceptance not complete |
| Offline-only mode | Blocks remote audio opens | Blocks remote audio opens and discovery; verifies saved files first | Native saved-fixture playback/seek and unsaved-source rejection passed |
| Frequent-song cache | Separate opt-in, eligibility/unmetered checks and budget | Absent | Adapt shared policy and Windows connectivity/cancellation |
| Google/Firebase account sync | Platform AccountManager, encrypted sessions | Browser PKCE, DPAPI sessions/config | Single project echo-85da4; normal emulator/desktop/cloud equality passed; physical phone and interactive revocation/account-switching remain untested |
| Library convergence | Versioned playlists/favorites/history/saved metadata, tombstones, clear revisions | Shared schema/merge and guarded durable apply | Bounded single document; rejects oversize rather than truncating; no audio/path/credential transfer |
| Legacy Drive snapshots | Prior published release's playlist-only sync retained | Legacy helpers retained; primary UI uses Firebase | Existing snapshots preserved; snapshot counts are not live presence |
| App updates | GitHub APK identity/signer validation and Android installer | Daily/manual Windows discovery independent of Android releases; size/hash/MSI identity checks and interactive installer | Windows MSI unsigned; actual Windows install/upgrade/retention not accepted; public release pending |
| App icon/artwork | Echo waveform icon; recognized artwork requests/cache controls | Matching icon in window/EXE/installer/shortcuts; bounded artwork/fallback | Desktop artwork-cache settings absent |
| Licenses/source access | Packaged notices, source/attribution UI | Packaged notices/resources; desktop inventory and GPL Temurin runtime/source prepared for release | Final desktop dependency/source audit and candidate packaging verification are release gates; in-app notices/attribution parity needs review |
| OS integration | Media session, notification, audio focus/noisy handling | Ordinary window/LibVLC | Windows media keys/session and output-change handling absent |
| Android Auto | Implemented browser/service; car-host acceptance separate | Not applicable | Keep Android host testing separate |
| Installer/runtime | Same-key APK | Per-user MSI + portable image, bundled Java, external VLC | Program/data paths isolated in MSI tables; native installer acceptance separate |

## Connected audio remains unimplemented

Firebase library sync does not provide online presence or playback coordination. Live device status/remote commands, same-song phone/laptop synchronization, independent players with shared controls, same-song laptop output groups, separate songs per output, and Echo-only audio relay/mixing remain future work. They need authenticated session/command semantics, endpoint routing, buffering/drift correction and physical hardware measurements. See [DESKTOP_SYNC_DESIGN.md](DESKTOP_SYNC_DESIGN.md).

## Next priorities

1. Physical-phone in-place update and real library convergence, only when the owner authorizes that device; current work is emulator-only.
2. Actual Windows install/upgrade and library/credential retention, followed by authorized public assets/source and update acceptance.
3. Queue restart restoration, ongoing shuffle mode and Smart append; frequent-song caching and Commons/provider settings.
4. Windows media integration and the connected-audio phases above.

Update this matrix from source plus executed checks. A helper/button or successful build alone is not live feature acceptance. Do not present metadata sync as synchronized audio.
