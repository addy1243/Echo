package dev.echo.data.library

import dev.echo.core.Playlist
import dev.echo.core.Track
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupPlaylist(val playlist: Playlist, val trackIds: List<String>)
internal data class LibraryBackup(val tracks: List<Track>, val playlists: List<BackupPlaylist>, val favorites: List<String>, val matches: List<LocalTrackMatchEntity>) {
    fun json(): String = JSONObject().apply {
        put("format", "echo-library")
        put("version", 1)
        put("tracks", JSONArray(tracks.map { track -> JSONObject().apply {
            put("id", track.id); put("title", track.title); put("artist", track.artist)
            put("album", track.album); put("durationMs", track.durationMs); put("provider", track.provider)
            put("artworkUri", track.artworkUri ?: JSONObject.NULL); put("sourceUri", track.sourceUri ?: JSONObject.NULL)
            put("isrc", track.isrc ?: JSONObject.NULL); put("attribution", track.attribution ?: JSONObject.NULL)
            put("licenseUrl", track.licenseUrl ?: JSONObject.NULL); put("sourcePage", track.sourcePage ?: JSONObject.NULL)
        } }))
        put("playlists", JSONArray(playlists.map { entry -> JSONObject().apply {
            put("id", entry.playlist.id); put("name", entry.playlist.name)
            put("spotifyId", entry.playlist.spotifyId ?: JSONObject.NULL); put("trackIds", JSONArray(entry.trackIds))
        } }))
        put("favorites", JSONArray(favorites))
        put("localMatches", JSONArray(matches.map { JSONObject().put("importedTrackId", it.importedTrackId).put("localTrackId", it.localTrackId) }))
    }.toString(2)

    companion object {
        fun parse(json: String): LibraryBackup {
            val root = JSONObject(json)
            require(root.getString("format") == "echo-library" && root.getInt("version") == 1) { "Unsupported Echo backup format" }
            val tracks = root.getJSONArray("tracks").objects().map { item ->
                Track(
                    id = item.getString("id"), title = item.getString("title"), artist = item.getString("artist"),
                    album = item.getString("album"), durationMs = item.getLong("durationMs"), provider = item.getString("provider"),
                    artworkUri = item.nullableString("artworkUri"), sourceUri = item.nullableString("sourceUri"),
                    isrc = item.nullableString("isrc"), attribution = item.nullableString("attribution"),
                    licenseUrl = item.nullableString("licenseUrl"), sourcePage = item.nullableString("sourcePage"),
                )
            }
            require(tracks.all { it.id.isNotBlank() && it.durationMs >= 0 } && tracks.map { it.id }.distinct().size == tracks.size) { "Invalid or duplicate backup track IDs" }
            val ids = tracks.map { it.id }.toSet()
            val playlists = root.getJSONArray("playlists").objects().map { item ->
                BackupPlaylist(Playlist(item.getString("id"), checkedName(item.getString("name")), item.nullableString("spotifyId")), item.getJSONArray("trackIds").strings())
            }
            require(playlists.all { it.playlist.id.isNotBlank() && it.trackIds.all(ids::contains) } && playlists.map { it.playlist.id }.distinct().size == playlists.size) { "Invalid backup playlist or missing track" }
            val favorites = root.getJSONArray("favorites").strings()
            require(favorites.all(ids::contains)) { "Favorite refers to a missing backup track" }
            val matches = root.getJSONArray("localMatches").objects().map { LocalTrackMatchEntity(it.getString("importedTrackId"), it.getString("localTrackId")) }
            require(matches.all { it.importedTrackId in ids && it.localTrackId in ids } && matches.map { it.importedTrackId }.distinct().size == matches.size) { "Invalid local track mappings" }
            return LibraryBackup(tracks, playlists, favorites, matches)
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
