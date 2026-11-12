# Echo Music

Download [Echo 0.2.7 for Android or Windows](https://github.com/addy1243/Echo/releases/tag/v0.2.7). The [release record](docs/releases/0.2.7.md) lists the artifacts and verification.

Echo 0.2.4 adds public YouTube playlist-title search and public playlist-link preview/import on Android and Windows. Search results open the existing preview-and-confirm flow; capped or partial results are identified before import. Spotify imports remain public-link or playlist-file based, without Spotify title search, audio or automatic sync. See [integration details](docs/INTEGRATIONS.md).

The Android app uses Media3 and supports a local library, playlists, queues, search, key-free YouTube playback, downloads, optional account sync, and connected playback. The Windows desktop app uses Compose, LibVLC, local library storage, imports, and an in-app MSI updater. Windows requires 64-bit VLC 3 to be installed separately.

## Project guide

Echo Music's original source is licensed under GPL-3.0-or-later; see [LICENSE](LICENSE), [COPYING](COPYING) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party terms and matching source. Settings → Licenses & source displays the packaged notices.

## 0.2.4 — Android code15 and Windows 0.2.4 — published 2026-11-03

[Release page](https://github.com/addy1243/Echo/releases/tag/v0.2.4) · [Android APK](https://github.com/addy1243/Echo/releases/download/v0.2.4/EchoMusic-15.apk) · [Windows installer](https://github.com/addy1243/Echo/releases/download/v0.2.4/EchoMusic-Windows-x64-0.2.4.msi) · [Full corresponding source](https://github.com/addy1243/Echo/releases/download/v0.2.4/EchoMusic-15-source.zip) · [Checksums](https://github.com/addy1243/Echo/releases/download/v0.2.4/SHA256SUMS) · [Release record](docs/releases/0.2.4.md)

Android is0.2.4/code15, Android8.0+, signed with the original certificate for in-place updates. Windows is0.2.4/x64 with bundled GPL Temurin Java21; install64-bit VLC3 separately. The MSI is unsigned and installation remains interactive. The [previous release](https://github.com/addy1243/Echo/releases/tag/v0.2.3) and its assets are unchanged.

The local executable is at `desktop/build/compose/binaries/main/app/EchoMusic/EchoMusic.exe`. Keep its full app/runtime directory together; the MSI installs the separate updatable copy. Use the existing Start-menu shortcut after an MSI upgrade and restart Echo to load the new version.

## Android features

Version 0.2.0 uses Firebase after separate consent, including favorites/history and account-wide clear/delete semantics. Install the newer same-key APK to obtain these changes. See [account sync](docs/FIREBASE.md).

In a playlist, **Add playlist to queue** offers Playlist order, Shuffle or Smart shuffle and preserves existing playback/upcoming songs. Search result taps play just the selected song, then continue the prior queue. Shuffle starts from a random playable occurrence, with every occurrence reachable in a new repeat-off session. Smart shuffle keeps active playback or chooses a random anchor for a fresh session. The full player also offers a **Sleep timer** (15/30/45/60 minutes) that pauses without clearing the queue and runs while the player screen is closed. See [queue behavior](docs/QUEUE_ACTIONS.md) and [sleep timer](docs/SLEEP_TIMER.md).

Drag an upcoming queue row by its handle to arrange real playback order; the current song stays fixed. Profile shows the local playlist count, account status and last successful Firebase sync; live device presence is not implemented.

The player and queue can add any saved/recommended recording to an existing or new playlist. Full-player artwork requests a larger recognized Google image variant with original-image fallback; source images still limit detail.

Use a song's **Download** action to pin private offline audio. **Settings → Downloads & cache** lists saved/pending/failed items with retry, cancel and remove, and has separate **Offline mode** and **Cache frequently played songs** switches. The automatic cache is opt-in after three qualified listens on unmetered internet, with a separate 256 MiB budget. Explicit downloads have a 2 GiB budget and are never automatically evicted. Manual downloads continue after leaving Echo, with progress/cancel notifications and visible byte/percentage progress in Downloads. Interrupted transfers restart through Retry. See [offline design and limits](docs/OFFLINE.md).

**Settings → Listening history** shows starts, skips, completions, interruptions and errors, with a confirmed clear action. Up to 5,000 events/90 days inform small preference adjustments within real related-song results; the screen shows the latest 500. History stays local unless Firebase library sync is explicitly enabled. See [history semantics](docs/HISTORY_NOTES.md).

**Profile — Google account & library sync** supports optional Google sign-in/out and Firebase playlists/favorites/history sync. Automatic sync runs about once a minute while Echo is open. Concurrent playlist edits preserve conflict copies; unlikes, deletions and history clearing converge through versioned state. Local data remains after sign-out. Audio, downloads, credentials and local file paths are not uploaded. Normal emulator sign-in and Android/desktop/cloud equality passed; no physical phone was accessed. See [account setup](docs/ACCOUNTS.md) and [Firebase evidence](docs/FIREBASE.md).

## Build

Open the repository in Android Studio or use the Gradle wrapper. Android targets API 36 and requires Android SDK setup. Windows MSI packaging requires the documented Temurin JDK 21 toolchain; VLC 3 is a separate runtime prerequisite. Read the shipping skill before building a public release.

## License

Echo source is GPL-3.0-or-later. See [LICENSE](LICENSE), [COPYING](COPYING), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the project and bundled dependency terms.
