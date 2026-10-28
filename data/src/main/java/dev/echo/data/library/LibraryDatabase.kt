package dev.echo.data.library

import androidx.room.*
import dev.echo.core.Playlist
import dev.echo.core.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
internal data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String?,
    val sourceUri: String?,
    val provider: String,
    val isrc: String?,
    val attribution: String?,
    val licenseUrl: String?,
    val sourcePage: String?,
) {
    fun model() = Track(id, title, artist, album, durationMs, artworkUri, sourceUri, provider, isrc, attribution, licenseUrl, sourcePage)
}

internal fun Track.entity() = TrackEntity(id, title, artist, album, durationMs, artworkUri, sourceUri, provider, isrc, attribution, licenseUrl, sourcePage)

@Entity(tableName = "artists")
internal data class ArtistEntity(@PrimaryKey val name: String)

@Entity(tableName = "albums", primaryKeys = ["name", "artist"])
internal data class AlbumEntity(val name: String, val artist: String, val artworkUri: String?)

@Entity(tableName = "playlists")
internal data class PlaylistEntity(@PrimaryKey val id: String, val name: String, val spotifyId: String? = null) {
    fun model() = Playlist(id, name, spotifyId)
}

@Entity(
    tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index("trackId")],
)
internal data class PlaylistTrackEntity(val playlistId: String, val position: Int, val trackId: String)

@Entity(tableName = "favorites", foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)])
internal data class FavoriteEntity(@PrimaryKey val trackId: String)

@Entity(tableName = "playback_history", foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)], indices = [Index("trackId")])
internal data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
    @ColumnInfo(defaultValue = "'PLAYED'") val event: String = "PLAYED",
    @ColumnInfo(defaultValue = "0") val listenedMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
)

internal data class ListeningHistoryRow(@Embedded val history: HistoryEntity, @Relation(parentColumn = "trackId", entityColumn = "id") val track: TrackEntity)

@Entity(tableName = "track_mappings", primaryKeys = ["provider", "externalId"], foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)], indices = [Index("trackId")])
internal data class TrackMappingEntity(val provider: String, val externalId: String, val trackId: String)

@Entity(tableName = "local_track_matches", foreignKeys = [
    ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["importedTrackId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["localTrackId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("localTrackId")])
internal data class LocalTrackMatchEntity(@PrimaryKey val importedTrackId: String, val localTrackId: String)

@DatabaseView("""SELECT t.id, t.title, t.artist, t.album, t.durationMs, t.artworkUri,
    COALESCE(l.sourceUri, t.sourceUri) AS sourceUri,
    CASE WHEN l.sourceUri IS NOT NULL THEN l.provider ELSE t.provider END AS provider,
    t.isrc, t.attribution, t.licenseUrl, t.sourcePage
    FROM tracks t LEFT JOIN local_track_matches m ON m.importedTrackId = t.id
    LEFT JOIN tracks l ON l.id = m.localTrackId""", viewName = "playable_tracks")
internal data class PlayableTrackView(@Embedded val track: TrackEntity) {
    fun model() = track.model()
}

@Entity(tableName = "cached_metadata", primaryKeys = ["provider", "cacheKey"])
internal data class CachedMetadataEntity(val provider: String, val cacheKey: String, val payload: String, val fetchedAt: Long)

@Entity(tableName = "imported_playlists", foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)])
internal data class ImportedPlaylistEntity(@PrimaryKey val playlistId: String, val provider: String, val externalId: String, val importedAt: Long)

