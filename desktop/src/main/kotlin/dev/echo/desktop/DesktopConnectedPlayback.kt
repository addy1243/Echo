package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackClient
import dev.echo.data.firebase.ConnectedPlaybackCommand
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlayerSnapshot
import dev.echo.data.firebase.EchoFirebaseProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    private var client: ConnectedPlaybackClient? = null

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
        )
        client = connected
        try {
            coroutineScope {
                launch { connected.state.collect { mutableState.value = it } }
                connected.run()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = ConnectedPlaybackState(message = "Connected playback could not connect. Check the Google account and Firebase setup.")
        } finally {
            connected.close()
            if (client === connected) client = null
            if (!enabled()) mutableState.value = ConnectedPlaybackState(message = "Connected playback is off.")
        }
    }

    suspend fun sendCommand(target: ConnectedDevice, action: ConnectedPlaybackAction, positionMs: Long = 0L) {
        require(enabled()) { "Turn on connected playback first" }
        require(target.id != deviceId) { "Choose another Echo device" }
        require(client != null) { "Connected playback is not running" }
        client!!.sendCommand(target, action, positionMs)
    }

    fun stop() {
        client?.close()
        client = null
        mutableState.value = ConnectedPlaybackState(message = "Connected playback is off.")
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
