package dev.echo.app.account

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

private data class CloudSnapshot(val tracks: Map<String, JSONObject>, val playlists: List<SyncPlaylist>)

fun sanitizeCloudBackup(json: String): String = readCloudSnapshot(json).json()

/** MediaStore IDs belong to one installation and must never resolve to another phone's file. */
fun qualifyCloudLocalTracks(json: String, deviceId: String): String = mapLocalIds(json, deviceId, restore = false)

fun restoreOwnLocalTracks(json: String, deviceId: String): String = mapLocalIds(json, deviceId, restore = true)

private fun mapLocalIds(json: String, deviceId: String, restore: Boolean): String {
    val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    require(deviceId.matches(uuid)) { "Invalid sync device identity" }
    val root = JSONObject(sanitizeCloudBackup(json))
    val tracks = root.getJSONArray("tracks").objects()
    val replacements = tracks.associate { track ->
        val id = track.string("id")
        val qualified = if (id.startsWith("cloud-local:")) {
            val parts = id.split(':', limit = 3)
            require(parts.size == 3 && parts[1].matches(uuid) && parts[2].isNotBlank() && !parts[2].startsWith("cloud-local:")) { "Invalid cloud-local identity" }
            require(track.string("provider") == "local") { "Cloud-local identity must be local metadata" }
            parts
        } else null
        val mapped = when {
            track.string("provider") != "local" -> id
            restore -> if (qualified?.get(1) == deviceId) qualified[2] else id
            qualified != null -> id
            else -> "cloud-local:$deviceId:$id"
        }
        id to mapped
    }
    require(replacements.values.distinct().size == replacements.size) { "Conflicting cloud-local identities" }
    tracks.forEach { it.put("id", replacements.getValue(it.string("id"))) }
    root.getJSONArray("playlists").objects().forEach { playlist ->
        playlist.put("trackIds", JSONArray(playlist.getJSONArray("trackIds").strings().map(replacements::getValue)))
    }
    return root.toString()
}

fun mergeCloudBackups(baseline: String?, local: String, remote: String): String {
    val before = baseline?.let(::readCloudSnapshot)
    val here = readCloudSnapshot(local)
    val there = readCloudSnapshot(remote)
    val playlists = mergePlaylists(before?.playlists.orEmpty(), here.playlists, there.playlists)
    return CloudSnapshot(there.tracks + here.tracks, playlists).json()
}

private fun readCloudSnapshot(json: String): CloudSnapshot {
    require(json.length <= 8 * 1024 * 1024) { "Playlist snapshot is too large" }
    val root = JSONObject(json)
    require(root.getString("format") == "echo-library" && root.getInt("version") == 1) { "Unsupported Echo backup format" }
    val tracks = root.getJSONArray("tracks").objects().map { original ->
        val id = original.string("id")
        val provider = original.string("provider")
        val duration = original.getLong("durationMs")
        require(id.isNotBlank() && duration >= 0) { "Invalid cloud track" }
        val source = secureUrl(original.nullableString("sourceUri"))
        val canonicalSource = when (provider) {
            "youtube" -> source?.takeIf { url ->
                url.host == "www.youtube.com" && url.encodedPath == "/watch" &&
                    url.queryParameterNames == setOf("v") && url.queryParameterValues("v").size == 1 &&
                    url.queryParameter("v")?.matches(Regex("[a-zA-Z0-9_-]{11}")) == true &&
                    id == "youtube:${url.queryParameter("v")}"
            }?.toString()
            "commons" -> source?.takeIf { it.host == "upload.wikimedia.org" }?.toString()
            else -> null
        }
        id to JSONObject().apply {
            put("id", id); put("title", original.string("title")); put("artist", original.string("artist"))
            put("album", original.string("album")); put("durationMs", duration); put("provider", provider)
            put("sourceUri", canonicalSource ?: JSONObject.NULL)
            listOf("artworkUri", "sourcePage", "licenseUrl").forEach { key ->
                put(key, secureUrl(original.nullableString(key))?.toString() ?: JSONObject.NULL)
            }
            listOf("isrc", "attribution").forEach { key -> put(key, original.nullableString(key) ?: JSONObject.NULL) }
        }
    }
    require(tracks.map { it.first }.distinct().size == tracks.size) { "Duplicate cloud track IDs" }
    val trackMap = tracks.toMap()
    val playlists = root.getJSONArray("playlists").objects().map { item ->
        val ids = item.getJSONArray("trackIds").strings()
        val name = item.string("name").trim()
        require(name.isNotBlank() && ids.all(trackMap::containsKey)) { "Invalid cloud playlist or missing track" }
        SyncPlaylist(item.string("id"), name, ids, item.nullableString("spotifyId"))
    }
    require(playlists.all { it.id.isNotBlank() } && playlists.map { it.id }.distinct().size == playlists.size) { "Invalid or duplicate cloud playlist IDs" }
    return CloudSnapshot(trackMap, playlists)
}

private fun CloudSnapshot.json(): String {
    val referenced = playlists.flatMap { it.trackIds }.toSet()
    require(referenced.all(tracks::containsKey)) { "Cloud playlist refers to a missing track" }
    return JSONObject().apply {
        put("format", "echo-library"); put("version", 1)
        put("tracks", JSONArray(tracks.filterKeys { it in referenced }.values.toList()))
        put("playlists", JSONArray(playlists.map { playlist -> JSONObject().apply {
            put("id", playlist.id); put("name", playlist.name); put("trackIds", JSONArray(playlist.trackIds))
            put("spotifyId", playlist.spotifyId ?: JSONObject.NULL)
        } }))
        put("favorites", JSONArray()); put("localMatches", JSONArray())
    }.toString()
}

private fun secureUrl(value: String?): HttpUrl? = value?.toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
}
private fun JSONObject.string(key: String): String = get(key) as? String ?: error("Invalid $key")
private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else string(key)
private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = List(length()) { get(it) as? String ?: error("Invalid track ID") }
