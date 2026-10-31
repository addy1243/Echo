package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackClient
import dev.echo.data.firebase.ConnectedPlaybackCommand
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import dev.echo.data.firebase.ConnectedPlaybackV2Client
import dev.echo.data.firebase.ConnectedPlaybackV2State
import dev.echo.data.firebase.GroupActionV2
import dev.echo.data.firebase.EchoFirebaseProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.prefs.Preferences

/** Opt-in foreground presence and controls; authentication is delegated to the existing desktop account. */
internal class DesktopConnectedPlayback(
    private val store: DesktopLibrary,
    private val google: DesktopGoogleAccount,
    private val player: DesktopPlayback,
    private val preferences: Preferences = Preferences.userNodeForPackage(DesktopConnectedPlayback::class.java),
) {
    val deviceId: String by lazy {
        store.firebaseDeviceId.also { require(DEVICE_ID.matches(it)) { "Invalid saved device ID" } }
    }

    private val mutableState = MutableStateFlow(ConnectedPlaybackState())
    val state: StateFlow<ConnectedPlaybackState> = mutableState.asStateFlow()
    private val mutableV2State = MutableStateFlow(ConnectedPlaybackV2State())
    val v2State: StateFlow<ConnectedPlaybackV2State> = mutableV2State.asStateFlow()
    private var client: ConnectedPlaybackClient? = null
    private var v2Client: ConnectedPlaybackV2Client? = null

    fun enabled(): Boolean = preferences.getBoolean(ENABLED_KEY, false)

    fun setEnabled(enabled: Boolean) {
        preferences.putBoolean(ENABLED_KEY, enabled)
    }

    suspend fun run() {
        val expectedAccount = google.state.value.email ?: error("Sign in first")
        val connected = ConnectedPlaybackClient(
            databaseUrl = EchoFirebaseProject.realtimeDatabaseUrl,
            deviceId = deviceId,
            label = "Echo for Windows",
            sessionProvider = { google.firebaseSession(EchoFirebaseProject.configuration()) },
            snapshot = { captureSnapshot() },
            executeCommand = { command ->
                check(enabled() && google.state.value.email == expectedAccount) { "Connected playback was disabled or the account changed" }
                player.executeConnectedCommand(command)
            },
            v2Participation = player::isV2Participating,
            controlEnabled = { !player.isV2Participating() },
        )
        val v2 = ConnectedPlaybackV2Client(
            databaseUrl = EchoFirebaseProject.realtimeDatabaseUrl,
            deviceId = deviceId,
            label = "Echo for Windows",
            sessionProvider = { google.firebaseSession(EchoFirebaseProject.configuration()) },
            bridge = player,
        )
        client = connected
        v2Client = v2
        try {
            coroutineScope {
                launch { connected.state.collect { mutableState.value = it } }
                launch { v2.state.collect { mutableV2State.value = it } }
                launch { connected.run() }
                v2.run()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = ConnectedPlaybackState(message = "Connected playback could not connect. Check the Google account and Firebase setup.")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                if (player.isInConnectedGroup()) {
                    runCatching { v2.leaveTogether() }
                    runCatching { player.leaveConnectedGroupPaused() }
                }
                connected.close()
                v2.close()
            }
            if (client === connected) client = null
            if (v2Client === v2) v2Client = null
            if (!enabled()) mutableState.value = ConnectedPlaybackState(message = "Connected playback is off.")
        }
    }

    suspend fun sendCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long = 0L) {
        require(enabled()) { "Turn on connected playback first" }
        require(target.id != deviceId) { "Choose another Echo device" }
        require(client != null) { "Connected playback is not running" }
        client!!.sendCommand(target, action, positionMs)
    }

    suspend fun moveTo(targetDeviceId: String, replacePlaying: Boolean) =
        (v2Client ?: error("Connected playback is not running")).moveTo(targetDeviceId, replacePlaying)

    suspend fun startTogether(targetDeviceIds: List<String>, replacePlaying: Boolean,
        expectedTargetSessions: Map<String, String>) =
        (v2Client ?: error("Connected playback is not running")).startTogether(targetDeviceIds,
            replacePlaying = replacePlaying, expectedTargetSessions = expectedTargetSessions)

    suspend fun restoreLatestDisplacedPaused() {
        check(!mutableV2State.value.pending) { "Finish the current connected playback operation first." }
        check(!mutableV2State.value.reconcilingMove) {
            "This Move is still being reconciled. Wait for its outcome before restoring the previous queue."
        }
        player.restoreLatestDisplacedPaused()
    }

    suspend fun requestGroup(action: GroupActionV2, positionMs: Long = 0L) =
        (v2Client ?: error("Connected playback is not running")).requestGroup(action, positionMs)

    suspend fun stopGracefully() {
        val activeV2 = v2Client
        try {
            if (player.isInConnectedGroup()) runCatching { activeV2?.leaveTogether() }
        } finally {
            runCatching { player.leaveConnectedGroupPaused() }
            stop()
        }
    }

    fun stop() {
        client?.close()
        v2Client?.close()
        client = null
        v2Client = null
        mutableState.value = ConnectedPlaybackState(message = "Connected playback is off.")
        mutableV2State.value = ConnectedPlaybackV2State(message = "Connected playback is off.")
    }

    private fun captureSnapshot(): ConnectedPlayerSnapshot {
        val state = player.state.value
        val track = state.current
        return ConnectedPlayerSnapshot(
            trackId = track?.let(::safeConnectedTrackId),
            title = track?.title,
            artist = track?.artist,
            isPlaying = state.playing,
            positionMs = state.positionMs.coerceAtLeast(0),
            durationMs = (state.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0).coerceAtLeast(0),
            trackRevision = state.occurrence.toString(),
            queueRevision = state.queueRevision.toString(),
        )
    }

    private companion object {
        const val ENABLED_KEY = "connectedPlaybackEnabled"
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

internal fun safeConnectedTrackId(track: Track): String = when {
    track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}")) -> track.id
    track.provider == "spotify" && track.id.matches(Regex("spotify:[A-Za-z0-9]{22}")) -> track.id
    else -> "local:" + MessageDigest.getInstance("SHA-256").digest(track.id.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
