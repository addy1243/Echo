package dev.echo.data.provider

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.core.AudioSourceProvider
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import dev.echo.data.offline.OfflineRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceResolver @Inject constructor(@ApplicationContext private val context: Context, youtube: YouTubeProvider,
    private val offline: OfflineRepository) {
    private val preferences = context.getSharedPreferences("sources", Context.MODE_PRIVATE)
    private val providers: List<AudioSourceProvider> = listOf(LocalAudioProvider(context), youtube, CommonsProvider())
    init {
        if (!preferences.getBoolean("youtube_added", false)) {
            val previous = preferences.getString("priority", null)
            val edit = preferences.edit().putBoolean("youtube_added", true)
            upgradedSourcePriority(previous)?.let { edit.putString("priority", it) }
            edit.apply()
        }
    }
    var priority: List<String>
        get() = preferences.getString("priority", "local,youtube,commons")!!.split(',').filter { it.isNotBlank() }
        set(value) {
            require(value.distinct().size == value.size && value.all { id -> providers.any { it.id == id } })
            preferences.edit().putString("priority", value.joinToString(",")).apply()
        }

    suspend fun resolve(track: Track): PlayableSource? {
        offline.localSource(track)?.let { return it }
        if (offline.settings.value.offlineOnly && track.provider != "local") return null
        var failure: Exception? = null
        for (id in priority) {
            val provider = providers.firstOrNull { it.id == id } ?: continue
            try {
                if (!provider.isAvailable()) continue
                val source = provider.resolve(track) ?: continue
                val expiresAt = source.expiresAt
                if (expiresAt == null || expiresAt > System.currentTimeMillis()) return source
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
        return null
    }
}

internal fun upgradedSourcePriority(previous: String?): String? =
    if (previous.isNullOrBlank()) previous else (previous.split(',') + "youtube").distinct().joinToString(",")

class LocalAudioProvider(private val context: Context) : AudioSourceProvider {
    override val id = "local"
    override suspend fun isAvailable() = true
    override suspend fun resolve(track: Track): PlayableSource? {
        if (track.provider != id) return null
        val uri = track.sourceUri?.let(Uri::parse) ?: return null
        if (uri.scheme !in setOf("content", "file", "android.resource")) return null
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { return PlayableSource(uri.toString()) }
        return null
    }
}
