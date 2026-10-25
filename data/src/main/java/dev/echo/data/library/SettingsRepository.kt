package dev.echo.data.library

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.data.spotify.SecureCredentials
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

private val Context.echoSettings by preferencesDataStore(name = "echo-settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.echoSettings
    private val credentials = SecureCredentials(context)
    private val keyChanges = MutableSharedFlow<String>(replay = 1)
    private val keyMutex = Mutex()
    private val clientKey = stringPreferencesKey("spotify_client_id")
    private val historyKey = stringPreferencesKey("search_history")
    private val credentialErrorState = MutableStateFlow<String?>(null)
    val credentialError: StateFlow<String?> = credentialErrorState.asStateFlow()
    private val historyErrorState = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = historyErrorState.asStateFlow()

    val spotifyClientId: Flow<String> = store.data.map { it[clientKey].orEmpty() }
    val searchHistory: Flow<List<String>> = store.data.map {
        try {
            decodeHistory(it[historyKey]).also { historyErrorState.value = null }
        } catch (_: JSONException) {
            historyErrorState.value = "Search history could not be read. Existing history has been preserved."
            emptyList()
        }
    }
    val youtubeApiKey: Flow<String> = flow {
        emit(readCredential({ credentials.get("youtube_api_key") }) { credentialErrorState.value = it })
        emitAll(keyChanges)
    }.flowOn(Dispatchers.IO)

    suspend fun setSpotifyClientId(value: String) {
        store.edit { it[clientKey] = value.trim() }
    }

    suspend fun setYoutubeApiKey(value: String) = withContext(Dispatchers.IO) {
        keyMutex.withLock {
            val trimmed = value.trim()
            credentials.set("youtube_api_key", trimmed.takeIf { it.isNotEmpty() })
            credentialErrorState.value = null
            keyChanges.emit(trimmed)
        }
    }

    suspend fun rememberSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        store.edit { preferences ->
            val previous = try {
                decodeHistory(preferences[historyKey])
            } catch (error: JSONException) {
                throw IllegalStateException("Search history could not be read; no changes were saved", error)
            }
            val entries = recentSearches(previous, trimmed)
            preferences[historyKey] = JSONArray(entries).toString()
        }
    }

    private fun decodeHistory(value: String?): List<String> {
        val array = JSONArray(value ?: "[]")
        return List(array.length()) { array.getString(it) }
    }
}

internal fun recentSearches(previous: List<String>, query: String): List<String> =
    (listOf(query) + previous.filterNot { it.equals(query, ignoreCase = true) }).take(20)

internal fun readCredential(read: () -> String?, report: (String?) -> Unit): String = try {
    read().orEmpty().also { report(null) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    report("YouTube API key could not be read. Unlock the device or re-enter the key in Settings.")
    ""
}
