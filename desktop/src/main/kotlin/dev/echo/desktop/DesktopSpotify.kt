package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.provider.TrackMatcher
import dev.echo.data.provider.YouTubePlaylistPreview
import dev.echo.data.provider.automaticRecordingMatch
import dev.echo.data.provider.youtubePlaylistId
import dev.echo.data.spotify.SpotifyLinkPreview
import dev.echo.data.spotify.parsePlaylistFile
import dev.echo.data.spotify.parseSpotifyAccountZip
import dev.echo.data.spotify.spotifyPlaylistLinkId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DesktopImportPlaylist(val name: String, val tracks: List<Track>, val spotifyId: String? = null)
data class DesktopPlaylistImport(val playlists: List<DesktopImportPlaylist>, val skipped: Int = 0, val archive: ByteArray? = null,
    val historyCount: Int = 0, val savedArtistCount: Int = 0, val savedAlbumCount: Int = 0)

fun parseDesktopPlaylistImport(text: String, filename: String): DesktopPlaylistImport {
    val parsed = parsePlaylistFile(text, filename)
    return DesktopPlaylistImport(parsed.playlists.map { DesktopImportPlaylist(it.name, it.tracks) }, parsed.skipped)
}

suspend fun readDesktopPlaylistImport(file: File): DesktopPlaylistImport = withContext(Dispatchers.IO) {
    require(file.isFile && file.canRead()) { "Choose a readable playlist file" }
    // Bound the actual read as well as the initial size; the file may change while importing.
    val isZip = file.name.endsWith(".zip", ignoreCase = true)
    val limit = if (isZip) 50_000_000 else 10_000_000
    val bytes = file.inputStream().use { it.readNBytes(limit + 1) }
    require(bytes.size <= limit) { "File is larger than ${limit / 1_000_000} MB" }
    currentCoroutineContext().ensureActive()
    if (isZip) {
        val parsed = parseSpotifyAccountZip(bytes)
        DesktopPlaylistImport(parsed.playlists.map { DesktopImportPlaylist(it.name, it.tracks) }, parsed.skipped, bytes,
            parsed.history.size, parsed.savedArtists.size, parsed.savedAlbums.size)
    } else parseDesktopPlaylistImport(bytes.toString(Charsets.UTF_8), file.name)
}

/** Call only after displaying the exposed/total track counts and receiving import confirmation. */
fun desktopSpotifyImport(preview: SpotifyLinkPreview, link: String): DesktopPlaylistImport {
    val id = spotifyPlaylistLinkId(link)
    require(preview.tracks.isNotEmpty() && (preview.total == null || preview.total >= preview.tracks.size)) {
        "Spotify returned inconsistent playlist counts"
    }
    return DesktopPlaylistImport(listOf(DesktopImportPlaylist(preview.name, preview.tracks, id)))
}

fun desktopYouTubeImport(preview: YouTubePlaylistPreview, link: String): DesktopPlaylistImport {
    youtubePlaylistId(link)
    require(preview.tracks.isNotEmpty()) { "YouTube returned no playlist songs to import" }
    return DesktopPlaylistImport(listOf(DesktopImportPlaylist(preview.name, preview.tracks)))
}

suspend fun automaticDesktopMatches(
    items: List<Track>,
    localTracks: List<Track>,
    search: suspend (String) -> List<Track>,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): Map<String, Track> {
    val missing = items.filter { it.provider == "spotify" && it.sourceUri == null }.distinctBy { it.id }
    require(missing.isNotEmpty()) { "This playlist has no unmatched imported songs" }
    val local = localTracks.filter { it.provider == "local" && it.sourceUri != null }
    val result = mutableMapOf<String, Track>()
    missing.forEachIndexed { index, track ->
        currentCoroutineContext().ensureActive()
        onProgress(index, missing.size)
        val recording = local.filter { TrackMatcher.matches(track, it) }.singleOrNull()
            ?: automaticRecordingMatch(track, search("${track.title} ${track.artist}".take(200)))
        if (recording != null) result[track.id] = recording
        currentCoroutineContext().ensureActive()
        onProgress(index + 1, missing.size)
    }
    return result
}
