package dev.echo.data.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.core.Playlist
import dev.echo.core.Track
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class LibraryRepository internal constructor(private val context: Context, private val database: LibraryDatabase) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context, Room.databaseBuilder(context, LibraryDatabase::class.java, "echo-library.db").build(),
    )
    private val dao = database.libraryDao()
    val tracks: Flow<List<Track>> = dao.tracks().map { rows -> rows.map { it.model() } }
    val playlists: Flow<List<Playlist>> = dao.playlists().map { rows -> rows.map { it.model() } }
    val favorites: Flow<Set<String>> = dao.favorites().map { it.toSet() }
    val recent: Flow<List<Track>> = dao.recent().map { rows -> rows.map { it.model() } }

    suspend fun upsertTracks(tracks: List<Track>) = database.withTransaction {
        require(tracks.all { it.id.isNotBlank() && it.durationMs >= 0 }) { "Tracks need an ID and a non-negative duration" }
        dao.upsertTracks(tracks.map { it.entity() })
        dao.upsertArtists(tracks.map { ArtistEntity(it.artist) }.distinctBy { it.name })
        dao.upsertAlbums(tracks.map { AlbumEntity(it.album, it.artist, it.artworkUri) }.distinctBy { it.name to it.artist })
        dao.upsertMappings(tracks.map { TrackMappingEntity(it.provider, it.id, it.id) }.distinctBy { it.provider to it.externalId })
    }

    suspend fun createPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        dao.upsertPlaylist(PlaylistEntity(id, checkedName(name)))
        return id
    }

    suspend fun renamePlaylist(id: String, name: String) = database.withTransaction {
        requirePlaylist(id)
        dao.renamePlaylist(id, checkedName(name))
    }

    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)

    suspend fun playlistTracks(id: String): List<Track> = dao.playlistTracks(id).map { it.model() }

    suspend fun addToPlaylist(playlistId: String, trackId: String) = database.withTransaction {
        requirePlaylist(playlistId)
        require(dao.hasTrack(trackId)) { "Track does not exist" }
        val ids = dao.playlistIds(playlistId)
        dao.insertEntries(listOf(PlaylistTrackEntity(playlistId, ids.size, trackId)))
    }

    suspend fun removeFromPlaylist(playlistId: String, position: Int) = database.withTransaction {
        requirePlaylist(playlistId)
        val ids = dao.playlistIds(playlistId).toMutableList()
        require(position in ids.indices) { "Playlist position is out of range" }
        ids.removeAt(position)
        replaceEntries(playlistId, ids)
    }

    suspend fun movePlaylistTrack(playlistId: String, from: Int, to: Int) = database.withTransaction {
        requirePlaylist(playlistId)
        replaceEntries(playlistId, movedPlaylistIds(dao.playlistIds(playlistId), from, to))
    }

    suspend fun setFavorite(trackId: String, liked: Boolean) = database.withTransaction {
        require(dao.hasTrack(trackId)) { "Track does not exist" }
        if (liked) dao.favorite(FavoriteEntity(trackId)) else dao.unfavorite(trackId)
    }

    suspend fun recordPlayed(trackId: String) = database.withTransaction {
        require(dao.hasTrack(trackId)) { "Track does not exist" }
        dao.record(HistoryEntity(trackId = trackId, playedAt = System.currentTimeMillis()))
        dao.trimHistory()
    }

    suspend fun matchLocalTrack(importedTrackId: String, localTrackId: String) = database.withTransaction {
        val imported = requireNotNull(dao.track(importedTrackId)) { "Imported track does not exist" }
        val local = requireNotNull(dao.track(localTrackId)) { "Local track does not exist" }
        require(imported.id != local.id && imported.provider != "local") { "Choose an imported track to match" }
        require(local.provider == "local" && !local.sourceUri.isNullOrBlank()) { "Choose a playable local recording" }
        dao.matchLocal(LocalTrackMatchEntity(importedTrackId, localTrackId))
    }

    suspend fun clearLocalMatch(importedTrackId: String) = dao.clearLocalMatch(importedTrackId)

    suspend fun importPlaylistFile(text: String, filename: String): String = withContext(Dispatchers.IO) {
        val file = dev.echo.data.spotify.parsePlaylistFile(text, filename)
        database.withTransaction {
            // New IDs keep a repeated file import from overwriting an edited playlist.
            file.playlists.forEach { playlist -> importPlaylist(UUID.randomUUID().toString(), playlist.name, playlist.tracks) }
        }
        "Imported ${file.playlists.size} playlists, ${file.playlists.sumOf { it.tracks.size }} songs" +
            if (file.skipped > 0) "; skipped ${file.skipped} podcast/local entries" else ""
    }

    suspend fun replacePlaylistTrack(id: String, position: Int, expectedId: String, recording: Track) = database.withTransaction {
        requirePlaylist(id)
        val ids = dao.playlistIds(id).toMutableList()
        require(ids.getOrNull(position) == expectedId) { "Playlist changed; choose the recording again" }
        require(recording.provider == "youtube" && recording.sourceUri != null) { "Choose a YouTube recording" }
        upsertTracks(listOf(recording))
        ids[position] = recording.id
        replaceEntries(id, ids)
    }

    suspend fun applyRecordingMatches(id: String, expectedIds: List<String>, matches: Map<String, Track>) = database.withTransaction {
        requirePlaylist(id)
        require(dao.playlistIds(id) == expectedIds) { "Playlist changed while matching. Retry to keep your edits." }
        require(matches.keys.all { it in expectedIds } && matches.values.all { it.provider in setOf("local", "youtube") && it.sourceUri != null }) { "Invalid recording match" }
        upsertTracks(matches.values.toList())
        val liked = dao.allFavorites().toSet()
        matches.forEach { (importedId, recording) -> if (importedId in liked) dao.favorite(FavoriteEntity(recording.id)) }
        replaceEntries(id, expectedIds.map { matches[it]?.id ?: it })
    }

    /** Library metadata only; does not contain settings, credentials, or audio files. */
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            LibraryBackup(
                dao.allTracks().map { it.model() },
                dao.allPlaylists().map { BackupPlaylist(it.model(), dao.playlistIds(it.id)) },
                dao.allFavorites(), dao.localMatches(),
            ).json()
        }
    }

    /** Merges IDs: backup tracks and same-ID playlists replace metadata/order; favorites are added. */
    suspend fun importBackup(json: String) = withContext(Dispatchers.IO) {
        val backup = LibraryBackup.parse(json)
        database.withTransaction {
            upsertTracks(backup.tracks)
            backup.playlists.forEach { entry ->
                val playlist = entry.playlist
                dao.upsertPlaylist(PlaylistEntity(playlist.id, playlist.name, playlist.spotifyId))
                replaceEntries(playlist.id, entry.trackIds)
                val spotifyId = playlist.spotifyId
                if (spotifyId != null) dao.upsertImport(ImportedPlaylistEntity(playlist.id, "spotify", spotifyId, System.currentTimeMillis())) else dao.clearImport(playlist.id)
            }
            backup.favorites.forEach { dao.favorite(FavoriteEntity(it)) }
            backup.matches.forEach { matchLocalTrack(it.importedTrackId, it.localTrackId) }
        }
    }

    suspend fun importPlaylist(id: String, name: String, tracks: List<Track>, spotifyId: String? = null) = database.withTransaction {
        require(id.isNotBlank()) { "Playlist ID cannot be empty" }
        val playlist = PlaylistEntity(id, checkedName(name), spotifyId)
        upsertTracks(tracks)
        dao.upsertPlaylist(playlist)
        replaceEntries(id, tracks.map { it.id })
        if (spotifyId != null) dao.upsertImport(ImportedPlaylistEntity(id, "spotify", spotifyId, System.currentTimeMillis())) else dao.clearImport(id)
    }

    suspend fun scanLocal() = withContext(Dispatchers.IO) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Audio library permission is required")
        }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID)
        val found = mutableListOf<Track>()
        val cursor = context.contentResolver.query(collection, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)
            ?: error("The device audio library could not be read")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val albumId = it.getLong(albumIdColumn)
                found += Track(
                    id = "local:$id", title = it.getString(titleColumn).orEmpty().ifBlank { "Unknown title" },
                    artist = it.getString(artistColumn).orEmpty().ifBlank { "Unknown artist" }, album = it.getString(albumColumn).orEmpty(),
                    durationMs = it.getLong(durationColumn).coerceAtLeast(0),
                    artworkUri = if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId).toString() else null,
                    sourceUri = ContentUris.withAppendedId(collection, id).toString(),
                )
            }
        }
        // shortcut: unavailable files remain in playlists; add an availability flag when library pruning is needed.
        upsertTracks(found)
    }

    private suspend fun requirePlaylist(id: String) = require(dao.hasPlaylist(id)) { "Playlist does not exist" }
    private suspend fun replaceEntries(id: String, ids: List<String>) {
        dao.clearPlaylist(id)
        dao.insertEntries(ids.mapIndexed { position, trackId -> PlaylistTrackEntity(id, position, trackId) })
    }
}
