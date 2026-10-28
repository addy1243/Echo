package dev.echo.desktop

import dev.echo.data.firebase.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DesktopFirebaseSyncState(val busy: Boolean = false, val message: String? = null,
    val lastSyncedAt: Long? = null, val accountEmail: String? = null)

class DesktopFirebaseSync(private val library: DesktopLibrary, private val accounts: DesktopGoogleAccount) {
    private val mutex = Mutex()
    private val root = library.file.toAbsolutePath().parent.resolve("firebase-sync")
    private val status = MutableStateFlow(DesktopFirebaseSyncState())
    val state: StateFlow<DesktopFirebaseSyncState> = status

    fun automaticEnabled(): Boolean {
        val email = accounts.state.value.email ?: return false
        val file = root.resolve("preferences.json")
        if (!Files.exists(file)) return false
        require(Files.size(file) <= 65_536) { "Firebase preferences are too large" }
        return JSONObject(Files.readString(file)).optBoolean(accountKey(email))
    }

    suspend fun setAutomatic(enabled: Boolean) = mutex.withLock {
        val email = accounts.state.value.email ?: error("Sign in first")
        withContext(Dispatchers.IO) {
            val file = root.resolve("preferences.json")
            val preferences = if (Files.exists(file)) {
                require(Files.size(file) <= 65_536) { "Firebase preferences are too large" }
                JSONObject(Files.readString(file))
            } else JSONObject()
            preferences.put(accountKey(email), enabled)
            writeDesktopBackup(file.toFile(), preferences.toString().also { Files.createDirectories(root) })
        }
    }

    suspend fun synchronize() = mutex.withLock {
        val expectedAccount = accounts.state.value.email ?: error("Sign in with Google first")
        status.value = DesktopFirebaseSyncState(busy = true, message = "Syncing your library…", accountEmail = expectedAccount)
        try {
            val config = EchoFirebaseProject.configuration()
            fun checkAccount() {
                check(accounts.state.value.email == expectedAccount && !accounts.state.value.busy) { "Account changed; sync stopped" }
            }
            checkAccount()
            val session = accounts.firebaseSession(config)
            checkAccount()
            val directory = root.resolve(config.projectId).resolve(session.uid)
            val baselineFile = directory.resolve("baseline.json")
            val activeAccountFile = root.resolve(config.projectId).resolve("last-account.txt")
            val previousAccount = withContext(Dispatchers.IO) {
                if (Files.exists(activeAccountFile) && Files.size(activeAccountFile) <= 256)
                    Files.readString(activeAccountFile) else null
            }
            val baseline = withContext(Dispatchers.IO) {
                val sameAccount = previousAccount == session.uid
                if (sameAccount && Files.exists(baselineFile)) {
                    require(Files.size(baselineFile) <= 8 * 1024 * 1024) { "Firebase baseline is too large" }
                    decodeLibrarySyncState(Files.readString(baselineFile))
                } else null
            }
            val expected = withContext(Dispatchers.IO) { library.exportFirebaseState() }
            withContext(Dispatchers.IO) {
                Files.createDirectories(directory)
                val recovery = directory.resolve("before-first-sync.json")
                if (!Files.exists(recovery)) writeDesktopBackup(recovery.toFile(), encodeLibrarySyncState(expected))
            }
            val merged = synchronizeFirebaseLibrary(FirebaseLibraryCloud(config), session, baseline, expected,
                importDeviceHistory = previousAccount != null && previousAccount != session.uid, checkAccount = ::checkAccount)
            withContext(Dispatchers.IO) {
                checkAccount()
                library.applyFirebaseState(expected, merged, bootstrapAccount = baseline == null)
                writeDesktopBackup(baselineFile.toFile(), encodeLibrarySyncState(merged))
                writeDesktopBackup(activeAccountFile.toFile(), session.uid)
            }
            status.value = DesktopFirebaseSyncState(message = "Library synced: ${merged.playlists.size} playlists, ${merged.favorites.size} favorites and ${merged.history.size} history events.",
                lastSyncedAt = System.currentTimeMillis(), accountEmail = expectedAccount)
        } catch (cancelled: CancellationException) {
            status.value = status.value.copy(busy = false, message = "Sync cancelled. Local data was kept.")
            throw cancelled
        } catch (error: Exception) {
            status.value = status.value.copy(busy = false, message = error.message ?: "Firebase sync failed. Local data was kept.")
        }
    }

    private fun accountKey(email: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${EchoFirebaseProject.configuration().projectId}:$email".toByteArray()).joinToString("") { "%02x".format(it) }
}
