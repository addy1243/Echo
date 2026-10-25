package dev.echo.app

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import dev.echo.data.library.SettingsRepository
import dev.echo.data.provider.CommonsProvider
import dev.echo.data.provider.SourceResolver
import dev.echo.data.spotify.SpotifyRepository
import dev.echo.data.spotify.SpotifyPlaylist
import dev.echo.data.provider.YouTubeProvider
import dev.echo.playback.EchoPlaybackService
import dev.echo.app.update.AppUpdater
import dev.echo.app.update.UpdateRelease
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@HiltViewModel
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EchoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val library: LibraryRepository,
    val settings: SettingsRepository,
    val spotify: SpotifyRepository,
    private val commons: CommonsProvider,
    private val youtube: YouTubeProvider,
    val sources: SourceResolver,
    val updater: AppUpdater,
) : ViewModel() {
    val tracks = library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites = library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val recent = library.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var controller by mutableStateOf<MediaController?>(null); private set
    var current by mutableStateOf<MediaItem?>(null); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var position by mutableStateOf(0L); private set
    var duration by mutableStateOf(0L); private set
    var revision by mutableStateOf(0); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var onlineResults by mutableStateOf<List<Track>>(emptyList()); private set
    var youtubeResults by mutableStateOf<List<Track>>(emptyList()); private set
    var isSearching by mutableStateOf(false); private set
    var searchedQuery by mutableStateOf(""); private set
    private var searchJob: Job? = null
    private var searchRequest = 0
    var spotifyPlaylists by mutableStateOf<List<SpotifyPlaylist>>(emptyList()); private set
    var playlistItems by mutableStateOf<List<Track>>(emptyList()); private set
    var loadedPlaylistId by mutableStateOf<String?>(null); private set
    var discoveryResults by mutableStateOf<List<Track>>(emptyList()); private set
    var newMusicResults by mutableStateOf<List<Track>>(emptyList()); private set
    var discoveryLoading by mutableStateOf(false); private set
    private val connection = MediaController.Builder(context, SessionToken(context, ComponentName(context, EchoPlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onError(controller: MediaController, sessionError: SessionError) { message = sessionError.message }
        }).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync() }
        override fun onPlayerError(error: PlaybackException) { message = "Playback failed: ${error.errorCodeName}. Check the source or retry." }
    }
    private var updateDownload: Job? = null
    init {
        viewModelScope.launch { updater.check(manual = false) }
        connection.addListener({
            try { controller = connection.get(); controller?.addListener(listener); sync() }
            catch (e: Exception) { message = "Playback service could not connect. Reopen Echo to retry." }
        }, ContextCompat.getMainExecutor(context))
        viewModelScope.launch { while (true) { sync(); delay(500) } }
    }
    private fun sync() {
        controller?.let { current = it.currentMediaItem; playing = it.isPlaying; buffering = it.playbackState == Player.STATE_BUFFERING; position = it.currentPosition.coerceAtLeast(0); duration = it.duration.coerceAtLeast(0); revision++ }
    }
    fun runAction(success: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { block(); if (success != null) message = success }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "The operation failed. Please retry." }
        finally { busy = false }
    }
    fun scan() = runAction("Local library updated") { library.scanLocal() }
    fun importSpotifyFile(uri: Uri) = runAction {
        val (text, name) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Imported playlist"
            val text = (context.contentResolver.openInputStream(uri) ?: error("Cannot open playlist file")).bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= 10_000_000) { "Playlist file is larger than 10 MB" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            text to name
        }
        message = library.importPlaylistFile(text, name)
    }

    private suspend fun discover(seeds: List<Track>, known: List<Track>): List<Track> {
        // shortcut: artist searches seed discovery; use a richer recommendation provider when available.
        val artists = seeds.map { it.artist }.filter { it.isNotBlank() && it != "Unknown artist" }.distinct().shuffled().take(3)
        val candidates = artists.flatMap { youtube.search(it.take(200)) }
        return discoveryCandidates(candidates, known).shuffled().take(20)
    }

    fun refreshDiscovery() {
        if (discoveryLoading) return
        discoveryLoading = true
        viewModelScope.launch {
            try {
                val known = library.tracks.first()
                val liked = library.favorites.first()
                val history = library.recent.first()
                val seeds = (known.filter { it.id in liked } + history + known).distinctBy { it.artist }
                val fresh = youtube.search("new music releases ${java.time.YearMonth.now()}")
                val suggestions = if (seeds.isEmpty()) fresh else discover(seeds, known + history.take(10))
                newMusicResults = fresh
                discoveryResults = suggestions
                if (suggestions.isEmpty()) message = "No new discoveries found for these artists. Try refreshing or searching another artist."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Discoveries couldn't load. Try refreshing." }
            finally { discoveryLoading = false }
        }
    }

    fun smartShuffle(items: List<Track>) = runAction {
        require(items.any { it.sourceUri != null }) { "Choose recordings for your imported songs first" }
        val suggestions = discover(items, items + library.recent.first().take(10))
        if (suggestions.isEmpty()) {
            message = "No discoveries found; your playlist is unchanged. Try regular shuffle."
        } else play(smartMix(items, suggestions), index = 0)
    }

    fun saveDiscovery() = runAction("Discovery mix saved to Library") {
        require(discoveryResults.isNotEmpty()) { "Refresh discovery first" }
        library.importPlaylist(java.util.UUID.randomUUID().toString(), "Echo discovery · ${java.time.LocalDate.now()}", discoveryResults)
    }
    fun checkUpdates() = runAction { updater.check(manual = true)?.let { message = it } }
    fun downloadUpdate(release: UpdateRelease) { if (updateDownload?.isActive != true) updateDownload = viewModelScope.launch { updater.download(release) } }
    fun cancelUpdate() { updateDownload?.cancel() }
    fun playFromSearch(query: String) = runAction {
        val songs = library.tracks.first().filter { it.sourceUri != null && (query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query, true)) }
        require(songs.isNotEmpty()) { "No playable music matches this search" }
        play(songs)
    }
    fun play(items: List<Track>, index: Int = firstPlayableIndex(items), shuffle: Boolean = false) = runAction {
        require(items.isNotEmpty()) { "There are no tracks to play" }
        val player = controller ?: error("Playback service is connecting")
        val playable = items.filter { it.sourceUri != null }
        require(playable.isNotEmpty()) { "No audio source is available. Match imported tracks to local music first." }
        require(items.getOrNull(index)?.sourceUri != null) { "This track has no audio source. Choose Match to select a local recording." }
        library.upsertTracks(playable.filter { candidate -> tracks.value.none { it.id == candidate.id } })
        player.setMediaItems(playable.map { it.mediaItem() }, playableStartIndex(items, index), 0)
        player.shuffleModeEnabled = shuffle
        player.prepare(); player.play()
    }
    fun enqueue(track: Track, next: Boolean = false) = runAction("Added to queue") {
        require(track.sourceUri != null) { "This track has no audio source" }
        if (tracks.value.none { it.id == track.id }) library.upsertTracks(listOf(track))
        val player = controller ?: error("Playback service is connecting")
        player.addMediaItem(if (next) (player.currentMediaItemIndex + 1).coerceAtLeast(0) else player.mediaItemCount, track.mediaItem())
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun resume() { controller?.play() }
    fun favorite(track: Track) = runAction { if (tracks.value.none { it.id == track.id }) library.upsertTracks(listOf(track)); library.setFavorite(track.id, track.id !in favorites.value) }
    fun loadPlaylist(id: String) {
        loadedPlaylistId = id
        playlistItems = emptyList()
        runAction { val songs = library.playlistTracks(id); if (loadedPlaylistId == id) playlistItems = songs }
    }
    fun mutatePlaylist(id: String, action: suspend () -> Unit) = runAction { action(); val songs = library.playlistTracks(id); if (loadedPlaylistId == id) playlistItems = songs }
    fun searchOnline(query: String) = search(query, commons::search) { onlineResults = it }
    fun searchYouTube(query: String) = search(query, youtube::search) { youtubeResults = it }
    private fun search(query: String, fetch: suspend (String) -> List<Track>, publish: (List<Track>) -> Unit) {
        searchJob?.cancel()
        val request = ++searchRequest
        val submittedQuery = query.trim()
        onlineResults = emptyList()
        youtubeResults = emptyList()
        searchedQuery = submittedQuery
        isSearching = true
        searchJob = viewModelScope.launch {
            try {
                require(submittedQuery.isNotBlank()) { "Enter a search term" }
                settings.rememberSearch(submittedQuery)
                publishSearchResult({ fetch(submittedQuery) }) {
                    publish(it)
                    if (it.isEmpty()) message = "No music results found"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == searchRequest) message = error.message ?: "Search failed. Please retry." }
            finally { if (request == searchRequest) isSearching = false }
        }
    }
    fun spotifyCallback(uri: Uri) = runAction("Spotify connected") { spotify.handleCallback(uri); spotifyPlaylists = spotify.playlists() }
    fun fetchSpotify() = runAction { spotifyPlaylists = spotify.playlists() }
    override fun onCleared() { controller?.removeListener(listener); MediaController.releaseFuture(connection) }
}

internal fun firstPlayableIndex(items: List<Track>): Int = items.indexOfFirst { it.sourceUri != null }

internal suspend fun <T> publishSearchResult(fetch: suspend () -> T, publish: (T) -> Unit) {
    val result = fetch()
    currentCoroutineContext().ensureActive()
    publish(result)
}

internal fun playableStartIndex(items: List<Track>, index: Int): Int {
    require(index in items.indices && items[index].sourceUri != null) { "Select a playable track" }
    return items.take(index).count { it.sourceUri != null }
}

private fun Track.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id)
    .setUri(sourceUri ?: "")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(artworkUri?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false).build()).build()
