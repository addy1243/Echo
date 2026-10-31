package dev.echo.desktop

import dev.echo.core.Track
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Private queue recovery metadata. Local source URIs and credentials are never written here. */
internal class DesktopPlaybackRecovery(
    private val file: File,
    private val findLocalTrack: (String) -> Track?,
) {
    @Synchronized fun save(key: String, state: DesktopPlaybackState) {
        state.queue.firstOrNull { it.provider !in setOf("local", "youtube") }?.let { unsupported ->
            error("Cannot safely preserve the displaced queue: ${unsupported.title.ifBlank { "a song" }} is not supported on this desktop player")
        }
        val root = readRoot()
        val queue = JSONArray()
        state.queue.forEach { track ->
            queue.put(JSONObject().put("key", safeEngineRecordingId(track)).put("provider", track.provider)
                .put("title", track.title.take(128)).put("artist", track.artist.take(128))
                .put("album", track.album.take(128)).put("durationMs", track.durationMs.coerceAtLeast(0)))
        }
        root.put(key, JSONObject().put("queue", queue).put("index", state.index)
            .put("positionMs", state.positionMs.coerceAtLeast(0)).put("repeat", state.repeatMode.name)
            .put("ended", state.ended))
        if (key.startsWith("move:")) {
            val operationId = key.removePrefix("move:")
            require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid saved Move operation" }
            val stack = moveStack(root).filterNot { it == operationId }.toMutableList()
            require(stack.size < MAX_MOVE_BACKUPS) { "Restore a previous queue before moving another queue to this device" }
            stack += operationId
            root.put(MOVE_STACK_KEY, JSONArray(stack))
        }
        writeRoot(root)
    }

    @Synchronized fun latestMoveOperationId(): String? {
        val root = readRoot()
        return moveStack(root).lastOrNull { id -> root.has("move:$id") }
    }

    @Synchronized fun restore(key: String): DesktopPlaybackState? {
        val payload = readRoot().optJSONObject(key) ?: return null
        val rows = payload.optJSONArray("queue") ?: error("The saved player queue is invalid")
        require(rows.length() in 0..10_000) { "The saved player queue is unavailable" }
        if (rows.length() == 0) return DesktopPlaybackState()
        val tracks = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val keyId = row.getString("key")
            val provider = row.getString("provider")
            when {
                provider == "youtube" && keyId.matches(Regex("youtube:[A-Za-z0-9_-]{11}")) -> Track(
                    keyId, row.getString("title"), row.getString("artist"), row.optString("album"),
                    row.optLong("durationMs").coerceAtLeast(0), provider = "youtube",
                    sourceUri = "https://www.youtube.com/watch?v=${keyId.removePrefix("youtube:")}")
                provider == "local" -> findLocalTrack(keyId)
                    ?.takeIf { it.provider == "local" && !it.sourceUri.isNullOrBlank() }
                    ?: error("A local song in the saved queue is no longer available on this device")
                else -> error("${row.optString("title", "A song")} cannot be restored on this device")
            }
        }
        val index = payload.optInt("index", -1)
        require(index in tracks.indices) { "The saved player position is invalid" }
        return DesktopPlaybackState(queue = tracks, index = index, positionMs = payload.optLong("positionMs").coerceAtLeast(0),
            durationMs = tracks[index].durationMs, repeatMode = runCatching { DesktopRepeatMode.valueOf(payload.getString("repeat")) }
                .getOrDefault(DesktopRepeatMode.OFF), ended = payload.optBoolean("ended"))
    }

    @Synchronized fun remove(key: String) {
        val root = readRoot()
        var changed = root.has(key)
        if (changed) root.remove(key)
        if (key.startsWith("move:")) {
            val id = key.removePrefix("move:")
            val remaining = moveStack(root).filterNot { it == id }
            if (remaining.isEmpty()) root.remove(MOVE_STACK_KEY) else root.put(MOVE_STACK_KEY, JSONArray(remaining))
            changed = true
        }
        if (changed) writeRoot(root)
    }

    private fun moveStack(root: JSONObject): List<String> {
        val array = root.optJSONArray(MOVE_STACK_KEY) ?: return emptyList()
        require(array.length() <= MAX_MOVE_BACKUPS) { "Saved Move recovery index is invalid" }
        return List(array.length()) { array.getString(it) }
            .filter { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) && root.has("move:$it") }
    }

    private fun readRoot(): JSONObject {
        if (!file.isFile) return JSONObject()
        require(file.length() in 1..MAX_BYTES) { "Saved player recovery data is too large" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun writeRoot(root: JSONObject) {
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Saved player recovery data is too large" }
        val parent = file.absoluteFile.parentFile ?: error("Player recovery storage is unavailable")
        check(parent.isDirectory || parent.mkdirs()) { "Player recovery storage is unavailable" }
        val temporary = File(parent, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024L
        const val MAX_MOVE_BACKUPS = 16
        const val MOVE_STACK_KEY = "_moveStack"
    }
}
