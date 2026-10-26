# Echo Music working guidance

## Current focus

- Queue append/search-selected/new-shuffle operations now use app-UID-only service commands with bounded stable IDs, stale selected-occurrence checks and explicit shuffle traversal. Preserve existing upcoming order, duplicates, repeat-all wrap and current playback. Fresh shuffle starts randomly with all occurrences reachable; completed/idle Smart shuffle sessions must not retain a stale anchor. Read docs/QUEUE_ACTIONS.md. Sleep timers belong to the service, use a monotonic deadline, pause without clearing and cancel on service destruction; read docs/SLEEP_TIMER.md.

- 0.1.7/code8 adds player/queue playlist actions, improved recognized artwork requests, local listening events/preferences, private offline downloads and separate opt-in frequent-song caching. Preserve the non-destructive Room v1→v2 migration, actual listening-time semantics, storage/integrity/cancellation safeguards and emulator-only queue fixtures. Read docs/OFFLINE.md and docs/HISTORY_NOTES.md before changing these paths.
- Google sign-in/out and private Drive playlist sync are implemented. The GPL-compatible build uses Android AccountManager and the installed Google account provider, verified Google userinfo and separate Drive consent. No proprietary identity SDK is bundled; the earlier Web ID is only a console reference. Live OAuth/Drive verification remains pending. Do not claim live login or cross-device transfers. Sync requires explicit consent, excludes audio/credentials/likes/history/local paths, qualifies local track IDs and preserves concurrent edits as conflict copies. Local libraries remain device-wide after sign-out. Read docs/ACCOUNTS.md; unseen stale snapshots can restore deletions without global tombstones.

- 0.1.6 work: compact bottom navigation with no top-level stack restoration, an immersive player and playback-ordered queue sheet. Queue row keys must be Android-saveable occurrence indexes; Media3 window UIDs stay internal for stale-action guards. Smart shuffle uses related songs from an actual recording, not random artist-search results. Auto-match clear imported recordings by title/credited artist/duration; commit guarded playlist replacements atomically, preserving duplicates and likes. Ambiguities keep manual selection.

- Spotify public share-link import uses only canonical `open.spotify.com` public embed/page HTML, never auth cookies/internal tokens. Preview exposed versus total tracks before committing; unknown or partial counts must be explicit. File import remains the fallback. No automatic sync or Spotify audio is provided.

- Home now prioritizes discovery song previews and live mix cards, then the user's playlists, liked-song/search shortcuts and recent/library picks. Preserve section-specific keys and keep automatic discovery loading separate from global action busy state so import/playback stay available.

- The user has no Spotify Premium and wants subscription-free Android listening. Current focus: local Spotify playlist-file import (Exportify CSV / Spotify account-data playlist JSON), Echo discovery, session-only smart shuffle and live new-music searches. Do not restart Premium-dependent Spotify onboarding or promise automatic Spotify editorial/personalized playlist sync. Preserve existing encrypted credentials and PKCE code; see docs/INTEGRATIONS.md.
- The user resumed laptop scope on 2026-10-27. A separate `desktop` Compose JVM preview prioritizes real Windows playback and desktop navigation; reuse core/provider sources without disturbing Android data. Read docs/DESKTOP.md and docs/DESKTOP_SYNC_DESIGN.md. Full account/history/favorites sync and connected/multi-output audio are requested next, not implemented by the local playback preview.
- The user returned to YouTube scope on 2026-10-25 and authorized a key-free, Spotube-style approach. NewPipeExtractor song search and native lazy playback are implemented and emulator-tested; preserve stable video IDs, bounded retries and existing library data. See CHANGELOG.md and docs/INTEGRATION_STATUS.md. Spotify onboarding retains its separate account-authorization requirements.
- Keep progress updates concise; distinguish implementation, executed checks and external blockers.

## Project references

Four modules: `core`, `data`, `playback`, `app`. Read `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/AGENT_TASKS.md` and `docs/INTEGRATION_STATUS.md` before changing shared contracts. Keep changes small and test non-trivial logic.

