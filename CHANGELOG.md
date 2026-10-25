# Release history

This file records development changes. A development build or an entry here does not mean an APK was published to GitHub Releases.

## Unreleased — 2026-10-25

Current Android source defaults: **0.1.4 / versionCode 5**. Assign a newer version code before publishing an update to an installation already running code 5.

### Added

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
