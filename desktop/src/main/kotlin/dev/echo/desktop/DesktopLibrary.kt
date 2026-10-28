package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.FirebaseHistoryEpoch
import dev.echo.data.firebase.FirebaseHistoryEvent
import dev.echo.data.firebase.FirebasePlaylist
import dev.echo.data.firebase.LibrarySyncState
import dev.echo.data.firebase.cloudTrackId
import dev.echo.data.firebase.decodeLibrarySyncState
import dev.echo.data.firebase.encodeLibrarySyncState
import dev.echo.data.firebase.sanitizeLibrarySyncState
import dev.echo.app.account.qualifyCloudLocalTracks
import dev.echo.app.account.restoreOwnLocalTracks
import dev.echo.app.account.sanitizeCloudBackup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class DesktopPlaylist(val id: String, val name: String, val trackIds: List<String>, val spotifyId: String? = null)
data class DesktopHistoryEntry(
    val id: String, val trackId: String, val event: String, val occurredAt: Long,
    val listenedMs: Long, val durationMs: Long,
)
data class DesktopLibraryState(
    val tracks: List<Track> = emptyList(),
    val playlists: List<DesktopPlaylist> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val history: List<DesktopHistoryEntry> = emptyList(),
    val installationId: String = UUID.randomUUID().toString(),
    val historyEpoch: FirebaseHistoryEpoch = FirebaseHistoryEpoch(),
    val deletedPlaylists: Set<String> = emptySet(),
    val unlikedTracks: Set<String> = emptySet(),
)

