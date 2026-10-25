# Echo Music working guidance

## Current focus

- Home now prioritizes discovery song previews and live mix cards, then the user's playlists, liked-song/search shortcuts and recent/library picks. Preserve section-specific keys and keep automatic discovery loading separate from global action busy state so import/playback stay available.

- The user has no Spotify Premium and wants subscription-free Android listening. Current focus: local Spotify playlist-file import (Exportify CSV / Spotify account-data playlist JSON), Echo discovery, session-only smart shuffle and live new-music searches. Do not restart Premium-dependent Spotify onboarding or promise automatic Spotify editorial/personalized playlist sync. Preserve existing encrypted credentials and PKCE code; see docs/INTEGRATIONS.md.
- Laptop/desktop support is deferred by the user; finish Android Spotify work first.
- The user returned to YouTube scope on 2026-10-25 and authorized a key-free, Spotube-style approach. NewPipeExtractor song search and native lazy playback are implemented and emulator-tested; preserve stable video IDs, bounded retries and existing library data. See CHANGELOG.md and docs/INTEGRATION_STATUS.md. Spotify onboarding retains its separate account-authorization requirements.
- Keep progress updates concise; distinguish implementation, executed checks and external blockers.

## Project references

Four modules: `core`, `data`, `playback`, `app`. Read `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/AGENT_TASKS.md` and `docs/INTEGRATION_STATUS.md` before changing shared contracts. Keep changes small and test non-trivial logic.

## In-app updates

- Implemented in `app/src/main/java/dev/echo/app/update/`; release contract and setup are in `docs/UPDATES.md`.
- Uses public GitHub Releases at `addy1243/Echo`: automatic startup check at most daily and manual check in Settings.
- Assets must be `EchoMusic-<versionCode>.apk`, with a newer Android version code and the installed signing certificate.
- Preserve HTTPS/repository/host validation, exact size/SHA-256 checks, APK identity/signature checks, private staging, cancellation and rate-limit handling.
- Android's installation permission and user confirmation remain required. Never silently install, bypass protections, or clear app data to force an update.
- Android source defaults: 0.1.4/code5 for the Home redesign; playlist import/discovery was 0.1.3/code4, YouTube was 0.1.2/code3 and updater preview was 0.1.1/code2. Recheck source defaults and installed version before changing versioning. Never publish signing keys/API secrets or publish a release without user authorization.
- Updater build/lint, four new JVM checks and four Android checks passed. Live GitHub APK replacement remains unverified because no newer release is published; recheck that external state.

## Connected Android device

- Latest installation: 0.1.4/code5 was installed in place on the reconnected phone after Home layout/navigation/recreation emulator checks. Android package metadata confirms the version. No phone library/queue fixture tests or manual playback checks were performed for this build; app data was not cleared.

- On 2026-10-25 the user reconnected the physical Android16 phone and renewed authorization for Echo development/testing. ADB was freshly verified as `device`; the updater was installed in place and focused tests passed.
- This supersedes the earlier emulator-only instruction for authorized Echo testing. Device availability is temporary: check `adb devices -l` each session and use an explicit `adb -s <serial>` target.
- Preserve the library, preferences and credentials. Use in-place same-key installs; do not uninstall `dev.echo.music`, clear its data, or alter unrelated phone apps/settings without explicit authorization.
- Run queue-replacing/library-fixture tests on a dedicated emulator unless their effects on the phone are explicitly authorized. Updater-only parser/signature/FileProvider/UI checks do not require library mutation.
- Keep the screen unlocked for UI checks. Let the user handle passwords, device unlock and normal system approval prompts. Stop phone access if permission is revoked or the user returns to emulator-only testing.
- Read only the Echo-related logs/UI needed for the task; avoid unrelated personal content.

## Build and verification

Use the installed Android Studio JBR, SDK36 and Gradle8.14, with project-local `GRADLE_USER_HOME=.gradle-user` to avoid observed Windows shared-cache failures. Gradle/ADB may need approved access to SDK caches and local daemon sockets.

Run targeted tests and build/lint for code changes. Distinguish actual device/API results from documentation and test fixtures. Verify Spotify app registration, authorization and access before claiming one-tap connection works.
