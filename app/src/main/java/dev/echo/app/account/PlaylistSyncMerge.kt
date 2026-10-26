package dev.echo.app.account

import java.security.MessageDigest

data class SyncPlaylist(val id: String, val name: String, val trackIds: List<String>, val spotifyId: String? = null)

/** Both inputs are complete snapshots; a missing ID means deletion, not a partial fetch. */
fun mergePlaylists(baseline: List<SyncPlaylist>, local: List<SyncPlaylist>, remote: List<SyncPlaylist>): List<SyncPlaylist> {
    fun index(items: List<SyncPlaylist>): Map<String, SyncPlaylist> {
        require(items.all { it.id.isNotBlank() }) { "Playlist IDs must not be blank" }
        require(items.map { it.id }.distinct().size == items.size) { "Playlist IDs must be unique" }
        return items.associateBy { it.id }
    }
    val previous = index(baseline)
    val here = index(local)
    val there = index(remote)
    val merged = linkedMapOf<String, SyncPlaylist>()
    val conflicts = mutableListOf<SyncPlaylist>()
    (here.keys + there.keys + previous.keys).forEach { id ->
        val old = previous[id]
        val ours = here[id]
        val theirs = there[id]
        val result = when {
            ours == theirs -> ours
            ours == old -> theirs
            theirs == old -> ours
            else -> {
                if (theirs != null && ours != null) conflicts += theirs
                ours ?: theirs
            }
        }
        result?.let { merged[id] = it }
    }
    conflicts.forEach { playlist ->
        val name = "${playlist.name} (sync conflict)"
        var collision = 0
        while (true) {
            val digest = MessageDigest.getInstance("SHA-256")
            // Length prefixes keep titles and repeated track IDs unambiguous without JSON dependencies.
            (listOf(playlist.id, playlist.name, playlist.spotifyId?.let { "spotify:$it" } ?: "no-spotify") + playlist.trackIds + collision.toString()).forEach { value ->
                digest.update("${value.toByteArray(Charsets.UTF_8).size}:$value".toByteArray(Charsets.UTF_8))
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val copy = playlist.copy(id = "${playlist.id}-conflict-$hash", name = name)
            val existing = merged[copy.id]
            if (existing == null || existing == copy) {
                merged[copy.id] = copy
                break
            }
            collision++
        }
    }
    return merged.values.toList()
}