## Background downloads, Profile and arranging the queue

- Manual song downloads and app-update downloads use separate non-exported dataSync foreground services. Keep progress/cancel notifications, real byte totals, bounded wake locks and Android timeout handling. No force-stop/reboot/range-resume or background installer promise. Automatic frequent-song caching retains its existing app/playback-process rules.
- Profile is a fourth bottom tab. Account-specific device counts are registrations verified at the last successful Drive sync, not online presence. Keep sign-out non-destructive and consent above navigation.
- Queue drag handles reorder the real service traversal, preserve duplicate occurrences/current playback, and offer accessibility Move up/down. Validate captured UI UIDs and current ordered IDs before mutation. Repeat-all physical wrap and shuffle permutations need regression checks.

## In-app updates

- Owner approved GPL-3.0-or-later and public release. v0.1.8/code9 is published with the same-key APK, matching source/dependency bundle and packaged notices; no proprietary Google identity SDK is bundled. Real code8 public download/checksum/signature/staging and installer-handoff passed, and the code8 UI displayed Update ready 0.1.8. Android installer acceptance, phone updates and live Google consent remain unverified. Preserve the validated distribution and update path; see docs/RELEASE_READINESS.md.

- Implemented in `app/src/main/java/dev/echo/app/update/`; release contract and setup are in `docs/UPDATES.md`.
- Uses public GitHub Releases at `addy1243/Echo`: automatic startup check at most daily and manual check in Settings.
- Assets must be `EchoMusic-<versionCode>.apk`, with a newer Android version code and the installed signing certificate.
- Preserve HTTPS/repository/host validation, exact size/SHA-256 checks, APK identity/signature checks, private staging, cancellation and rate-limit handling.
- Android's installation permission and user confirmation remain required. Never silently install, bypass protections, or clear app data to force an update.
- Android source defaults: 0.1.9/code10 for queue rearrangement, Profile and background manual downloads; 0.1.8/code9 is the prior public release; accounts/offline/history were 0.1.7/code8; navigation/player/queue/matching were 0.1.6/code7 and public links were 0.1.5/code6. Recheck source defaults and installed version before changing versioning. Never publish signing keys/API secrets or publish a release without user authorization.
- Updater build/lint/JVM and Android checks pass, including the real newer code9 candidate and public GitHub download. Normal Android installation permission and confirmation are still required; actual system-installer completion was not automated.

## Connected Android device

- Latest installation: 0.1.6/code7 was installed in place after the user reconnected and approved Android's USB debugging computer-trust prompt. Package metadata confirms the version. All queue/library fixture tests ran on the emulator; no phone data reset or manual phone playback check was performed.

- On 2026-10-25 the user reconnected the physical Android16 phone and renewed authorization for Echo development/testing. ADB was freshly verified as `device`; the updater was installed in place and focused tests passed.
- This supersedes the earlier emulator-only instruction for authorized Echo testing. Device availability is temporary: check `adb devices -l` each session and use an explicit `adb -s <serial>` target.
- Preserve the library, preferences and credentials. Use in-place same-key installs; do not uninstall `dev.echo.music`, clear its data, or alter unrelated phone apps/settings without explicit authorization.
- Run queue-replacing/library-fixture tests on a dedicated emulator unless their effects on the phone are explicitly authorized. Updater-only parser/signature/FileProvider/UI checks do not require library mutation.
- Keep the screen unlocked for UI checks. Let the user handle passwords, device unlock and normal system approval prompts. Stop phone access if permission is revoked or the user returns to emulator-only testing.
- Read only the Echo-related logs/UI needed for the task; avoid unrelated personal content.

## Build and verification

Use the installed Android Studio JBR, SDK36 and Gradle8.14, with project-local `GRADLE_USER_HOME=.gradle-user` to avoid observed Windows shared-cache failures. Gradle/ADB may need approved access to SDK caches and local daemon sockets.

Run targeted tests and build/lint for code changes. Distinguish actual device/API results from documentation and test fixtures. Verify Spotify app registration, authorization and access before claiming one-tap connection works.
