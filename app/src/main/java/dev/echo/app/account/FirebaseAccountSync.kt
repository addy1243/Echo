package dev.echo.app.account

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.data.firebase.EchoFirebaseProject
import dev.echo.data.firebase.FirebaseAuthRest
import dev.echo.data.firebase.FirebaseLibraryCloud
import dev.echo.data.firebase.FirebaseSession
import dev.echo.data.firebase.decodeLibrarySyncState
import dev.echo.data.firebase.encodeLibrarySyncState
import dev.echo.data.firebase.synchronizeFirebaseLibrary
import dev.echo.data.library.LibraryRepository
import dev.echo.data.spotify.SecureCredentials
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class FirebaseAccountSync @Inject constructor(
    @ApplicationContext context: Context,
    private val library: LibraryRepository,
    private val accounts: GoogleAccounts,
) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val accountLock = Any()
    private val credentials = SecureCredentials(context, "firebase-credentials")
    private val prefs = context.getSharedPreferences("firebase-sync-status", Context.MODE_PRIVATE)
    private val root = File(context.noBackupFilesDir, "firebase-sync")
    private val revision = MutableStateFlow(0)
    val lastSynced: Flow<Long?> = combine(accounts.email, revision) { email, _ ->
        val project = runCatching { EchoFirebaseProject.configuration() }.getOrNull()
        if (email == null || project == null) null else {
            val user = prefs.getString("user:${accountKey(project.projectId, email)}", null)
            user?.let { prefs.getLong("last:$it", 0).takeIf { time -> time > 0 } }
        }
    }

    suspend fun synchronize(googleAccessToken: String, expectedAccount: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ticket = generation.get()
            val googleSubject = accounts.subject?.takeIf { it.isNotBlank() } ?: error("Choose your Google account again before syncing")
            fun checkAccount() {
                check(generation.get() == ticket && accounts.email.value == expectedAccount && accounts.subject == googleSubject) {
                    "Account changed; sync stopped"
                }
            }
            currentCoroutineContext().ensureActive()
            checkAccount()
            val config = EchoFirebaseProject.configuration()
            val cacheKey = accountKey(config.projectId, expectedAccount)
            val auth = FirebaseAuthRest(config)
            val cached = credentials.get(cacheKey)?.let(::readSession)?.takeIf { it.second == googleSubject }?.first
            val session = when {
                cached == null -> auth.signInGoogle(googleAccessToken, expectedAccount)
                cached.expiresAt <= System.currentTimeMillis() + 60_000 -> auth.refresh(cached)
                else -> cached
            }
            checkAccount()
            check(session.email.equals(expectedAccount, true)) { "Firebase account changed; sign out and reconnect" }
            synchronized(accountLock) {
                checkAccount()
                credentials.set(cacheKey, sessionJson(session, googleSubject))
            }
            val projectDir = File(root, config.projectId)
            val accountDir = File(projectDir, session.uid)
            check(accountDir.isDirectory || accountDir.mkdirs()) { "Could not prepare private sync storage" }
            val deviceId = library.firebaseDeviceId()
            val local = library.exportFirebaseState(deviceId)
            val initial = File(accountDir, "initial-library.json")
            if (!initial.exists()) writeFirebaseFile(initial, encodeLibrarySyncState(local))
            val baselineFile = File(accountDir, "baseline.json")
            val lastAccountFile = File(projectDir, "last-account.txt")
            val priorUid = lastAccountFile.takeIf { it.isFile && it.length() <= 128 }?.readText()
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            val sameAccount = priorUid == session.uid
            val baseline = if (sameAccount && baselineFile.exists()) {
                require(baselineFile.length() <= 8 * 1024 * 1024) { "Saved sync baseline is too large; local data was kept" }
                try { decodeLibrarySyncState(baselineFile.readText()) }
                catch (_: Exception) { error("Saved sync baseline could not be read; the original file and local library were kept") }
            } else null
            val merged = synchronizeFirebaseLibrary(FirebaseLibraryCloud(config), session, baseline, local,
                importDeviceHistory = priorUid != null && priorUid != session.uid, checkAccount = ::checkAccount)
            currentCoroutineContext().ensureActive()
            checkAccount()
            library.applyFirebaseState(local, merged, deviceId, bootstrapAccount = baseline == null)
            checkAccount()
            writeFirebaseFile(baselineFile, encodeLibrarySyncState(merged))
            writeFirebaseFile(lastAccountFile, session.uid)
            val userKey = accountKey(config.projectId, session.uid)
            check(prefs.edit().putString("user:$cacheKey", userKey).putLong("last:$userKey", System.currentTimeMillis()).commit()) {
                "Library synced but its status could not be saved"
            }
            revision.value++
            "Playlists, favorites and listening history synced with Firebase."
        }
    }

    fun signOut(expectedEmail: String?) {
        synchronized(accountLock) {
            generation.incrementAndGet()
            val config = runCatching { EchoFirebaseProject.configuration() }.getOrNull()
            if (config != null) expectedEmail?.let { credentials.set(accountKey(config.projectId, it), null) }
        }
        revision.value++
    }
}

private fun accountKey(project: String, account: String) = MessageDigest.getInstance("SHA-256")
    .digest("$project:$account".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun sessionJson(session: FirebaseSession, googleSubject: String) = JSONObject().put("uid", session.uid).put("email", session.email)
    .put("idToken", session.idToken).put("refreshToken", session.refreshToken).put("expiresAt", session.expiresAt)
    .put("googleSubject", googleSubject).toString()

private fun readSession(json: String): Pair<FirebaseSession, String> = try {
    require(json.length <= 128 * 1024)
    val value = JSONObject(json)
    val subject = value.getString("googleSubject")
    require(subject.length in 1..256 && subject.none(Char::isISOControl))
    FirebaseSession(value.getString("uid"), value.getString("email"), value.getString("idToken"),
        value.getString("refreshToken"), value.getLong("expiresAt")) to subject
} catch (_: Exception) { error("Saved Firebase credentials could not be read. Sign out and reconnect.") }

private fun writeFirebaseFile(file: File, value: String) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try { stream.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
    catch (error: Exception) { atomic.failWrite(stream); throw error }
}
