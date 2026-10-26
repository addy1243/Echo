# Release history

This file records development changes. A development build or an entry here does not mean an APK was published to GitHub Releases.

## 0.1.9 / versionCode 10 — published 2026-10-27

- Drag upcoming queue rows to arrange real playback order, including shuffled and repeat-all queues. Current playback and duplicate occurrences are preserved; stale drags are rejected. Move up/down accessibility actions are available.
- User-started app updates and manual song downloads continue in Android foreground services with progress/cancellation notifications and bounded screen-off wake locks. Song downloads show actual received bytes and percentages when totals are known. Notification taps open Downloads. Completion offers installation only through user action.
- Added a Profile bottom tab with Google account/Drive playlist sync controls, local playlist count and account-specific device registrations checked during the last successful sync. Device registrations do not imply online presence.
- Interrupted downloads retry from the beginning; force-stop/reboot survival, automatic cache background scheduling and remote device management are not added.

## 0.1.8 / versionCode 9 — published 2026-10-26

- Published [v0.1.8](https://github.com/addy1243/Echo/releases/tag/v0.1.8) with same-key `EchoMusic-9.apk` and exact corresponding source at commit2bb31c6; preserved code8 as the emulator upgrade baseline. The real public download passed checksum, identity and installer-handoff checks without silent installation.
- Verified GitHub repository write access, APK identity/version/signing certificate, and build/unit/lint checks. Added an opt-in Android check of the actual newer candidate against the installed app.
- Owner approved GPL-3.0-or-later. Replaced bundled proprietary Google identity SDKs with Android account selection/token authorization and verified Google userinfo, retained separate Drive consent, added packaged runtime notices/source access and matching dependency-source packaging. Live owner Google authorization remains unverified; see [release readiness](docs/RELEASE_READINESS.md).

## 0.1.7 development work included in 0.1.8 — 2026-10-26

- Playlist Add to queue offers playlist order, Shuffle and Smart shuffle while preserving current playback and prior upcoming songs. Search taps play only the selected song before continuing the existing queue, including actual shuffled traversal. New Shuffle sessions choose a random playable occurrence and retain every occurrence in repeat-off mode; Smart shuffle randomizes fresh-session anchors while retaining active playback when applicable.
- Service-owned sleep timer with 15/30/45/60-minute choices, visible countdown, replacement and cancellation. Expiry pauses without clearing queue/position, including while the screen is off; service destruction clears the timer. See [queue behavior](docs/QUEUE_ACTIONS.md) and [timer semantics](docs/SLEEP_TIMER.md).

- Add-to-playlist controls in the full player and playback queue, including session recommendations, with a shared picker/new-playlist action.
- Higher-resolution recognized Google artwork variants for large artwork, with original fallback; quality remains source-dependent.
- Private pinned offline downloads, cancel/retry/remove/keep controls, a Downloads screen and offline playback mode. Separate opt-in frequent-song cache follows qualified listening evidence and unmetered connectivity. Per-song/download/cache/free-space limits and complete-file integrity checks apply. Transfers require the app process; interrupted downloads restart via Retry.
- Durable start/skip/complete/interruption/error logs, listening-time accounting, history display/confirmed clearing and conservative preference nudges within related-song suggestions. Explicit non-destructive Room v1→v2 migration preserves existing library data.
- Optional Google account sign-in/out and private Drive playlist sync, manually or periodically while foreground. Concurrent edits become conflict copies; local file identities are device-qualified and cloud snapshots exclude audio, credentials, likes and listening logs. Local data remains on sign-out.
- The owner-supplied public Web client ID is configured and enables Google sign-in; live sign-in and two-device sync are not validated. See [account setup](docs/ACCOUNTS.md), [offline design](docs/OFFLINE.md), [history](docs/HISTORY_NOTES.md) and [executed validation](docs/INTEGRATION_STATUS.md).
- No GitHub release published and no physical phone data reset or installation performed by this change.

## Unreleased — 2026-10-25

Historical Android source defaults for the changes below: **0.1.6 / versionCode 7**. Current 0.1.7 defaults are recorded above.

### Added

- Full-screen artwork-first player, compact bottom navigation and a Now playing/Up next queue sheet following real shuffle order, with current-song highlighting and clear-upcoming controls.
- Automatic matching of clear imported recordings, progress/cancellation, duplicate/like preservation and an atomic guard against overwriting intervening playlist edits. Link import enables matching by default; existing imports have Auto-match recordings.

- Paste a public Spotify playlist share link, preview exposed/total song counts, then import metadata as a new playlist. Partial/unknown counts are explicit; private or changed pages can use file import. No Spotify login or audio preview extraction.

- Subscription-free Spotify playlist-file import: Exportify CSV and Spotify account-data playlist JSON, preserved order/duplicates and atomic additive imports.
- Explicit YouTube recording selection for imported song occurrences, with stale-position protection.
- Artist-based Discovery mix with save-to-Library, current-month New music searches and queue-only Echo smart shuffle.
- Local import validation and tests for malformed CSV/JSON, rollback, repeated imports, duplicate entries and discovery interleaving.

- Key-free YouTube Music song search and native audio playback through NewPipeExtractor v0.26.5.
- Stable video identities in the library, playlists, likes, backups and saved queues; fresh audio URLs resolve when playback loads.
- Expiring-source handling, one bounded refresh for media HTTP403, and visible failures without automatic extraction/rate-limit retry loops.
- Provider/downloader, source-preference migration, playback-cache and stale-search regression tests.
- Extractor update instructions, Android shrinker/desugaring configuration, and bundled dependency license/notices.
- Spotify onboarding work: fixed mobile callback and build-time public Client ID configuration. Live account authorization remains a separate verification requirement.

### Changed

- Home uses a compact header, swipeable discovery song previews, two live mix entry cards, saved playlist cards and useful listening shortcuts before recent/library rows. New music opens its own tab directly. Existing library songs are clearly labeled when used as discovery-loading fallbacks; background discovery no longer blocks import or other actions.

- Normal Settings now points to file import instead of Premium-dependent Spotify login. Existing credentials can still be disconnected; Spotify automatic playlist sync is not promised.

- Discover/Search defaults to YouTube. Results use Echo's normal player, queue and library controls.
- Replaced the YouTube Data API link client and API-key setup UI. Previously stored encrypted keys are preserved but unused by search/playback.

### Fixed

- Home no longer restores a Library navigation stack when switching tabs. Playlist reloads cancel stale jobs without blanking an already loaded playlist.
- Queue display uses actual playback order and occurrence identities instead of raw storage order. Queue rows use Android-saveable keys.
- Smart shuffle uses ranked related songs from a recording rather than broad random artist searches; it preserves the current anchor and position and stops if playback changes during lookup.

- Playlist Play/Shuffle can start at the first playable entry when earlier imported entries have no source.
- New online searches clear and label results; canceled requests cannot replace newer results.

### Verification and limits

- Debug/test APKs, release shrinking/unsigned release build, 46 JVM tests and debug lint passed during the YouTube implementation. Lint reported zero errors, seven warnings and three hints.
- API36 emulator: live key-free search and real audio playback passed, including pause/resume, seek, next track and saved-queue restoration after service recreation.
- Navigation, Home history, local WAV controls, notification/screen-off playback and browser checks passed. The local service-recreation check required an isolated run because a preceding UI test retained a controller.
- Runtime checks used the debug build. Phone/car behavior and minified-release playback were not checked in this task. Individual recordings or future YouTube changes can still fail.
- Mixed queues containing deleted local files and Commons API-error parsing remain known follow-up work. Spotify imports do not automatically match YouTube recordings.
- No GitHub release was published by this YouTube implementation. See [integration evidence](docs/INTEGRATION_STATUS.md) and [publishing instructions](docs/UPDATES.md).

## 0.1.1 / versionCode 2 — earlier updater preview, 2026-10-25

- Added daily/manual GitHub update checks, release prompt, progress/cancellation and Android installer handoff.
- Added APK size, SHA-256, package/version/SDK and signing-certificate checks with private staging and pending-update recovery.
- Recorded passing build/lint, four updater unit tests and four focused Android updater checks. End-to-end replacement through a published newer GitHub asset remained unverified in that snapshot.

See [the historical status record](docs/INTEGRATION_STATUS.md) for earlier foundation and device validation. This changelog does not infer published releases from local build versions.
