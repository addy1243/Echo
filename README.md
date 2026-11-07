# Echo Music

Echo is a Kotlin music player for Android and Windows. The current release is [0.2.4](https://github.com/addy1243/Echo/releases/tag/v0.2.4). Its artifact hashes and acceptance evidence are in [the release record](docs/releases/0.2.4.md). The working branch can lag the latest release tag.

The Android app uses Media3 and supports a local library, playlists, queues, search, key-free YouTube playback, downloads, optional account sync, and connected playback. The Windows desktop app uses Compose, LibVLC, local library storage, imports, and an in-app MSI updater. Windows requires 64-bit VLC 3 to be installed separately.

## Project guide

- [Agent instructions](AGENTS.md): orchestrator roles, delegation, workflow, and engineering standards.
- [Important requirements](docs/IMP.md): current state, safety boundaries, and behavioral invariants.
- [Architecture](docs/ARCHITECTURE.md): module ownership and data flow.
- [Recent session log](docs/LOGS.md): the latest three completed sessions.
- [Windows shipping skill](.agents/skills/echo-windows-shipping/SKILL.md): build, release, updater, installer, and laptop acceptance steps.

## Build

Open the repository in Android Studio or use the Gradle wrapper. Android targets API 36 and requires Android SDK setup. Windows MSI packaging requires the documented Temurin JDK 21 toolchain; VLC 3 is a separate runtime prerequisite. Read the shipping skill before building a public release.

## License

Echo source is GPL-3.0-or-later. See [LICENSE](LICENSE), [COPYING](COPYING), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the project and bundled dependency terms.
