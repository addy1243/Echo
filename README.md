# Echo Music

Native Kotlin Android personal music player. Android 8.0+; Kotlin, Compose Material 3, Room, Hilt, Media3 and DataStore. Open this folder in Android Studio.

## Build

Use JDK 17 or 21, Android SDK 36 and build tools 35.0.0. Set `ANDROID_HOME` to your SDK, or put `sdk.dir=C:/path/to/Android/Sdk` in untracked `local.properties`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :data:testDebugUnitTest :playback:testDebugUnitTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Gradle downloads declared dependencies on the first build. A project-local Gradle cache can be used with `$env:GRADLE_USER_HOME = Join-Path $PWD '.gradle-user'` if the shared Windows cache has locked files.

## First use

1. Open Echo and choose **Search → YouTube** for online songs, or **Home → Discover** for discovery mixes and new-music searches. For device music, choose **Scan music** and grant audio access, or use **Library → Add file** to select individual audio files. Notification access is requested with the local scan on Android 13+.
2. Tap a track to play. The bottom player opens full controls, seek, shuffle, repeat and queue. Playback lives in the foreground media service; leaving the screen does not own or stop the player.
3. Create playlists in Library. Track options add occurrences, likes, queue entries and play-next. Inside playlists use arrows to reorder and the remove control to remove one occurrence. Metadata and order are stored in Room.
4. Search **Licensed audio** for recordings on Wikimedia Commons. Credits/license/source links are accessible from each recording and Now Playing. Only supported explicit open licenses are accepted.
5. Search **YouTube** to find songs without a Data API key and play them in Echo. Saved tracks keep stable video links; stream URLs resolve when playback loads. YouTube access may change or fail and require an extractor update. **Library → Import Spotify playlist file** accepts an individual Exportify CSV or Spotify account-data Playlist JSON. Import copies song metadata in order; choose **Find recording** in the playlist for YouTube playback, or **Match local file**. See [formats and limits](docs/INTEGRATIONS.md#spotify-playlist-files-and-echo-discovery).
6. Settings exports/imports metadata-only JSON backups through Android's document picker. Backups contain personal listening metadata and local URIs: store them privately. Merge replaces playlists with matching IDs and retains unrelated playlists; audio files and credentials are excluded. Local URIs may need rematching on another device.
7. **Discover** builds a basic mix using artist searches from your likes/library and can save it as a dated playlist. **New music** searches this month's releases on YouTube; it can include older recordings. **Smart shuffle · Echo** adds discoveries to a shuffled playback queue without changing the playlist. These are Echo features, not Spotify's recommendation engine. Refresh searches to find new uploads without updating the app.

Home shows up to five discovery song previews and entry cards for Echo discovery/New music, followed by up to six saved playlists, Play liked, Search, Scan, Resume queue and recent/library picks. Discovery loads automatically without blocking those controls. When it is unavailable, saved playable songs can fill the preview with an explicit collection label; no fabricated recommendations are shown.

## Modules

- `core`: domain models and provider interfaces.
- `data`: Room library, MediaStore, settings, Commons, Spotify imports and key-free YouTube extraction.
- `playback`: independent Media3 service, queue recovery and Android Auto browsing/search.
- `app`: Hilt application and functional Compose screens.

See [architecture](docs/ARCHITECTURE.md), [decisions](docs/DECISIONS.md), [integration status](docs/INTEGRATION_STATUS.md), and [car/device validation](playback/VALIDATION.md).

## Tests

```powershell
.\gradlew.bat :data:testDebugUnitTest :playback:testDebugUnitTest
.\gradlew.bat :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
.\gradlew.bat :app:lintDebug
```

The live online playback test is skipped by default. To explicitly test a real licensed recording over the network:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.echo.app.LiveCommonsPlaybackTest" "-Pandroid.testInstrumentationRunnerArguments.runLiveCommons=true"
```

Unit tests cover matching, licensing, OAuth validation, queue restoration and playlist rules. Room instrumentation tests check import rollback, duplicate positions, favorites, durable corrections and backup round trips. App instrumentation checks navigation plus a real MediaBrowser/MediaController and generated local WAV audio. Run service tests on an emulator dedicated to this project: they replace the playback queue and remove only their fixture tracks. Emulator test success does not establish phone lock-screen/Bluetooth/physical car behavior.

The opt-in YouTube test checks the Blender Foundation's public Big Buck Bunny recording without an API key. Always target the emulator explicitly:

```powershell
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.echo.app.LiveYouTubePlaybackTest" "-Pandroid.testInstrumentationRunnerArguments.runLiveYouTube=true"
```

See [YouTube maintenance and dependency licensing](docs/INTEGRATIONS.md#key-free-youtube-playback) before distributing extractor-based builds.

## Release

Development changes and validation limits are recorded in [the release history](CHANGELOG.md). Entries marked Unreleased are not published APK releases.

In-app updates use [GitHub Releases](https://github.com/addy1243/Echo/releases). See [publishing/update instructions](docs/UPDATES.md): upload a newer same-key APK named `EchoMusic-<versionCode>.apk`. Settings includes Check for updates; startup checks once daily. Android requires permission and confirmation to install.

Use Android Studio **Build → Generate Signed App Bundle / APK**, create/select your private signing key outside the repository and select release. `gradlew :app:assembleRelease` creates an unsigned APK until signing is configured. Never commit keystores, tokens or client secrets. Preserve your signing key to install future upgrades without erasing the library. No publication or paid service is configured.

The initial database is version 1 with exported Room schemas. Future schema changes must increment the version and provide tested migrations; destructive migration fallback is absent.

## Current limits

This is an initial implementation pending the checks recorded in integration status, not a production certification. There are no app-introduced ads, podcasts, DRM bypass or stream downloads. Key-free YouTube uses unofficial extraction and cannot guarantee every song, region or future availability. Playback selects original progressive audio at normal speed; there is no transcoding/quality selector. Discovery is basic artist-search matching, and New music is not a verified release-date feed. Cached deleted local files remain visible and can prevent mixed queues from loading. Artwork uses Coil; individual document files without artwork use a generated visual. Android Auto requires host/device validation and searches only saved tracks. Spotify file imports are snapshots with no automatic Spotify synchronization. The bundled Spotify Client ID is public; no client secret or account credentials are included.
