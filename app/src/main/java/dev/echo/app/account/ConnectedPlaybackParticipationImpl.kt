package dev.echo.app.account

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.echo.data.firebase.ConnectedPlaybackParticipation
import dev.echo.data.firebase.ConnectedPlaybackState
import dev.echo.data.firebase.ConnectedPlaybackV2State
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ConnectedPlaybackParticipationImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val accounts: GoogleAccounts,
    private val firebase: FirebaseAccountSync,
) : ConnectedPlaybackParticipation {
    private val prefs = context.getSharedPreferences("connected-playback", Context.MODE_PRIVATE)
    override val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    override val deviceId: String = prefs.getString("device-id", null)
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device-id", it).apply() }
    override val accountEmail: String? get() = accounts.email.value
    private val mutableLegacy = MutableStateFlow(ConnectedPlaybackState())
    override val legacyState = mutableLegacy.asStateFlow()
    private val mutableV2 = MutableStateFlow(ConnectedPlaybackV2State())
    override val v2State = mutableV2.asStateFlow()
    private val mutableDisplacedMoveOperationId = MutableStateFlow<String?>(null)
    override val displacedMoveOperationId = mutableDisplacedMoveOperationId.asStateFlow()
    @Volatile private var attachedBridge: dev.echo.core.connected.NativePlaybackBridgeV2? = null
    override val nativeBridge get() = attachedBridge

    override fun label(): String = "Android · ${android.os.Build.MODEL.orEmpty().take(32)}"
    override suspend fun existingSession(expectedEmail: String) = firebase.existingSession(expectedEmail)
    override fun updateLegacyState(state: ConnectedPlaybackState) { mutableLegacy.value = state }
    override fun updateV2State(state: ConnectedPlaybackV2State) { mutableV2.value = state }
    override fun updateDisplacedMoveOperationId(operationId: String?) { mutableDisplacedMoveOperationId.value = operationId }
    override fun attachNativeBridge(bridge: dev.echo.core.connected.NativePlaybackBridgeV2) { attachedBridge = bridge }
    override fun detachNativeBridge(bridge: dev.echo.core.connected.NativePlaybackBridgeV2) {
        if (attachedBridge === bridge) attachedBridge = null
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectedPlaybackParticipationModule {
    @Binds @Singleton abstract fun bindConnectedPlaybackParticipation(
        implementation: ConnectedPlaybackParticipationImpl,
    ): ConnectedPlaybackParticipation
}
