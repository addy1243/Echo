package dev.echo.playback

import android.app.PendingIntent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.data.provider.SourceResolver
import dev.echo.data.provider.YouTubeRequestException
import javax.inject.Inject
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray

@AndroidEntryPoint
@UnstableApi
class EchoPlaybackService : MediaLibraryService() {
    @Inject lateinit var repository: LibraryRepository
    @Inject lateinit var resolver: SourceResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val preferences by lazy { getSharedPreferences("echo_queue", MODE_PRIVATE) }
    private var queueWasSet = false
    private val subscriptions = mutableMapOf<String, MutableSet<MediaSession.ControllerInfo>>()
    private var recoveryJob: Job? = null
    private var recoveryAttempts = 0
    private var lastPlaybackId: String? = null

    override fun onCreate() {
        super.onCreate()
        val defaultFactory = DefaultMediaSourceFactory(this)
        val sources = object : MediaSource.Factory by defaultFactory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val deferredTrack = mediaItem.localConfiguration?.tag as? Track
                if (deferredTrack != null) {
                    val cache = PlaybackSourceCache {
                        runBlocking { resolver.resolve(deferredTrack) }
                    }
                    val data = ResolvingDataSource.Factory(DefaultDataSource.Factory(this@EchoPlaybackService)) { spec ->
                        try {
                            val source = cache.get(System.currentTimeMillis())
                            if (Uri.parse(source.uri).scheme != "https") throw IOException("Invalid online source")
                            spec.withUri(Uri.parse(source.uri)).withAdditionalHeaders(source.headers)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            val message = (error as? YouTubeRequestException)?.message
                                ?: "Online audio could not be loaded. Check your connection, retry, or update Echo if this keeps happening."
                            scope.launch { session.sendError(SessionError(SessionError.ERROR_IO, message)) }
                            // Provider exceptions may contain signed URLs; expose only an actionable message.
                            throw SourceResolutionException(message)
                        }
                    }
                    return DefaultMediaSourceFactory(this@EchoPlaybackService)
                        .setDataSourceFactory(data).setLoadErrorHandlingPolicy(EchoLoadErrorPolicy())
                        .createMediaSource(mediaItem)
                }
                val source = mediaItem.localConfiguration?.tag as? PlayableSource
                    ?: error("Media must be resolved by the library service")
                val http = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(source.headers)
                return DefaultMediaSourceFactory(this@EchoPlaybackService)
                    .setDataSourceFactory(DefaultDataSource.Factory(this@EchoPlaybackService, http))
                    .setLoadErrorHandlingPolicy(EchoLoadErrorPolicy())
                    .createMediaSource(mediaItem)
            }
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val builder = MediaLibrarySession.Builder(this, player, LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != lastPlaybackId) {
                    recoveryAttempts = 0
                    recoveryJob?.cancel()
                    lastPlaybackId = mediaItem?.mediaId
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) recoveryAttempts = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.isSourceResolutionFailure()) {
                    recoveryJob?.cancel()
                    return
                }
                val http = error.httpResponseError()
                val refreshExpiredSource = shouldRefreshOnlineSource(
                    (player.currentMediaItem?.localConfiguration?.tag as? Track)?.provider,
                    http?.responseCode, recoveryAttempts,
                )
                if (http != null && !isTransientHttpStatus(http.responseCode) && !refreshExpiredSource) {
                    recoveryJob?.cancel()
                    val retryAfter = http.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }
                        ?.value?.firstOrNull()?.take(100)
                    val message = when (http.responseCode) {
                        429 -> "The audio source rate limited playback (HTTP 429). " +
                            (retryAfter?.let { "Wait before retrying. Retry-After: $it." } ?: "Wait before retrying.")
                        401 -> "The audio source requires authentication (HTTP 401). Reconnect the source before retrying."
                        403 -> "The audio source denied access (HTTP 403). Check source permissions before retrying."
                        else -> "The audio source rejected playback (HTTP ${http.responseCode}). No automatic retry."
                    }
                    session.sendError(SessionError(SessionError.ERROR_IO, message))
                    return
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && http == null) {
                    recoveryJob?.cancel()
                    return
                }
                if (error.errorCode !in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED) || recoveryAttempts >= 2) return
                val failedItem = player.currentMediaItem ?: return
                val index = player.currentMediaItemIndex
                val position = player.currentPosition
                val attempt = ++recoveryAttempts
                recoveryJob?.cancel()
                recoveryJob = scope.launch {
                    delay(attempt * 1_000L)
                    val track = repository.tracks.first().find { it.id == failedItem.mediaId } ?: return@launch
                    val replacement = resolve(track) ?: return@launch
                    if (player.currentMediaItem != failedItem || player.currentMediaItemIndex != index) return@launch
                    // A fresh MediaSource also drops its cached signed URL after the one allowed 403 refresh.
                    player.replaceMediaItem(index, replacement)
                    player.seekTo(index, position)
                    player.prepare()
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueWasSet = true
                if (queueWasSet) persistQueue()
                if (player.isPlaying && (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                        events.contains(Player.EVENT_IS_PLAYING_CHANGED))) {
                    player.currentMediaItem?.mediaId?.let { id ->
                        scope.launch {
                            try { repository.recordPlayed(id) } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                Log.w("EchoPlayback", "Could not record playback history")
                            }
                        }
                    }
                }
            }
        })
        scope.launch {
            while (true) {
                delay(5_000)
                if (queueWasSet && player.isPlaying) persistQueue()
            }
        }
        scope.launch {
            combine(repository.tracks, repository.playlists, repository.favorites, repository.recent) { _, _, _, _ -> Unit }
                .collect {
                    for (parent in subscriptions.keys.toList()) {
                        session.notifyChildrenChanged(parent, children(parent).orEmpty().size, null)
                    }
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        if (::player.isInitialized) {
            if (queueWasSet) persistQueue()
            if (::session.isInitialized) session.release()
            player.release()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun persistQueue() {
        val ids = JSONArray()
        repeat(player.mediaItemCount) { ids.put(player.getMediaItemAt(it).mediaId) }
        preferences.edit().putString("ids", ids.toString())
            .putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putInt("repeat", player.repeatMode)
            .putBoolean("shuffle", player.shuffleModeEnabled).apply()
    }

    private suspend fun resumeQueue(): MediaSession.MediaItemsWithStartPosition {
        val ids = try {
            val stored = JSONArray(preferences.getString("ids", "[]"))
            List(stored.length()) { stored.getString(it) }
        } catch (_: org.json.JSONException) { emptyList() }
        val tracks = repository.tracks.first().associateBy(Track::id)
        val resolved = mutableMapOf<String, MediaItem>()
        for (id in ids.distinct()) {
            val track = tracks[id] ?: continue
            resolve(track)?.let { resolved[id] = it }
        }
        val restored = restoreQueue(ids, preferences.getInt("index", 0),
            preferences.getLong("position", 0), resolved.keys)
            ?: throw IllegalStateException("No playable saved queue. Select a song from the library.")
        player.repeatMode = preferences.getInt("repeat", Player.REPEAT_MODE_OFF)
            .takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL } ?: Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = preferences.getBoolean("shuffle", false)
        return MediaSession.MediaItemsWithStartPosition(restored.ids.map { resolved.getValue(it) },
            restored.index, restored.positionMs)
    }

    private suspend fun resolve(track: Track): MediaItem? {
        if (track.provider == "youtube") {
            val uri = track.sourceUri ?: return null
            // Force a fresh source on retries; Media3 otherwise reuses the same watch locator and signed URL cache.
            return track.toMediaItem().buildUpon().setUri(uri).setTag(track)
                .setCustomCacheKey(UUID.randomUUID().toString()).build()
        }
        val source = try { withContext(Dispatchers.IO) { resolver.resolve(track) } } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        } ?: return null
        val scheme = Uri.parse(source.uri).scheme
        if (scheme !in setOf("content", "file", "android.resource", "https")) return null
        if (source.expiresAt?.let { it <= System.currentTimeMillis() } == true) return null
        val item = track.toMediaItem()
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setIsPlayable(true).build())
            .setUri(source.uri).setMimeType(source.mimeType)
            .setTag(source).build()
    }

    private suspend fun search(query: String): List<Track> {
        val term = query.trim().take(200)
        if (term.isBlank()) return emptyList()
        return repository.tracks.first().filter {
            it.title.contains(term, true) || it.artist.contains(term, true) || it.album.contains(term, true)
        }
    }

    private suspend fun tracksFor(parentId: String): List<Track>? {
        val all = repository.tracks.first()
        return when {
            parentId == "local" -> all.filter { it.provider == "local" }
            parentId == "favorites" -> repository.favorites.first().let { ids -> all.filter { it.id in ids } }
            parentId == "recent" -> repository.recent.first()
            parentId.startsWith("playlist/") -> repository.playlistTracks(Uri.decode(parentId.removePrefix("playlist/")))
            parentId.startsWith("album/") -> {
                val parts = parentId.removePrefix("album/").split('/', limit = 2).map(Uri::decode)
                all.filter { parts.size == 2 && it.album == parts[0] && it.artist == parts[1] }
            }
            parentId.startsWith("artist/") -> all.filter { it.artist == Uri.decode(parentId.removePrefix("artist/")) }
            else -> null
        }
    }

    private suspend fun children(parentId: String): List<MediaItem>? = when (parentId) {
        "root" -> listOf(folder("local", "On this device"), folder("playlists", "Playlists"),
            folder("favorites", "Favorites"), folder("recent", "Recently played"),
            folder("albums", "Albums"), folder("artists", "Artists"))
        "playlists" -> repository.playlists.first().map { folder("playlist/${Uri.encode(it.id)}", it.name) }
        "albums" -> repository.tracks.first().map { it.album to it.artist }.distinct().sortedBy { it.first }.map { (album, artist) ->
            // shortcut: track artist identifies albums; use album artist when the domain supports compilations.
            folder("album/${Uri.encode(album)}/${Uri.encode(artist)}", "${album.ifBlank { "Unknown album" }} · $artist")
        }
        "artists" -> repository.tracks.first().map { it.artist }.distinct().sorted().map {
            folder("artist/${Uri.encode(it)}", it.ifBlank { "Unknown artist" })
        }
        else -> tracksFor(parentId)?.map(Track::toMediaItem)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            subscriptions.values.forEach { it.remove(controller) }
            subscriptions.entries.removeAll { it.value.isEmpty() }
        }

        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = scope.future {
            val items = children(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            subscriptions.getOrPut(parentId) { mutableSetOf() }.add(browser)
            session.notifyChildrenChanged(browser, parentId, items.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onUnsubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String): ListenableFuture<LibraryResult<Void>> = scope.future {
            subscriptions[parentId]?.remove(browser)
            if (subscriptions[parentId]?.isEmpty() == true) subscriptions.remove(parentId)
            LibraryResult.ofVoid()
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            LibraryResult.ofItem(folder("root", "Echo"), params)
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int,
            params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            if (page < 0 || pageSize < 1) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else children(parentId)?.let { LibraryResult.ofItemList(page(it, page, pageSize), params) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val track = repository.tracks.first().find { it.id == mediaId }
            val item = track?.toMediaItem() ?: when (mediaId) {
                "root" -> folder("root", "Echo")
                else -> children("root")?.find { it.mediaId == mediaId }
                    ?: children("playlists")?.find { it.mediaId == mediaId }
                    ?: children("albums")?.find { it.mediaId == mediaId }
                    ?: children("artists")?.find { it.mediaId == mediaId }
            }
            item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int,
            params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            if (page < 0 || pageSize < 1) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItemList(page(search(query), page, pageSize).map(Track::toMediaItem), params)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> = scope.future {
            recoveryJob?.cancel()
            val all = repository.tracks.first().associateBy(Track::id)
            val result = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val query = item.requestMetadata.searchQuery
                val requested = when {
                    !query.isNullOrBlank() -> search(query).take(1)
                    item.mediaId in all -> listOf(all.getValue(item.mediaId))
                    else -> tracksFor(item.mediaId).orEmpty()
                }
                require(requested.isNotEmpty()) { "Unknown media id or no matching music" }
                for (track in requested) {
                    result += resolve(track) ?: throw IllegalArgumentException("No authorized playable source for ${track.title}")
                }
            }
            result
        }

        override fun onPlaybackResumption(mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            resumeQueue()
        }
    }
}

/** One extraction per prepared source; refresh just before a signed URL expires. */
internal class PlaybackSourceCache(private val resolve: () -> PlayableSource?) {
    private var source: PlayableSource? = null

    @Synchronized fun get(now: Long): PlayableSource {
        source?.takeIf { val expiry = it.expiresAt; expiry == null || expiry > now + 30_000 }?.let { return it }
        return (resolve() ?: throw IOException("No playable online source"))
            .also { source = it }
    }
}

internal fun shouldRefreshOnlineSource(provider: String?, status: Int?, attempts: Int): Boolean =
    provider == "youtube" && status == 403 && attempts == 0
