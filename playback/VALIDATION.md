# Playback validation

Implemented service contract: `dev.echo.playback.EchoPlaybackService`, Hilt-injected Room-backed `LibraryRepository` and `SourceResolver`. Phone controllers submit `Track.toMediaItem()` (ID and metadata); the service ignores supplied playback URLs and resolves library IDs again. Accepted clients are the app itself or a Media3 trusted controller (media-control permission or enabled notification listener).

Car tree: root → local audio / playlists / favorites / recent / albums / artists; playlist, album and artist nodes lead to track leaves. Track metadata without an audio source is marked unavailable. Search operates on the durable catalogue; voice playback resolves the first title, artist or album match. Subscribed folders update when library flows change.

ExoPlayer owns playback, audio focus, noisy-output pause, session notification, queue, seeking, repeat and shuffle. Each resolved source gets a separate HTTP data-source factory, so request headers are scoped to that item's requests. HTTPS and platform local URI schemes are accepted. Resolver work runs on IO. Network errors and HTTP 5xx playback errors trigger up to two delayed source re-resolutions, preserving queue/index/position and respecting paused state. HTTP 4xx, including 401/403/429, stop automatic retry in both ExoPlayer loading and service recovery. Controllers receive a session error with the rate-limit/authentication message and Retry-After header when supplied. No audio extractor, Spotify audio stream, DRM bypass or embedded credential exists.

Queue preferences contain IDs, current index/position, repeat and shuffle only. A play request on an empty player triggers Media3 playback resumption and re-resolves saved IDs. Removed/unavailable items are skipped; losing the selected item resets position. Service creation does not autoplay. Queue progress is saved on player events and every five seconds while playing. No signed URL, HTTP header or token is persisted.

## Executed

- `QueueRestoreTest`: **4 tests passed**, compiled with cached Kotlin 2.0.21 and run with JUnit 4.13.2 on the installed Android Studio JBR. Covers duplicate occurrences, removed selected tracks, corrupt index/negative position, empty queues and pagination overflow.
- Checked callback signatures against the official [MediaSession 1.6.1 source](https://github.com/androidx/media/blob/1.6.1/libraries/session/src/main/java/androidx/media3/session/MediaSession.java), [MediaLibraryService 1.6.1 source](https://github.com/androidx/media/blob/1.6.1/libraries/session/src/main/java/androidx/media3/session/MediaLibraryService.java), and [MediaSource factory contract](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/MediaSource.java).

## Integrated emulator results

Gradle builds the debug APK and instrumentation APKs; lint has zero errors. The six API36 app instrumentation checks pass: navigation, persisted-history Home regression/activity recreation, real MediaBrowser browse/unknown-ID errors, WAV play/pause/seek/queue/repeat/shuffle, controller reconnection, service recreation with explicit queue resumption, real notification Pause/Play PendingIntents, and advancing audio while the screen is off. Test setup finishes Echo activities before service recreation because an active UI correctly keeps the bound service alive. Four Room/Keystore instrumentation checks pass separately.

`MediaItemsTest` standalone library instrumentation was not run; the real app browser test covers its browsable/playable behavior. Tests replace the playback queue, insert unique temporary tracks and remove only their own fixtures; use a dedicated emulator installation.

The explicit live Commons test reached a licensed catalog result but failed with HTTP429 from the audio host. No native online playback success is claimed. Both retry layers now stop on 4xx; session errors show the rate limit and Retry-After when supplied. Network recovery/5xx and HTTP policy unit tests pass; recovery under a real future signed-URL provider remains unverified.

Run `:playback:testDebugUnitTest`, `:app:assembleDebug`, `:playback:connectedDebugAndroidTest` and `:app:connectedDebugAndroidTest` with the Android SDK and a device/emulator configured. The standalone unit check does not prove the service compiles or Android playback works.

Pending on physical hardware: play/pause/seek/skip from phone and lock screen, unplug headphones, exercise audio focus and OS process death; verify queue position and deleted-file behavior. Test Android Auto with its trusted host/DHU: every folder, voice search, empty folders, unavailable imported metadata and favorites/playlist changes. Real phone scan and startup worked; a Home duplicate-key crash was fixed, but phone playback after the fix has not been verified. User ended phone access; further validation uses the emulator.

Known limits: album identity uses album title + track artist until album-artist metadata exists. Shuffle enablement survives restart, but its randomized traversal order does not. Current built-in providers return non-expiring sources. A future signed-URL provider needs one explicit expiry refresh before a 403 can be retried; current 403 responses are not retried. An unavailable entry rejects a new queue request with a controller error; it is not silently replaced with unrelated audio.