/** Local desktop data only. A failed load or write never replaces the last valid library. */
class DesktopLibrary(
    val file: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "EchoMusic", "library.json"),
) {
    private var state = if (Files.exists(file)) {
        require(Files.size(file) <= MAX_BYTES) { "Echo library is too large" }
        decode(JSONObject(Files.readString(file)))
    } else DesktopLibraryState()

    @Synchronized fun snapshot(): DesktopLibraryState = state.copy(
        tracks = state.tracks.toList(), playlists = state.playlists.map { it.copy(trackIds = it.trackIds.toList()) },
        favorites = state.favorites.toSet(), history = state.history.toList(),
        deletedPlaylists = state.deletedPlaylists.toSet(), unlikedTracks = state.unlikedTracks.toSet(),
    )

    val firebaseDeviceId: String get() = synchronized(this) { persistInstallation(); state.installationId }

    @Synchronized fun exportFirebaseState(): LibrarySyncState {
        persistInstallation()
        return sanitizeLibrarySyncState(LibrarySyncState(
            tracks = state.tracks,
            playlists = state.playlists.map { FirebasePlaylist(it.id, it.name, it.trackIds, it.spotifyId) },
            favorites = state.favorites,
            history = state.history.map { FirebaseHistoryEvent(it.id, it.trackId, it.event, it.occurredAt, it.listenedMs, it.durationMs) },
            deletedPlaylists = state.deletedPlaylists, unlikedTracks = state.unlikedTracks, historyEpoch = state.historyEpoch,
        ), state.installationId)
    }

    @Synchronized fun applyFirebaseState(expected: LibrarySyncState, merged: LibrarySyncState, bootstrapAccount: Boolean = false) {
        check(encodeLibrarySyncState(exportFirebaseState()) == encodeLibrarySyncState(expected)) {
            "Library changed during sync. Retry to preserve your edits."
        }
        val incoming = decodeLibrarySyncState(encodeLibrarySyncState(merged))
        check(bootstrapAccount || incoming.historyEpoch >= expected.historyEpoch) { "History clear revision cannot move backwards" }
        val ownTracks = state.tracks.associateBy { cloudTrackId(it, state.installationId) }
        val restored = incoming.tracks.map { track ->
            val existing = ownTracks[track.id]?.takeIf { it.provider == "local" }
            if (existing != null) checkedTrack(track.copy(id = existing.id, sourceUri = existing.sourceUri,
                artworkUri = existing.artworkUri ?: track.artworkUri)) else checkedTrack(track)
        }
        val ids = incoming.tracks.zip(restored).associate { (cloud, local) -> cloud.id to local.id }
        commit(state.copy(
            tracks = (state.tracks + restored).associateBy(Track::id).values.toList(),
            playlists = incoming.playlists.map { DesktopPlaylist(it.id, checkedName(it.name), it.trackIds.map(ids::getValue), it.spotifyId) },
            favorites = incoming.favorites.map(ids::getValue).toSet(),
            history = incoming.history.map { DesktopHistoryEntry(it.id, ids.getValue(it.trackId), it.event,
                it.occurredAt, it.listenedMs, it.durationMs) },
            historyEpoch = incoming.historyEpoch, deletedPlaylists = incoming.deletedPlaylists,
            unlikedTracks = incoming.unlikedTracks.map { ids[it] ?: ownTracks[it]?.id ?: it }.toSet(),
        ))
    }

    private fun persistInstallation() {
        if (!Files.exists(file) || JSONObject(Files.readString(file)).optString("installationId").isBlank()) commit(state)
    }

    @Synchronized fun saveTrack(track: Track) = commit(withTrack(state, track))

    @Synchronized fun toggleFavorite(track: Track) {
        val next = withTrack(state, track)
        val removing = track.id in next.favorites
        commit(next.copy(favorites = if (removing) next.favorites - track.id else next.favorites + track.id,
            unlikedTracks = if (removing) next.unlikedTracks + track.id else next.unlikedTracks - track.id))
    }

    @Synchronized fun createPlaylist(name: String): DesktopPlaylist {
        val playlist = DesktopPlaylist(UUID.randomUUID().toString(), checkedName(name), emptyList())
        commit(state.copy(playlists = state.playlists + playlist, deletedPlaylists = state.deletedPlaylists - playlist.id))
        return playlist
    }

    @Synchronized fun addToPlaylist(playlistId: String, track: Track) {
        require(state.playlists.any { it.id == playlistId }) { "Playlist no longer exists" }
        val next = withTrack(state, track)
        commit(next.copy(playlists = next.playlists.map {
            if (it.id == playlistId) it.copy(trackIds = it.trackIds + track.id) else it
        }))
    }

    @Synchronized fun importParsedPlaylists(import: DesktopPlaylistImport): List<DesktopPlaylist> {
        require(import.playlists.isNotEmpty() && import.playlists.size <= 1000 &&
            import.playlists.sumOf { it.tracks.size } <= 50_000) { "Import up to 1,000 playlists and 50,000 songs" }
        val tracks = import.playlists.flatMap { it.tracks }.map(::checkedTrack)
        val playlists = import.playlists.map {
            require(it.spotifyId == null || Regex("[A-Za-z0-9]{22}").matches(it.spotifyId)) { "Invalid Spotify playlist" }
            DesktopPlaylist(UUID.randomUUID().toString(), checkedName(it.name), it.tracks.map(Track::id), it.spotifyId)
        }
        commit(state.copy(tracks = (tracks + state.tracks).associateBy(Track::id).values.toList(),
            playlists = state.playlists + playlists))
        return playlists
    }

    @Synchronized fun replacePlaylistRecording(playlistId: String, index: Int, expectedIds: List<String>, recording: Track) {
        val playlist = unchangedPlaylist(playlistId, expectedIds)
        require(index in playlist.trackIds.indices) { "Song no longer exists in this playlist" }
        val safe = playableRecording(recording)
        val original = playlist.trackIds[index]
        val next = withTrack(state, safe)
        val liked = original in next.favorites
        commit(next.copy(playlists = next.playlists.map {
            if (it.id == playlistId) it.copy(trackIds = it.trackIds.toMutableList().apply { set(index, safe.id) }) else it
        }, favorites = if (liked) next.favorites + safe.id else next.favorites,
            unlikedTracks = if (liked) next.unlikedTracks - safe.id else next.unlikedTracks))
    }

    @Synchronized fun applyRecordingMatches(playlistId: String, expectedIds: List<String>, matches: Map<String, Track>) {
        unchangedPlaylist(playlistId, expectedIds)
        require(matches.keys.all { it in expectedIds }) { "Invalid recording match" }
        val safe = matches.mapValues { playableRecording(it.value) }
        val liked = safe.filterKeys { it in state.favorites }.values.map { it.id }.toSet()
        commit(state.copy(tracks = (state.tracks + safe.values).associateBy(Track::id).values.toList(),
            playlists = state.playlists.map { if (it.id == playlistId) it.copy(trackIds = expectedIds.map { id -> safe[id]?.id ?: id }) else it },
            favorites = state.favorites + liked, unlikedTracks = state.unlikedTracks - liked))
    }

    private fun playableRecording(track: Track): Track = checkedTrack(track).also {
        require(it.provider in setOf("local", "youtube") && it.sourceUri != null) { "Choose a playable local or YouTube recording" }
    }

    @Synchronized fun renamePlaylist(id: String, name: String) {
        val playlist = playlist(id)
        replacePlaylist(playlist.copy(name = checkedName(name)))
    }

    @Synchronized fun deletePlaylist(id: String) {
        playlist(id)
        commit(state.copy(playlists = state.playlists.filterNot { it.id == id }, deletedPlaylists = state.deletedPlaylists + id))
    }

    @Synchronized fun removeFromPlaylist(id: String, index: Int, expectedTrackIds: List<String>) {
        val playlist = unchangedPlaylist(id, expectedTrackIds)
        require(index in playlist.trackIds.indices) { "Song no longer exists in this playlist" }
        replacePlaylist(playlist.copy(trackIds = playlist.trackIds.toMutableList().apply { removeAt(index) }))
    }

    @Synchronized fun movePlaylistTrack(id: String, from: Int, to: Int, expectedTrackIds: List<String>) {
        val playlist = unchangedPlaylist(id, expectedTrackIds)
        require(from in playlist.trackIds.indices && to in playlist.trackIds.indices) { "Song no longer exists in this playlist" }
        if (from != to) replacePlaylist(playlist.copy(trackIds = playlist.trackIds.toMutableList().apply { add(to, removeAt(from)) }))
    }

    /** Android-compatible metadata backup, excluding history, audio and desktop-local paths. */
    @Synchronized fun exportBackup(): String = metadataBackup(includeFavorites = true)

    /** Shared Android v1 protocol deliberately synchronizes playlists only. */
    @Synchronized fun exportCloudBackup(deviceId: String): String =
        qualifyCloudLocalTracks(metadataBackup(includeFavorites = false), deviceId)

    @Synchronized fun applyCloudPlaylists(expectedBackup: String, mergedBackup: String, deletedPlaylistIds: Set<String>, deviceId: String) {
        check(exportCloudBackup(deviceId) == expectedBackup) { "Library changed during sync. Retry to keep your edits." }
        val sanitized = sanitizeCloudBackup(mergedBackup)
        require(JSONObject(sanitized).getJSONArray("tracks").objects().all {
            it.getString("provider") != "local" || it.getString("id").startsWith("cloud-local:")
        }) { "Cloud-local tracks must have a device identity" }
        val root = JSONObject(restoreOwnLocalTracks(sanitized, deviceId))
        val localIds = state.tracks.filter { it.provider == "local" && !it.id.startsWith("cloud-local:") }
            .associate { portableTrackId(it) to it.id }
        val tracks = root.getJSONArray("tracks").objects().map { item ->
            val incoming = decodeTrack(item, imported = true)
            incoming.copy(id = localIds[incoming.id] ?: incoming.id)
        }
        val idMap = root.getJSONArray("tracks").objects().zip(tracks).associate { (json, track) -> json.getString("id") to track.id }
        val playlists = root.getJSONArray("playlists").objects().map {
            DesktopPlaylist(it.getString("id"), checkedName(it.getString("name")),
                it.getJSONArray("trackIds").strings().map(idMap::getValue), it.optionalString("spotifyId"))
        }
        require(playlists.none { it.id in deletedPlaylistIds }) { "Merged playlist cannot also be deleted" }
        val replacements = playlists.associateBy { it.id }
        val existingIds = state.playlists.map { it.id }.toSet()
        commit(state.copy(
            tracks = (tracks + state.tracks).associateBy(Track::id).values.toList(),
            playlists = state.playlists.filterNot { it.id in deletedPlaylistIds }.map { replacements[it.id] ?: it } +
                playlists.filter { it.id !in existingIds },
        ))
    }

    private fun portableTrackId(track: Track): String = if (track.provider == "local" && !track.id.startsWith("cloud-local:"))
        "desktop-local:${UUID.nameUUIDFromBytes("${file.toAbsolutePath()}\u0000${track.id}".toByteArray(Charsets.UTF_8))}" else track.id

    private fun metadataBackup(includeFavorites: Boolean): String {
        val ids = state.tracks.associate { it.id to portableTrackId(it) }
        val portable = state.copy(
            tracks = state.tracks.map { track -> if (track.provider == "local")
                track.copy(id = ids.getValue(track.id), sourceUri = null, artworkUri = null) else track },
            playlists = state.playlists.map { it.copy(trackIds = it.trackIds.map(ids::getValue)) },
            favorites = if (includeFavorites) state.favorites.map(ids::getValue).toSet() else emptySet(), history = emptyList(),
        )
        return encode(portable).apply {
            put("format", "echo-library"); remove("history"); put("localMatches", JSONArray())
            listOf("installationId", "historyEpoch", "deletedPlaylists", "unlikedTracks").forEach(::remove)
            getJSONArray("tracks").objects().forEach { it.put("sourcePage", JSONObject.NULL) }
        }.toString(2).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup is too large" } }
    }

    private fun playlist(id: String): DesktopPlaylist = state.playlists.find { it.id == id }
        ?: throw IllegalArgumentException("Playlist no longer exists")

    private fun unchangedPlaylist(id: String, expectedTrackIds: List<String>): DesktopPlaylist = playlist(id).also {
        require(it.trackIds == expectedTrackIds) { "Playlist changed. Open it again before editing this song." }
    }

    private fun replacePlaylist(playlist: DesktopPlaylist) =
        commit(state.copy(playlists = state.playlists.map { if (it.id == playlist.id) playlist else it }))

    @Synchronized fun recordHistory(track: Track, event: String, listenedMs: Long = 0, durationMs: Long = track.durationMs) {
        require(event in EVENTS && listenedMs >= 0 && durationMs >= 0) { "Invalid listening event" }
        val next = withTrack(state, track)
        val now = System.currentTimeMillis()
        val entry = DesktopHistoryEntry(UUID.randomUUID().toString(), track.id, event, now, listenedMs, durationMs)
        commit(next.copy(history = (next.history.filter { it.occurredAt >= now - HISTORY_AGE_MS } + entry).takeLast(5000)))
    }

    @Synchronized fun clearHistory() {
        check(state.historyEpoch.counter < Long.MAX_VALUE) { "History clear revision is exhausted" }
        commit(state.copy(history = emptyList(), historyEpoch = FirebaseHistoryEpoch(state.historyEpoch.counter + 1, state.installationId)))
    }

    /** Additive Android metadata backup import; phone-local paths never become desktop playback sources. */
    @Synchronized fun importBackup(json: String): Int {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup must be 8 MiB or smaller" }
        val root = JSONObject(json)
        require(root.getString("format") == "echo-library" && root.getInt("version") == 1) { "Unsupported Echo backup" }
        val tracks = root.getJSONArray("tracks").objects().map { decodeTrack(it, imported = true) }
        require(tracks.map { it.id }.distinct().size == tracks.size) { "Duplicate backup track IDs" }
        val ids = tracks.map { it.id }.toSet()
        val playlists = root.getJSONArray("playlists").objects().map {
            val members = it.getJSONArray("trackIds").strings()
            require(members.all(ids::contains)) { "Playlist refers to a missing track" }
            DesktopPlaylist(UUID.randomUUID().toString(), checkedName(it.getString("name")), members, it.optionalString("spotifyId"))
        }
        val favorites = root.getJSONArray("favorites").strings().toSet()
        require(favorites.all(ids::contains)) { "Favorite refers to a missing track" }
        // Keep an existing desktop file association if an imported phone entry has the same ID.
        val merged = (tracks + state.tracks).associateBy(Track::id).values.toList()
        commit(state.copy(tracks = merged, playlists = state.playlists + playlists, favorites = state.favorites + favorites,
            unlikedTracks = state.unlikedTracks - favorites))
        return playlists.size
    }

    private fun withTrack(previous: DesktopLibraryState, track: Track): DesktopLibraryState {
        val safe = checkedTrack(track)
        return previous.copy(tracks = previous.tracks.filterNot { it.id == safe.id } + safe)
    }

    private fun commit(next: DesktopLibraryState) {
        require(next.tracks.size <= 20_000 && next.playlists.size <= 2000 && next.playlists.sumOf { it.trackIds.size } <= 100_000) {
            "Desktop library limit reached"
        }
        val bytes = encode(next).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Desktop library is too large" }
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "echo-library-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            state = next
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val HISTORY_AGE_MS = 90L * 24 * 60 * 60 * 1000
        private val EVENTS = setOf("PLAYED", "SKIPPED", "COMPLETED", "INTERRUPTED", "ERROR")
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        fun localTrack(file: File): Track {
            val canonical = file.canonicalFile
            require(canonical.isFile && canonical.canRead()) { "Choose a readable audio file" }
            val uri = canonical.toURI().toASCIIString()
            return Track(id = "desktop:$uri", title = canonical.nameWithoutExtension, artist = "Local file", sourceUri = uri)
        }

        private fun checkedName(name: String): String = name.trim().also {
            require(it.isNotEmpty() && it.length <= 200) { "Use a playlist name of 1–200 characters" }
        }

        private fun checkedTrack(track: Track): Track {
            require(track.id.isNotBlank() && track.id.length <= 4096 && track.title.length <= 2000 && track.artist.length <= 2000 && track.durationMs >= 0) {
                "Invalid track metadata"
            }
            val source = when {
                track.provider == "youtube" && VIDEO_ID.matches(track.id.removePrefix("youtube:")) && track.id.startsWith("youtube:") ->
                    "https://www.youtube.com/watch?v=${track.id.removePrefix("youtube:")}"
                track.provider == "local" && track.id.startsWith("desktop:") && track.sourceUri?.startsWith("file:") == true -> {
                    require(URI(track.sourceUri).scheme == "file") { "Invalid local file" }
                    track.sourceUri
                }
                else -> null
            }
            return track.copy(sourceUri = source)
        }

        private fun encode(state: DesktopLibraryState) = JSONObject().apply {
            put("format", "echo-desktop-library"); put("version", 1)
            put("installationId", state.installationId)
            put("historyEpoch", JSONObject().put("counter", state.historyEpoch.counter).put("deviceId", state.historyEpoch.deviceId))
            put("deletedPlaylists", JSONArray(state.deletedPlaylists.sorted())); put("unlikedTracks", JSONArray(state.unlikedTracks.sorted()))
            put("tracks", JSONArray(state.tracks.map { track -> JSONObject().apply {
                put("id", track.id); put("title", track.title); put("artist", track.artist); put("album", track.album)
                put("durationMs", track.durationMs); put("provider", track.provider)
                put("artworkUri", track.artworkUri ?: JSONObject.NULL); put("sourceUri", track.sourceUri ?: JSONObject.NULL)
                put("isrc", track.isrc ?: JSONObject.NULL); put("attribution", track.attribution ?: JSONObject.NULL)
                put("licenseUrl", track.licenseUrl ?: JSONObject.NULL)
            } }))
            put("playlists", JSONArray(state.playlists.map { JSONObject().put("id", it.id).put("name", it.name)
                .put("trackIds", JSONArray(it.trackIds)).put("spotifyId", it.spotifyId ?: JSONObject.NULL) }))
            put("favorites", JSONArray(state.favorites.toList()))
            put("history", JSONArray(state.history.map { JSONObject().put("id", it.id).put("trackId", it.trackId).put("event", it.event)
                .put("occurredAt", it.occurredAt).put("listenedMs", it.listenedMs).put("durationMs", it.durationMs) }))
        }

        private fun decode(root: JSONObject): DesktopLibraryState {
            require(root.getString("format") == "echo-desktop-library" && root.getInt("version") == 1) { "Unsupported desktop library" }
            val tracks = root.getJSONArray("tracks").objects().map { decodeTrack(it) }
            val ids = tracks.map { it.id }.toSet()
            require(ids.size == tracks.size && tracks.size <= 20_000) { "Invalid desktop track IDs" }
            val playlists = root.getJSONArray("playlists").objects().map {
                DesktopPlaylist(it.getString("id"), checkedName(it.getString("name")), it.getJSONArray("trackIds").strings(), it.optionalString("spotifyId"))
            }
            require(playlists.size <= 2000 && playlists.map { it.id }.distinct().size == playlists.size &&
                playlists.all { it.id.isNotBlank() && it.trackIds.all(ids::contains) }) { "Invalid desktop playlists" }
            val favorites = root.getJSONArray("favorites").strings().toSet()
            require(favorites.all(ids::contains)) { "Invalid desktop favorites" }
            val history = root.getJSONArray("history").objects().map {
                DesktopHistoryEntry(it.getString("id"), it.getString("trackId"), it.getString("event"), it.getLong("occurredAt"),
                    it.getLong("listenedMs"), it.getLong("durationMs"))
            }
            require(history.size <= 5000 && history.all { it.trackId in ids && it.event in EVENTS && it.listenedMs >= 0 && it.durationMs >= 0 }) {
                "Invalid desktop history"
            }
            val installation = root.optString("installationId").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            require(installation.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "Invalid installation identity" }
            val epoch = root.optJSONObject("historyEpoch")?.let { FirebaseHistoryEpoch(it.getLong("counter"), it.getString("deviceId")) }
                ?: FirebaseHistoryEpoch()
            val deleted = root.optJSONArray("deletedPlaylists")?.strings()?.toSet().orEmpty()
            val unliked = root.optJSONArray("unlikedTracks")?.strings()?.toSet().orEmpty()
            require((epoch.deviceId.isEmpty() && epoch.counter == 0L || epoch.counter > 0 &&
                epoch.deviceId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) &&
                deleted.size <= 20_000 && unliked.size <= 20_000 && playlists.none { it.id in deleted } && favorites.none { it in unliked }) {
                "Invalid desktop sync metadata"
            }
            return DesktopLibraryState(tracks, playlists, favorites, history, installation, epoch, deleted, unliked)
        }

        private fun decodeTrack(item: JSONObject, imported: Boolean = false): Track = checkedTrack(Track(
            id = item.getString("id"), title = item.getString("title"), artist = item.getString("artist"),
            album = item.optString("album"), durationMs = item.optLong("durationMs"), provider = item.optString("provider", "local"),
            artworkUri = item.optionalString("artworkUri")?.takeIf { !imported || it.startsWith("https://") },
            sourceUri = item.optionalString("sourceUri")?.takeUnless { imported && item.optString("provider", "local") == "local" },
            isrc = item.optionalString("isrc"), attribution = item.optionalString("attribution"), licenseUrl = item.optionalString("licenseUrl"),
        ))
        private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
        private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
    }
}
