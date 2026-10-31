package dev.echo.data.firebase

import dev.echo.core.connected.NativePlaybackBridgeV2
import kotlinx.coroutines.flow.StateFlow

/** App supplied identity/session access; playback remains unaware of app/account implementations. */
interface ConnectedPlaybackParticipation {
    val enabled: Boolean
    val deviceId: String
    val accountEmail: String?
    val legacyState: StateFlow<ConnectedPlaybackState>
    val v2State: StateFlow<ConnectedPlaybackV2State>
    val displacedMoveOperationId: StateFlow<String?>
    val nativeBridge: NativePlaybackBridgeV2?
    fun attachNativeBridge(bridge: NativePlaybackBridgeV2)
    fun detachNativeBridge(bridge: NativePlaybackBridgeV2)
    fun label(): String
    suspend fun existingSession(expectedEmail: String): FirebaseSession
    fun updateLegacyState(state: ConnectedPlaybackState)
    fun updateV2State(state: ConnectedPlaybackV2State)
    fun updateDisplacedMoveOperationId(operationId: String?)
}
