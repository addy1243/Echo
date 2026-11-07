package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.EngineFence
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.TransferQueue
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackClient
import dev.echo.data.firebase.ConnectedPlaybackCommand
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import dev.echo.data.firebase.ConnectedPlaybackV2Client
import dev.echo.data.firebase.ConnectedPlaybackV2State
import dev.echo.data.firebase.GroupActionV2
import dev.echo.data.firebase.GroupQueueOperationV2
import dev.echo.data.firebase.QueueOperationV2
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

internal interface DesktopConnectedPlaybackBackend {
    val deviceId: String
    val state: StateFlow<ConnectedPlaybackState>
    val v2State: StateFlow<ConnectedPlaybackV2State>
    fun enabled(): Boolean
    suspend fun run()
    suspend fun sendCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long = 0L)
    suspend fun moveTo(targetDeviceId: String, replacePlaying: Boolean)
    suspend fun startTogether(targetDeviceIds: List<String>, replacePlaying: Boolean,
        expectedTargetSessions: Map<String, String>)
    suspend fun playQueueOn(targetDeviceId: String, expectedTargetSessionId: String,
        expectedTargetFence: EngineFence, queue: TransferQueue): QueueOperationV2
    suspend fun readRemoteQueue(targetDeviceId: String, expectedTargetSessionId: String,
        expectedTargetFence: EngineFence): EngineCapture
    suspend fun replaceGroupQueue(expectedGroup: GroupRef, expectedTimelineSequence: Long,
        queue: TransferQueue): GroupQueueOperationV2
    suspend fun restoreLatestDisplacedPaused()
    suspend fun requestGroup(action: GroupActionV2, positionMs: Long = 0L,
        expectedGroup: GroupRef? = null, expectedTimelineSequence: Long? = null,
        expectedOccurrenceId: String? = null)
    suspend fun stopGracefully()
    fun stop()
}

/** Foreground presence and controls while signed in; authentication is delegated to the existing desktop account. */
internal class DesktopConnectedPlayback(
    private val store: DesktopLibrary,
    private val google: DesktopGoogleAccount,
    private val player: DesktopPlayback,
    @Suppress("UNUSED_PARAMETER") preferences: Preferences = Preferences.userNodeForPackage(DesktopConnectedPlayback::class.java),
) : DesktopConnectedPlaybackBackend {
    override
    val deviceId: String by lazy {
        store.firebaseDeviceId.also { require(DEVICE_ID.matches(it)) { "Invalid saved device ID" } }
    }

    private val mutableState = MutableStateFlow(ConnectedPlaybackState())
    override val state: StateFlow<ConnectedPlaybackState> = mutableState.asStateFlow()
    private val mutableV2State = MutableStateFlow(ConnectedPlaybackV2State())
    override val v2State: StateFlow<ConnectedPlaybackV2State> = mutableV2State.asStateFlow()
    private var client: ConnectedPlaybackClient? = null
    private var v2Client: ConnectedPlaybackV2Client? = null
    @Volatile private var testOverride: Boolean? = null

    override fun enabled(): Boolean = testOverride ?: (google.state.value.email != null && EchoFirebaseProject.configured)

    /** Compatibility seam for existing native/JVM fixtures; never persisted as a user preference. */
    fun setEnabled(enabled: Boolean) { testOverride = enabled }

    override suspend fun run() {
        val expectedAccount = google.state.value.email ?: error("Sign in first")
        val connected = ConnectedPlaybackClient(
            databaseUrl = EchoFirebaseProject.realtimeDatabaseUrl,
            deviceId = deviceId,
            label = "Echo for Windows",
            sessionProvider = {
                check(google.state.value.email == expectedAccount) { "Account changed; reconnect connected playback" }
                google.firebaseSession(EchoFirebaseProject.configuration()).also {
                    check(google.state.value.email == expectedAccount && it.email.equals(expectedAccount, true)) {
                        "Account changed; reconnect connected playback"
                    }
                }
            },
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
            sessionProvider = {
                check(google.state.value.email == expectedAccount) { "Account changed; reconnect connected playback" }
                google.firebaseSession(EchoFirebaseProject.configuration()).also {
                    check(google.state.value.email == expectedAccount && it.email.equals(expectedAccount, true)) {
                        "Account changed; reconnect connected playback"
                    }
                }
            },
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

    override suspend fun sendCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long) {
        require(enabled()) { "Sign in to use connected playback" }
        require(target.id != deviceId) { "Choose another Echo device" }
        require(client != null) { "Connected playback is not running" }
        client!!.sendCommand(target, action, positionMs)
    }

    override suspend fun moveTo(targetDeviceId: String, replacePlaying: Boolean) =
        (v2Client ?: error("Connected playback is not running")).moveTo(targetDeviceId, replacePlaying)

    override suspend fun startTogether(targetDeviceIds: List<String>, replacePlaying: Boolean,
        expectedTargetSessions: Map<String, String>) =
        (v2Client ?: error("Connected playback is not running")).startTogether(targetDeviceIds,
            replacePlaying = replacePlaying, expectedTargetSessions = expectedTargetSessions)

    override suspend fun playQueueOn(targetDeviceId: String, expectedTargetSessionId: String,
        expectedTargetFence: EngineFence, queue: TransferQueue): QueueOperationV2 =
        (v2Client ?: error("Connected playback is not running")).playQueueOn(
            targetDeviceId, expectedTargetSessionId, expectedTargetFence, queue)

    override suspend fun readRemoteQueue(targetDeviceId: String, expectedTargetSessionId: String,
        expectedTargetFence: EngineFence): EngineCapture =
        (v2Client ?: error("Connected playback is not running")).readRemoteQueue(
            targetDeviceId, expectedTargetSessionId, expectedTargetFence)

    override suspend fun replaceGroupQueue(expectedGroup: GroupRef, expectedTimelineSequence: Long,
        queue: TransferQueue): GroupQueueOperationV2 =
        (v2Client ?: error("Connected playback is not running")).replaceGroupQueue(
            expectedGroup, expectedTimelineSequence, queue)

    override suspend fun restoreLatestDisplacedPaused() {
        check(!mutableV2State.value.pending) { "Finish the current connected playback operation first." }
        check(!mutableV2State.value.reconcilingMove) {
            "This Move is still being reconciled. Wait for its outcome before restoring the previous queue."
        }
        player.restoreLatestDisplacedPaused()
    }

    override suspend fun requestGroup(action: GroupActionV2, positionMs: Long,
        expectedGroup: GroupRef?, expectedTimelineSequence: Long?, expectedOccurrenceId: String?) =
        (v2Client ?: error("Connected playback is not running")).requestGroup(
            action, positionMs, expectedGroup, expectedTimelineSequence, expectedOccurrenceId)

    override suspend fun stopGracefully() {
        val activeV2 = v2Client
        try {
            if (player.isInConnectedGroup()) runCatching { activeV2?.leaveTogether() }
        } finally {
            runCatching { player.leaveConnectedGroupPaused() }
            stop()
        }
    }

    override fun stop() {
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
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

internal fun safeConnectedTrackId(track: Track): String = when {
    track.provider == "youtube" && track.id.matches(Regex("youtube:[A-Za-z0-9_-]{11}")) -> track.id
    track.provider == "spotify" && track.id.matches(Regex("spotify:[A-Za-z0-9]{22}")) -> track.id
    else -> "local:" + MessageDigest.getInstance("SHA-256").digest(track.id.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
