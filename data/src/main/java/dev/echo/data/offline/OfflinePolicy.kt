package dev.echo.data.offline

import java.security.MessageDigest
import java.io.File

internal const val MAX_SONG_BYTES = 100L * 1024 * 1024
internal const val DOWNLOAD_BUDGET = 2L * 1024 * 1024 * 1024
internal const val CACHE_BUDGET = 256L * 1024 * 1024
internal const val FREE_SPACE_RESERVE = 100L * 1024 * 1024

fun downloadPercent(receivedBytes: Long, totalBytes: Long?): Int? = totalBytes?.takeIf { it > 0 }?.let {
    (receivedBytes.coerceAtLeast(0).toDouble() / it * 100).toInt().coerceIn(0, 100)
}

internal fun offlineFileName(id: String): String = MessageDigest.getInstance("SHA-256")
    .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".audio"

internal fun audioDigest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun validOfflineFile(file: File, bytes: Long, digest: String): Boolean =
    file.isFile && bytes > 0 && file.length() == bytes && digest.isNotEmpty() && audioDigest(file) == digest

internal fun shouldAutoCache(enabled: Boolean, offline: Boolean, unmetered: Boolean, qualifiedPlays: Int): Boolean =
    enabled && !offline && unmetered && qualifiedPlays >= 3

internal fun validateDownloadSize(bytes: Long, declared: Long, available: Long) {
    require(bytes in 1..MAX_SONG_BYTES) { "The recording is empty or exceeds the 100 MiB song limit" }
    require(declared < 0 || bytes == declared) { "Download was interrupted; retry to save the complete recording" }
    require(available >= FREE_SPACE_RESERVE) { "Not enough free device storage" }
}
