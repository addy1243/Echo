# Echo for Windows

Windows **0.2.4** and Android **0.2.4/code15** are release candidates; publication and artifact checks are pending in [the release record](releases/0.2.4.md). They add public YouTube playlist-title search and public playlist-link preview/import. The previous stable release is [0.2.3](https://github.com/addy1243/Echo/releases/tag/v0.2.3), which added Move and Play together. Earlier release tags/assets remain immutable.

## Run or install

Current laptop executable:

```text
C:\Users\adars\OneDrive\Documents\Autos\Echo\desktop\build\compose\binaries\main\app\EchoMusic\EchoMusic.exe
```

Keep the whole `EchoMusic` directory together, including `app` and `runtime`; Java is bundled. Release installer: `build/releases/0.2.4/EchoMusic-Windows-x64-0.2.4.msi`; checksums and matching source accompany the release. The earlier `build/distributions` 0.1.0 installer is a development preview, not the published release.

**64-bit VLC 3 is required separately.** Echo uses LibVLC without opening VLC's window. VLC is not bundled or installed silently. The MSI is unsigned. The existing Start-menu installation upgraded to0.2.3 successfully; library and DPAPI credential hashes were unchanged, and the owner confirmed the running version after restarting Echo.

The per-user MSI installs program files under `%LOCALAPPDATA%\EchoMusicDesktop`, separate from `%LOCALAPPDATA%\EchoMusic` data. The portable development EXE and installed copy are separate; use the installed shortcut after installing the MSI for later MSI upgrades.

## Available

- Desktop sidebar, wide song table, artwork, persistent transport and Now playing/Up next; maximized window. The EXE, window, installer and shortcuts reuse Android's Echo waveform/colors.
- Local/YouTube playback and YouTube Music song or public playlist-title search; play/pause, seek, previous/next, volume, repeat, ordinary/recording-related Smart shuffle, Play next, normal/shuffled append, guarded queue editing and monotonic sleep timer.
- Playlists with rename/delete and occurrence move/remove, favorites, actual listening-time history, confirmed history clear and metadata backup import/export. Duplicate occurrences survive. Abrupt termination can lose unfinished listening duration.
- Public YouTube playlist-title search and link preview/import, plus Spotify public-link and CSV/account-data JSON import, with explicit partial/unknown totals. Guarded manual/local recording association and conservative automatic matching are available. Spotify imports contain no audio and do not sync automatically.
- Discovery from actual library/favorite/recent artists, monthly music searches, save-to-playlist and save mix. Search results are not verified release dates.
- Private manual downloads with integrity checks, progress, Cancel/Retry/Remove and offline-only playback. Transfers run while Echo is open; Retry restarts interruptions. Frequent-song automatic caching is absent.
- Optional Google/Firebase library sync and daily/manual Windows update checks with verified MSI staging and explicit interactive installation.

## Connected playback

Published0.2.3 adds functional Move and Play together to the unified player. Actual Windows/Android native playback, queue preservation, shared transport and failure recovery passed against the local demo and production Firebase backend. Saved playlists keep their existing Firestore sync. See [setup, limits and executed checks](CONNECTED_PLAYBACK.md).

## Accounts and sync

Both clients use **echo-85da4**. After separate consent, Firebase shares playlists, saved-track metadata, favorites and bounded history, including deletion/unlike markers and account-wide history clearing. Audio, credentials, local paths and temporary streams are excluded. Sign-out retains the device-wide local library.

Normal Android-emulator Google/Firebase sign-in succeeded. Its saved library, desktop and Firestore matched canonically; automatic sync is enabled on both while open, polling approximately once per minute. The physical phone was not accessed or updated. See [account setup](DESKTOP_ACCOUNTS.md), [Firebase evidence/limits](FIREBASE.md) and [parity](DESKTOP_PARITY.md).

## Updates and storage

**App updates** checks Windows releases daily at startup or manually. Android-only releases do not hide Windows releases. Downloads validate hosts, exact size, SHA-256 and native MSI identity, support progress/cancellation and restore verified staging after restart. Install opens normal Windows Installer; it does not accept prompts or silently replace a running app. See [Windows update contract](DESKTOP_UPDATES.md).

Library: `%LOCALAPPDATA%\EchoMusic\library.json`; downloads: `%LOCALAPPDATA%\EchoMusic\offline-audio`; DPAPI credentials/configuration: `%LOCALAPPDATA%\EchoMusic\google-account.dpapi`. Development override: `-Decho.desktop.library=<path>`. Do not copy protected credentials to another Windows user.

## Build and verification

Use Android Studio JBR, SDK36 and `GRADLE_USER_HOME=.gradle-user` for Gradle/Android. Public Windows packaging uses the full GPL-licensed **Eclipse Temurin JDK 21.0.12.1+1**, including its matching complete source in the corresponding-source bundle. Point `desktopJavaHome` to that extracted JDK; the earlier Oracle JDK24 preview packaging is not the public-runtime plan.

```powershell
.\gradlew.bat :desktop:test :desktop:createDistributable :desktop:packageMsi '-PdesktopJavaHome=C:\path\to\temurin-jdk-21'
.\gradlew.bat :desktop:msiCheck
# Quiet-volume native playback; live YouTube is separate opt-in.
.\gradlew.bat :desktop:playbackCheck
.\gradlew.bat :desktop:playbackCheck -PechoLiveYouTube=true
```

Executed on 2026-10-27:

- **88 desktop JVM tests passed before the final release fixes**, EXE/MSI preview packaging passed, and production native MSI product/version/UpgradeCode/x64 inspection passed. Directory/uninstall-table review confirmed program/data separation. Preview identity log: `build/desktop-icon-msi-final-check.log`. The parent release record will state the final candidate's test counts and artifact hashes after rebuilding.
- Normal Android-app/desktop/Firestore canonical equality passed: `build/firebase-sync-and-android-icon-final.log`. Isolated Android Room roundtrips covered duplicates, desktop rename and deletion convergence without changing unrelated favorites/history.
- Native VLC transport, queue, repeat, Smart shuffle retention, sleep timer and verified offline-file playback/seek passed. Unsaved audio was blocked in offline-only mode. Earlier live YouTube search/playback/seek checks passed, without promising every recording or acoustic quality.
- Real GitHub updater check passed: `build/windows-updater-live-check.log`; no Windows release existed at that check. Actual Windows installation/upgrades and post-install retention remain unverified.
- Android debug build/lint, Room and Profile checks passed; no phone was accessed. New import/discovery/download screen interaction has not received full visual acceptance.

Regenerate icon assets with `scripts/generate_desktop_icon.py`. Remaining work includes Commons/provider settings, frequent-song caching, queue restoration, Windows media integration and connected/multi-output audio; see [parity](DESKTOP_PARITY.md) and [audio design](DESKTOP_SYNC_DESIGN.md).
