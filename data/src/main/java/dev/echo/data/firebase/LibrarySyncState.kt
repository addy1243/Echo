package dev.echo.data.firebase

import dev.echo.core.Track
import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class FirebasePlaylist(val id: String, val name: String, val trackIds: List<String>, val spotifyId: String? = null)
data class FirebaseHistoryEvent(val id: String, val trackId: String, val event: String, val occurredAt: Long,
    val listenedMs: Long, val durationMs: Long)
data class FirebaseHistoryEpoch(val counter: Long = 0, val deviceId: String = "") : Comparable<FirebaseHistoryEpoch> {
    override fun compareTo(other: FirebaseHistoryEpoch): Int = compareValuesBy(this, other, { it.counter }, { it.deviceId })
}
data class LibrarySyncState(
    val tracks: List<Track> = emptyList(),
    val playlists: List<FirebasePlaylist> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val history: List<FirebaseHistoryEvent> = emptyList(),
    val deletedPlaylists: Set<String> = emptySet(),
    val unlikedTracks: Set<String> = emptySet(),
    val historyEpoch: FirebaseHistoryEpoch = FirebaseHistoryEpoch(),
)

private val deviceIdPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
private val cloudLocalPattern = Regex("cloud-local:${deviceIdPattern.pattern}:[a-f0-9]{64}")
private val videoPattern = Regex("youtube:([a-zA-Z0-9_-]{11})")
private val historyTypes = setOf("PLAYED", "SKIPPED", "COMPLETED", "INTERRUPTED", "ERROR")

/** Device files get opaque stable identities; the file path is never a cloud identifier. */
fun cloudTrackId(track: Track, deviceId: String): String {
    require(deviceIdPattern.matches(deviceId)) { "Invalid sync installation identity" }
    if (track.provider != "local") return track.id
    if (cloudLocalPattern.matches(track.id)) return track.id
    return "cloud-local:$deviceId:${digest(track.id)}"
}

fun sanitizeLibrarySyncState(state: LibrarySyncState, deviceId: String): LibrarySyncState {
    val mapping = state.tracks.associate { it.id to cloudTrackId(it, deviceId) }
    require(mapping.size == state.tracks.size && mapping.values.toSet().size == mapping.size) { "Duplicate cloud track identity" }
    val result = state.copy(
        tracks = state.tracks.map { safeTrack(it.copy(id = mapping.getValue(it.id))) },
        playlists = state.playlists.map { it.copy(trackIds = it.trackIds.map { id -> mapping[id] ?: error("Missing playlist track") }) },
        favorites = state.favorites.map { mapping[it] ?: error("Missing favorite track") }.toSet(),
        history = state.history.map { it.copy(trackId = mapping[it.trackId] ?: error("Missing history track")) },
        unlikedTracks = state.unlikedTracks.map { mapping[it] ?: it }.toSet(),
    )
    validate(result)
    return result
}

