package dev.echo.data.spotify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

data class StoredSpotifyArchive(val sha256: String, val encryptedBytes: Long, val modifiedAt: Long)

/** Retains user-confirmed Spotify export archives locally as authenticated ciphertext. */
class SpotifyArchiveStore @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun list(): List<StoredSpotifyArchive> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val directory = File(context.noBackupFilesDir, DIRECTORY)
            if (!directory.exists()) return@synchronized emptyList()
            (directory.listFiles() ?: error("Could not read Spotify archive storage"))
                .mapNotNull { file ->
                    val hash = file.name.removeSuffix(".spz")
                    if (!file.isFile || !HASH_PATTERN.matches(hash) || !file.name.endsWith(".spz") ||
                        file.length() !in 28..MAX_ENCRYPTED_ARCHIVE_BYTES) null
                    else StoredSpotifyArchive(hash, file.length(), file.lastModified())
                }.sortedWith(compareByDescending<StoredSpotifyArchive> { it.modifiedAt }.thenBy { it.sha256 })
        }
    }

    suspend fun read(hash: String): ByteArray {
        require(HASH_PATTERN.matches(hash)) { "Invalid Spotify archive identifier" }
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val file = File(File(context.noBackupFilesDir, DIRECTORY), "$hash.spz")
                require(file.isFile && file.length() in 28..MAX_ENCRYPTED_ARCHIVE_BYTES) {
                    "Spotify archive is missing or outside the supported size limit"
                }
                val stored = file.readBytes()
                require(stored.size in 28..MAX_ENCRYPTED_ARCHIVE_BYTES.toInt()) {
                    "Spotify archive is missing or outside the supported size limit"
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(0, 12)))
                val bytes = cipher.doFinal(stored, 12, stored.size - 12)
                require(bytes.isNotEmpty() && bytes.size <= MAX_ARCHIVE_BYTES && sha256(bytes) == hash) {
                    "Spotify archive failed integrity verification"
                }
                bytes
            }
        }
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val directory = File(context.noBackupFilesDir, DIRECTORY)
            if (directory.exists()) {
                val files = directory.listFiles() ?: error("Could not read Spotify archive storage")
                files.filter { it.name.matches(Regex("[0-9a-f]{64}\\.spz")) }.forEach {
                    check(it.delete()) { "Some Spotify archives could not be deleted" }
                }
            }
        }
    }

    suspend fun save(bytes: ByteArray): Unit {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ARCHIVE_BYTES) {
            "Spotify export ZIP must be 50 MB or smaller"
        }
        withContext(Dispatchers.IO) {
            val name = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) } + ".spz"
            synchronized(lock) {
                val directory = File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }
                check(directory.isDirectory) { "Could not create private Spotify archive directory" }
                val target = File(directory, name)
                if (target.exists()) {
                    val verified = runCatching {
                        require(target.length() in 29..50_100_000)
                        val stored = target.readBytes()
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(0, 12)))
                        cipher.doFinal(stored, 12, stored.size - 12).contentEquals(bytes)
                    }.getOrDefault(false)
                    check(verified) { "The existing Spotify archive could not be verified; it was kept unchanged" }
                    return@synchronized
                }

                val temporary = File.createTempFile("archive-", ".tmp", directory)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key())
                    val ciphertext = cipher.doFinal(bytes)
                    val used = directory.listFiles()?.filter { it.name.endsWith(".spz") }?.sumOf { it.length() }
                        ?: error("Could not read Spotify archive storage")
                    require(used + cipher.iv.size + ciphertext.size <= 250_000_000L) { "Spotify archive storage limit reached; existing exports were kept" }
                    java.io.FileOutputStream(temporary).use { output ->
                        output.write(cipher.iv)
                        output.write(ciphertext)
                        output.fd.sync()
                    }
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val DIRECTORY = "spotify-archives"
        const val KEY_ALIAS = "echo.spotify.archive.v1"
        const val MAX_ARCHIVE_BYTES = 50_000_000
        const val MAX_ENCRYPTED_ARCHIVE_BYTES = MAX_ARCHIVE_BYTES + 28L
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        val lock = Any()
        val keyLock = Any()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
