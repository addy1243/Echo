package dev.echo.core

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val artworkUri: String? = null,
    val sourceUri: String? = null,
    val provider: String = "local",
    val isrc: String? = null,
    val attribution: String? = null,
    val licenseUrl: String? = null,
    val sourcePage: String? = null,
)

data class Playlist(val id: String, val name: String, val spotifyId: String? = null)
data class PlayableSource(val uri: String, val headers: Map<String, String> = emptyMap(), val mimeType: String? = null, val expiresAt: Long? = null)

interface AudioSourceProvider {
    val id: String
    suspend fun isAvailable(): Boolean
    suspend fun resolve(track: Track): PlayableSource?
}

interface MusicMetadataProvider {
    val id: String
    suspend fun search(query: String): List<Track>
}