/** The cloud write uses a version precondition; this function never edits either input. */
fun mergeLibrarySyncStates(baseline: LibrarySyncState?, local: LibrarySyncState, remote: LibrarySyncState,
    nowMillis: Long = System.currentTimeMillis()): LibrarySyncState {
    validate(local); validate(remote); baseline?.let(::validate)
    val before = baseline?.playlists.orEmpty().associateBy { it.id }
    val here = local.playlists.associateBy { it.id }
    val there = remote.playlists.associateBy { it.id }
    val deleted = (baseline?.deletedPlaylists.orEmpty() + local.deletedPlaylists + remote.deletedPlaylists +
        (before.keys - here.keys) + (before.keys - there.keys)).toMutableSet()
    val playlists = linkedMapOf<String, FirebasePlaylist>()
    val conflicts = mutableListOf<FirebasePlaylist>()
    (here.keys + there.keys).sorted().forEach { id ->
        val ours = here[id]
        val theirs = there[id]
        val old = before[id]
        if (id in deleted) {
            // Preserve a concurrent edit as a copy, while the deleted original stays deleted.
            listOfNotNull(ours, theirs).filter { old != null && it != old }.distinct().forEach(conflicts::add)
        } else {
            val chosen = when {
                ours == theirs -> ours
                ours == old -> theirs
                theirs == old -> ours
                ours == null -> theirs
                theirs == null -> ours
                else -> {
                    val ordered = listOf(ours, theirs).sortedBy(::playlistSignature)
                    conflicts += ordered[1]
                    ordered[0]
                }
            }
            chosen?.let { playlists[id] = it }
        }
    }
    conflicts.distinct().sortedBy(::playlistSignature).forEach { playlist ->
        var collision = 0
        while (true) {
            val copy = playlist.copy(id = "${playlist.id.take(180)}-conflict-${digest(playlistSignature(playlist) + ":$collision")}",
                name = playlist.name.take(184) + " (sync conflict)")
            // A deleted deterministic conflict copy must not return under a newly salted identity.
            if (copy.id in deleted) break
            if (playlists[copy.id] == null || playlists[copy.id] == copy) {
                playlists[copy.id] = copy
                break
            }
            collision++
        }
    }

    val oldFavorites = baseline?.favorites.orEmpty()
    val unliked = (baseline?.unlikedTracks.orEmpty() + local.unlikedTracks + remote.unlikedTracks +
        (oldFavorites - local.favorites) + (oldFavorites - remote.favorites)).toMutableSet()
    // Re-like is explicit only after this installation has observed the previous unlike.
    val knownUnlikes = baseline?.unlikedTracks.orEmpty()
    unliked.removeAll(((local.favorites - oldFavorites) + (remote.favorites - oldFavorites)).intersect(knownUnlikes))
    val favorites = (local.favorites + remote.favorites) - unliked

    val epoch = maxOf(local.historyEpoch, remote.historyEpoch, baseline?.historyEpoch ?: FirebaseHistoryEpoch())
    val events = (if (local.historyEpoch == epoch) local.history else emptyList()) +
        (if (remote.historyEpoch == epoch) remote.history else emptyList())
    val history = events.groupBy { it.id }.map { (_, duplicates) ->
        require(duplicates.distinct().size == 1) { "Conflicting listening event identity" }
        duplicates.first()
    }.filter { it.occurredAt >= nowMillis - 90L * 24 * 60 * 60 * 1000 }
        .sortedWith(compareBy({ it.occurredAt }, { it.id })).takeLast(5000)
    val oldTracks = baseline?.tracks.orEmpty().associateBy { it.id }
    val localTracks = local.tracks.associateBy { it.id }
    val remoteTracks = remote.tracks.associateBy { it.id }
    val tracks = (localTracks.keys + remoteTracks.keys).associateWith { id ->
        val ours = localTracks[id]
        val theirs = remoteTracks[id]
        val old = oldTracks[id]
        when {
            ours == theirs -> requireNotNull(ours)
            ours == null -> requireNotNull(theirs)
            theirs == null -> ours
            ours == old -> theirs
            theirs == old -> ours
            else -> minOf(ours, theirs, compareBy(::trackSignature))
        }
    }
    val result = LibrarySyncState(tracks.keys.sorted().map(tracks::getValue),
        playlists.values.toList(), favorites, history, deleted, unliked, epoch)
    validate(result)
    return result
}

