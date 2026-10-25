# Echo Music architecture

Four Gradle modules: `core` (pure Kotlin contracts), `data` (Room, MediaStore, settings and integrations), `playback` (Media3 service and Android Auto), `app` (Hilt composition and Compose screens). Packages separate providers, persistence, and presentation without creating empty feature modules.

Playlist-file parsing (Spotify account JSON / Exportify CSV) stays in `data`; the repository imports all playlists atomically with new IDs. Explicit recording selection replaces one guarded playlist occurrence with a canonical YouTube track. Discovery uses the existing provider's artist searches; pure app helpers filter discoveries and interleave queue entries. No schema migration, automatic Spotify sync, extra backend or new dependency is required.

Canonical model: `dev.echo.core.Track`; URIs are strings at domain boundaries. Spotify metadata may have no audio URI. Duplicate playlist occurrences use independent positions. No destructive database migration or client secrets.

The service owns ExoPlayer. The phone UI connects with MediaController. Car browsers use the same MediaLibraryService and durable library.

YouTube uses NewPipeExtractor for key-free song search and stream resolution. Persisted tracks contain stable provider/video IDs and canonical watch links. The service resolves temporary audio URLs lazily through a Media3 resolving data source, keeping them out of Room and backups. Local files and Commons retain their provider paths; Spotify imports remain metadata-only until locally matched. See [integration setup and maintenance](INTEGRATIONS.md#key-free-youtube-playback) and [release history](../CHANGELOG.md).