@Dao
internal interface LibraryDao {
    @Query("SELECT * FROM playable_tracks ORDER BY title COLLATE NOCASE, id") fun tracks(): Flow<List<PlayableTrackView>>
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE, id") fun playlists(): Flow<List<PlaylistEntity>>
    @Query("SELECT trackId FROM favorites") fun favorites(): Flow<List<String>>
    @Query("SELECT playable_tracks.* FROM playable_tracks INNER JOIN playback_history ON playable_tracks.id = playback_history.trackId WHERE playback_history.event = 'PLAYED' GROUP BY playable_tracks.id ORDER BY MAX(playback_history.playedAt) DESC, MAX(playback_history.id) DESC LIMIT 100") fun recent(): Flow<List<PlayableTrackView>>
    @Query("SELECT playable_tracks.* FROM playable_tracks INNER JOIN playlist_tracks ON playable_tracks.id = playlist_tracks.trackId WHERE playlistId = :id ORDER BY position") suspend fun playlistTracks(id: String): List<PlayableTrackView>
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): TrackEntity?
    @Query("SELECT * FROM tracks ORDER BY id") suspend fun allTracks(): List<TrackEntity>
    @Query("SELECT * FROM playlists ORDER BY id") suspend fun allPlaylists(): List<PlaylistEntity>
    @Query("SELECT trackId FROM favorites ORDER BY trackId") suspend fun allFavorites(): List<String>
    @Query("SELECT * FROM local_track_matches ORDER BY importedTrackId") suspend fun localMatches(): List<LocalTrackMatchEntity>
    @Upsert suspend fun matchLocal(match: LocalTrackMatchEntity)
    @Query("DELETE FROM local_track_matches WHERE importedTrackId = :id") suspend fun clearLocalMatch(id: String)
    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :id ORDER BY position") suspend fun playlistIds(id: String): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM playlists WHERE id = :id)") suspend fun hasPlaylist(id: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE id = :id)") suspend fun hasTrack(id: String): Boolean
    @Upsert suspend fun upsertTracks(tracks: List<TrackEntity>)
    @Upsert suspend fun upsertArtists(artists: List<ArtistEntity>)
    @Upsert suspend fun upsertAlbums(albums: List<AlbumEntity>)
    @Upsert suspend fun upsertPlaylist(playlist: PlaylistEntity)
    @Upsert suspend fun upsertMappings(mappings: List<TrackMappingEntity>)
    @Upsert suspend fun upsertImport(imported: ImportedPlaylistEntity)
    @Query("DELETE FROM imported_playlists WHERE playlistId = :id") suspend fun clearImport(id: String)
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: String, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id") suspend fun clearPlaylist(id: String)
    @Insert suspend fun insertEntries(entries: List<PlaylistTrackEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun favorite(favorite: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE trackId = :id") suspend fun unfavorite(id: String)
    @Insert suspend fun record(history: HistoryEntity): Long
    @Query("SELECT * FROM playback_history ORDER BY playedAt DESC, id DESC LIMIT 5000") suspend fun allHistory(): List<HistoryEntity>
    @Query("DELETE FROM playback_history WHERE id = :id") suspend fun deleteHistory(id: Long)
    @Query("DELETE FROM favorites") suspend fun clearFavorites()
    @Query("SELECT * FROM cached_metadata WHERE provider = :provider AND cacheKey = :key") suspend fun metadata(provider: String, key: String): CachedMetadataEntity?
    @Query("SELECT * FROM cached_metadata WHERE provider = 'firebase-sync' AND cacheKey LIKE 'history-id:%'") suspend fun firebaseHistoryMappings(): List<CachedMetadataEntity>
    @Upsert suspend fun upsertMetadata(metadata: CachedMetadataEntity)
    @Query("DELETE FROM cached_metadata WHERE provider = 'firebase-sync' AND cacheKey = :key") suspend fun deleteFirebaseMetadata(key: String)
    @Query("DELETE FROM cached_metadata WHERE provider = 'firebase-sync' AND cacheKey LIKE 'history-id:%'") suspend fun clearFirebaseHistoryMappings()
    @Query("DELETE FROM cached_metadata WHERE provider = 'firebase-sync' AND cacheKey LIKE 'history-id:%' AND cacheKey NOT IN (SELECT 'history-id:' || id FROM playback_history)") suspend fun pruneFirebaseHistoryMappings()
    @Transaction @Query("SELECT * FROM playback_history ORDER BY playedAt DESC, id DESC LIMIT 5000") fun listeningHistory(): Flow<List<ListeningHistoryRow>>
    @Query("DELETE FROM playback_history WHERE playedAt < :oldest OR id NOT IN (SELECT id FROM playback_history ORDER BY playedAt DESC, id DESC LIMIT 5000)") suspend fun trimHistory(oldest: Long)
    @Query("DELETE FROM playback_history") suspend fun clearHistory()
}

@Database(entities = [TrackEntity::class, ArtistEntity::class, AlbumEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, FavoriteEntity::class, HistoryEntity::class, TrackMappingEntity::class, LocalTrackMatchEntity::class, CachedMetadataEntity::class, ImportedPlaylistEntity::class], views = [PlayableTrackView::class], version = 2, exportSchema = true)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playback_history ADD COLUMN event TEXT NOT NULL DEFAULT 'PLAYED'")
        db.execSQL("ALTER TABLE playback_history ADD COLUMN listenedMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE playback_history ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
    }
}