fun encodeLibrarySyncState(state: LibrarySyncState): String {
    validate(state)
    return JSONObject().apply {
        put("format", "echo-firebase-library"); put("version", 2)
        put("tracks", JSONArray(state.tracks.sortedBy { it.id }.map { original ->
            val track = safeTrack(original)
            JSONObject().apply {
                put("id", track.id); put("title", track.title); put("artist", track.artist); put("album", track.album)
                put("durationMs", track.durationMs); put("provider", track.provider)
                put("sourceUri", track.sourceUri ?: JSONObject.NULL); put("artworkUri", track.artworkUri ?: JSONObject.NULL)
                put("sourcePage", track.sourcePage ?: JSONObject.NULL); put("licenseUrl", track.licenseUrl ?: JSONObject.NULL)
                put("isrc", track.isrc ?: JSONObject.NULL); put("attribution", track.attribution ?: JSONObject.NULL)
            }
        }))
        put("playlists", JSONArray(state.playlists.sortedBy { it.id }.map { playlist -> JSONObject().apply {
            put("id", playlist.id); put("name", playlist.name); put("trackIds", JSONArray(playlist.trackIds))
            put("spotifyId", playlist.spotifyId ?: JSONObject.NULL)
        } }))
        put("favorites", JSONArray(state.favorites.sorted()))
        put("deletedPlaylists", JSONArray(state.deletedPlaylists.sorted()))
        put("unlikedTracks", JSONArray(state.unlikedTracks.sorted()))
        put("historyEpoch", JSONObject().put("counter", state.historyEpoch.counter).put("deviceId", state.historyEpoch.deviceId))
        put("history", JSONArray(state.history.sortedWith(compareBy({ it.occurredAt }, { it.id })).map { event -> JSONObject().apply {
            put("id", event.id); put("trackId", event.trackId); put("event", event.event); put("occurredAt", event.occurredAt)
            put("listenedMs", event.listenedMs); put("durationMs", event.durationMs)
        } }))
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Cloud library is too large" } }
}

fun decodeLibrarySyncState(json: String): LibrarySyncState {
    require(json.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Cloud library is too large" }
    val root = JSONObject(json)
    require(root.getString("format") == "echo-firebase-library" && root.getInt("version") == 2) { "Unsupported cloud library" }
    val state = LibrarySyncState(
        tracks = root.getJSONArray("tracks").objects().map { item -> safeTrack(Track(
            item.getString("id"), item.getString("title"), item.getString("artist"), item.getString("album"),
            item.getLong("durationMs"), item.optionalString("artworkUri"), item.optionalString("sourceUri"), item.getString("provider"),
            item.optionalString("isrc"), item.optionalString("attribution"), item.optionalString("licenseUrl"), item.optionalString("sourcePage"))) },
        playlists = root.getJSONArray("playlists").objects().map { FirebasePlaylist(it.getString("id"), it.getString("name"),
            it.getJSONArray("trackIds").strings(), it.optionalString("spotifyId")) },
        favorites = root.getJSONArray("favorites").strings().toSet(),
        history = root.getJSONArray("history").objects().map { FirebaseHistoryEvent(it.getString("id"), it.getString("trackId"),
            it.getString("event"), it.getLong("occurredAt"), it.getLong("listenedMs"), it.getLong("durationMs")) },
        deletedPlaylists = root.getJSONArray("deletedPlaylists").strings().toSet(),
        unlikedTracks = root.getJSONArray("unlikedTracks").strings().toSet(),
        historyEpoch = root.getJSONObject("historyEpoch").let { FirebaseHistoryEpoch(it.getLong("counter"), it.getString("deviceId")) },
    )
    validate(state)
    return state
}

private fun safeTrack(track: Track): Track {
    require(track.id.length in 1..4096 && track.title.length <= 2000 && track.artist.length <= 2000 &&
        track.album.length <= 2000 && track.durationMs >= 0 && track.provider.length in 1..100) { "Invalid cloud track" }
    if (track.provider == "local") require(cloudLocalPattern.matches(track.id)) { "Local tracks must use an opaque installation-qualified identity" }
    val source = when (track.provider) {
        "youtube" -> videoPattern.matchEntire(track.id)?.groupValues?.get(1)?.let { "https://www.youtube.com/watch?v=$it" }
        "commons" -> safeUrl(track.sourceUri)?.takeIf { URI(it).host == "upload.wikimedia.org" }
        else -> null
    }
    return track.copy(sourceUri = source, artworkUri = safeUrl(track.artworkUri), sourcePage = safeUrl(track.sourcePage),
        licenseUrl = safeUrl(track.licenseUrl))
}

private fun safeUrl(value: String?): String? = value?.takeIf { it.length <= 8192 }?.let { text -> runCatching {
    URI(text).takeIf { it.scheme == "https" && it.host != null && it.rawUserInfo == null &&
        it.port in listOf(-1, 443) && it.fragment == null && it.rawQuery == null }?.toASCIIString()
}.getOrNull() }

private fun validate(state: LibrarySyncState) {
    require(state.tracks.size <= 20_000 && state.playlists.size <= 2000 && state.history.size <= 5000 &&
        state.deletedPlaylists.size <= 20_000 && state.unlikedTracks.size <= 20_000) { "Cloud library limit reached" }
    val tracks = state.tracks.map { safeTrack(it).id }.toSet()
    require(tracks.size == state.tracks.size && state.playlists.map { it.id }.toSet().size == state.playlists.size &&
        state.history.map { it.id }.toSet().size == state.history.size) { "Duplicate cloud identity" }
    require(state.playlists.sumOf { it.trackIds.size } <= 100_000 && state.playlists.all { it.id.length in 1..512 &&
        it.name.trim().length in 1..200 && it.trackIds.all(tracks::contains) && it.id !in state.deletedPlaylists }) { "Invalid cloud playlist" }
    require(state.favorites.all(tracks::contains) && state.favorites.none { it in state.unlikedTracks }) { "Invalid cloud favorites" }
    require(state.history.all { it.id.length in 1..512 && it.trackId in tracks && it.event in historyTypes &&
        it.occurredAt >= 0 && it.listenedMs >= 0 && it.durationMs >= 0 }) { "Invalid cloud listening history" }
    require(state.historyEpoch.counter == 0L && state.historyEpoch.deviceId.isEmpty() ||
        state.historyEpoch.counter > 0 && deviceIdPattern.matches(state.historyEpoch.deviceId)) { "Invalid history clear revision" }
    require((state.deletedPlaylists + state.unlikedTracks).all { it.length in 1..4096 }) { "Invalid cloud deletion marker" }
}

private fun playlistSignature(playlist: FirebasePlaylist): String =
    (listOf(playlist.id, playlist.name, playlist.spotifyId.orEmpty()) + playlist.trackIds).joinToString("") { "${it.length}:$it" }
private fun trackSignature(track: Track): String = JSONArray(listOf(track.id, track.title, track.artist, track.album,
    track.durationMs, track.artworkUri, track.sourceUri, track.provider, track.isrc, track.attribution,
    track.licenseUrl, track.sourcePage)).toString()
private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
