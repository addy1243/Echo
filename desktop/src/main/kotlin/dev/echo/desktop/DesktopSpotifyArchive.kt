package dev.echo.desktop

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal data class DesktopSpotifyArchive(val id: String, val modifiedAt: Long)

internal fun desktopSpotifyArchiveDirectory(dataDirectory: Path): Path = dataDirectory.resolve("spotify-archives")

internal fun listDesktopSpotifyArchives(directory: Path): List<DesktopSpotifyArchive> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { files -> files
        .filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.dpapi")) }
        .map { path -> DesktopSpotifyArchive(path.fileName.toString().removeSuffix(".dpapi"), Files.getLastModifiedTime(path).toMillis()) }
        .sorted(compareByDescending(DesktopSpotifyArchive::modifiedAt))
        .toList()
    }
}

internal fun readDesktopSpotifyArchive(directory: Path, id: String): ByteArray {
    require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid saved Spotify archive" }
    val path = directory.resolve("$id.dpapi")
    require(Files.isRegularFile(path) && Files.size(path) in 1..50_100_000) { "Saved Spotify archive is missing or too large" }
    val bytes = Crypt32Util.cryptUnprotectData(Files.readAllBytes(path))
    val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(actual == id && bytes.size <= 50_000_000) { "Saved Spotify archive failed integrity verification" }
    return bytes
}

/** Copies previously stored archives into local app data and keeps the originals intact. */
internal fun migrateDesktopSpotifyArchives(legacyDirectory: Path, localDirectory: Path) {
    if (legacyDirectory.toAbsolutePath().normalize() == localDirectory.toAbsolutePath().normalize() ||
        !Files.isDirectory(legacyDirectory)) return
    val legacyArchives = listDesktopSpotifyArchives(legacyDirectory)
    if (legacyArchives.isEmpty()) return
    Files.createDirectories(localDirectory)
    legacyArchives.forEach { archive ->
        val target = localDirectory.resolve("${archive.id}.dpapi")
        if (Files.exists(target)) readDesktopSpotifyArchive(localDirectory, archive.id)
        else saveDesktopSpotifyArchive(readDesktopSpotifyArchive(legacyDirectory, archive.id), localDirectory)
    }
}

/** Stores the selected archive locally for this Windows user; never part of library sync. */
internal fun saveDesktopSpotifyArchive(bytes: ByteArray, directory: Path) {
    require(bytes.isNotEmpty() && bytes.size <= 50_000_000) { "Spotify ZIP must be 50 MB or smaller" }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    Files.createDirectories(directory)
    val target = directory.resolve("$digest.dpapi")
    if (Files.exists(target)) {
        check(Files.size(target) <= 50_100_000 && runCatching { Crypt32Util.cryptUnprotectData(Files.readAllBytes(target)).contentEquals(bytes) }.getOrDefault(false)) {
            "The existing Spotify archive could not be verified; it was kept unchanged"
        }
        return
    }
    val encrypted = Crypt32Util.cryptProtectData(bytes)
    val used = Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".dpapi") }.mapToLong(Files::size).sum() }
    require(used + encrypted.size <= 250_000_000L) { "Spotify archive storage limit reached; existing exports were kept" }
    val temporary = Files.createTempFile(directory, "spotify-archive-", ".tmp")
    try {
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(encrypted)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target) }
    } finally { Files.deleteIfExists(temporary) }
}

internal fun deleteDesktopSpotifyArchives(vararg directories: Path) {
    directories.map { it.toAbsolutePath().normalize() }.distinct().forEach { directory ->
        if (!Files.exists(directory)) return@forEach
        Files.list(directory).use { files -> files.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.dpapi")) }
            .forEach { Files.delete(it) } }
    }
}
