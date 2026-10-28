package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.provider.YouTubeProvider
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

enum class DesktopRepeatMode { OFF, ALL, ONE }

data class DesktopPlaybackState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 0.7f,
    val error: String? = null,
    val occurrence: Long = 0,
    val queueRevision: Long = 0,
    val repeatMode: DesktopRepeatMode = DesktopRepeatMode.OFF,
    val ended: Boolean = false,
    val sleepRemainingMs: Long? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    val canRetainCurrent: Boolean get() = current != null && !ended && !loading && error == null
}

internal fun upcomingDesktopTracks(state: DesktopPlaybackState): List<Track> =
    if (state.current == null) emptyList() else state.queue.drop(state.index + 1) +
        if (state.repeatMode == DesktopRepeatMode.ALL) state.queue.take(state.index) else emptyList()

internal fun appendedDesktopQueue(state: DesktopPlaybackState, items: List<Track>, next: Boolean): DesktopPlaybackState? {
    val current = state.current ?: return null
    val upcoming = upcomingDesktopTracks(state)
    val combined = listOf(current) + if (next) items + upcoming else upcoming + items
    if (combined.size > 10_000) return null
    return state.copy(queue = combined, index = 0, queueRevision = state.queueRevision + 1)
}

internal fun retainedDesktopQueue(state: DesktopPlaybackState, items: List<Track>, expectedOccurrence: Long,
    anchorId: String?, expectedQueueRevision: Long?): DesktopPlaybackState? {
    if (state.occurrence != expectedOccurrence || expectedQueueRevision != null && state.queueRevision != expectedQueueRevision ||
        items.isEmpty() || items.size > 10_000 || items.any { it.sourceUri == null || it.provider !in setOf("local", "youtube") }) return null
    if (anchorId == null) return state.copy(queue = items.toList(), index = 0, queueRevision = state.queueRevision + 1)
    if (!state.canRetainCurrent || anchorId != state.current?.id || items.first().id != anchorId) return null
    return state.copy(queue = listOf(state.current!!) + items.drop(1), index = 0, queueRevision = state.queueRevision + 1)
}

internal fun editDesktopUpcoming(state: DesktopPlaybackState, from: Int, to: Int?, expectedOccurrence: Long,
    expectedQueueRevision: Long): DesktopPlaybackState? {
    val upcoming = (state.index + 1 until state.queue.size).toList() +
        if (state.repeatMode == DesktopRepeatMode.ALL) (0 until state.index).toList() else emptyList()
    if (state.current == null || state.occurrence != expectedOccurrence || state.queueRevision != expectedQueueRevision ||
        from !in upcoming || to != null && to !in upcoming) return null
    val order = if (state.repeatMode == DesktopRepeatMode.ALL) listOf(state.index) + upcoming else state.queue.indices.toList()
    val items = order.map { state.queue[it] }.toMutableList()
    val moved = items.removeAt(order.indexOf(from))
    if (to != null) items.add(order.indexOf(to), moved)
    return state.copy(queue = items, index = order.indexOf(state.index), queueRevision = state.queueRevision + 1)
}

class DesktopPlayback(
    private val provider: YouTubeProvider = YouTubeProvider(),
    private val offline: DesktopOfflineRepository? = null,
    private val onHistory: (Track, String, Long) -> Unit = { _, _, _ -> },
) : Closeable {
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "Echo audio").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(DesktopPlaybackState())
    val state: StateFlow<DesktopPlaybackState> = mutableState
    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null
    private var source: DesktopAudioSource? = null
    private var load: Job? = null
    private var generation = 0L
    private var listenedMs = 0L
    private var playedRecorded = false
    private var terminalRecorded = false
    private var wantsPlaying = false
    private var sleepJob: Job? = null
    private var playingSince: TimeSource.Monotonic.ValueTimeMark? = null
    @Volatile private var closed = false
    private var remoteActive = false

    init { offline?.let { repository -> scope.launch {
        repository.state.collect { settings ->
            if (settings.offlineOnly && remoteActive) {
                ++generation; load?.cancel(); wantsPlaying = false; record("INTERRUPTED")
                source?.close(); source = null; remoteActive = false
                player?.controls()?.stop()
                update { it.copy(playing = false, loading = false, error = "Offline mode is enabled. Choose downloaded music or a local file.") }
            }
        }
    } } }

    private fun update(transform: (DesktopPlaybackState) -> DesktopPlaybackState) { mutableState.value = transform(mutableState.value) }

    private fun nativePlayer(ticket: Long): MediaPlayer {
        player?.let { return it }
        val createdFactory = factory ?: MediaPlayerFactory("--no-video", "--quiet", "--no-media-library", "--no-metadata-network-access")
        factory = createdFactory
        return createdFactory.mediaPlayers().newMediaPlayer().also { created ->
            player = created
            fun event(action: () -> Unit) { scope.launch { if (generation == ticket && !closed) action() } }
            created.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) { event {
                    if (!wantsPlaying) player?.controls()?.setPause(true)
                    else if (!state.value.loading && state.value.current != null) {
                        if (playingSince == null) playingSince = TimeSource.Monotonic.markNow()
                        recordStart()
                        update { it.copy(playing = true) }
                    }
                } }
                override fun paused(mediaPlayer: MediaPlayer) { event { stopCounting(); update { it.copy(playing = false) } } }
                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { event {
                    if (newCache < 100f) { stopCounting(); update { it.copy(playing = false) } }
                    else if (wantsPlaying && !state.value.loading && player?.status()?.isPlaying == true) {
                        if (playingSince == null) playingSince = TimeSource.Monotonic.markNow()
                        recordStart()
                        update { it.copy(playing = true) }
                    }
                } }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { event { update { it.copy(positionMs = newTime.coerceAtLeast(0)) } } }
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { event { update { it.copy(durationMs = newLength.coerceAtLeast(0)) } } }
                override fun finished(mediaPlayer: MediaPlayer) { event {
                    if (!state.value.loading) {
                        record("COMPLETED")
                        val next = when (state.value.repeatMode) {
                            DesktopRepeatMode.ONE -> state.value.index
                            DesktopRepeatMode.ALL -> (state.value.index + 1) % state.value.queue.size
                            DesktopRepeatMode.OFF -> state.value.index + 1
                        }
                        if (next in state.value.queue.indices) start(state.value.queue, next)
                        else update { it.copy(playing = false, ended = true, positionMs = it.durationMs) }
                    }
                } }
                override fun error(mediaPlayer: MediaPlayer) { event {
                    if (!state.value.loading) {
                        record("ERROR")
                        update { it.copy(playing = false, loading = false, error = "Audio could not play. Try another recording or check your connection.") }
                    }
                } }
            })
        }
    }

    fun play(tracks: List<Track>, index: Int = 0) {
        if (closed || index !in tracks.indices || tracks.size > 10_000) return
        val snapshot = tracks.toList()
        scope.launch { record("SKIPPED"); start(snapshot, index) }
    }

    private fun start(tracks: List<Track>, index: Int) {
        load?.cancel()
        val ticket = ++generation
        wantsPlaying = true
        update { it.copy(queue = tracks, index = index, playing = false, loading = true, ended = false, positionMs = 0, durationMs = tracks[index].durationMs, error = null, occurrence = ticket, queueRevision = it.queueRevision + 1) }
        player?.controls()?.stop(); player?.release(); player = null
        source?.close(); source = null
        remoteActive = false
        listenedMs = 0; playingSince = null; playedRecorded = false; terminalRecorded = false
        load = scope.launch {
            try {
                val track = tracks[index]
                val saved = offline?.savedFile(track)
                val locator = if (saved != null) saved.absolutePath else when (track.provider) {
                    "local" -> localAudioFile(track).absolutePath
                    "youtube" -> {
                        check(offline?.state?.value?.offlineOnly != true) { "Offline mode is enabled" }
                        remoteActive = true
                        val resolved = provider.resolve(track) ?: error("Recording has no playable audio")
                        ensureActive()
                        if (generation != ticket) return@launch
                        DesktopAudioSource(resolved) { provider.resolve(track) }.also { source = it }.uri
                    }
                    else -> error("This provider is not supported on desktop yet")
                }
                ensureActive()
                if (generation != ticket) return@launch
                val native = nativePlayer(ticket)
                native.audio().setVolume((state.value.volume * 100).toInt())
                // Native callbacks are queued onto this dispatcher after the loading flag is cleared.
                check(if (wantsPlaying) native.media().play(locator) else native.media().play(locator, ":start-paused")) { "VLC could not start playback" }
                update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (generation == ticket) {
                    record("ERROR")
                    update { it.copy(playing = false, loading = false, error =
                    if (error is UnsatisfiedLinkError || error is NoClassDefFoundError) "Install 64-bit VLC 3, then restart Echo to enable playback."
                    else if (offline?.state?.value?.offlineOnly == true) "Offline mode is enabled. Choose downloaded music or a local file."
                    else if (error is dev.echo.data.provider.YouTubeRequestException) error.message
                    else "Could not start audio. Check that VLC 3 is installed and the recording is available.") }
                }
            }
        }
    }

    fun toggle() { if (!closed) scope.launch {
        if (state.value.loading) return@launch
        if (state.value.playing) { wantsPlaying = false; stopCounting(); player?.controls()?.setPause(true); update { it.copy(playing = false) } }
        else if (state.value.current != null) {
            wantsPlaying = true
            if (state.value.error != null || state.value.positionMs >= state.value.durationMs && state.value.durationMs > 0) start(state.value.queue, state.value.index)
            else player?.controls()?.play()
        }
    } }
    fun next() { if (!closed) scope.launch {
        val next = if (state.value.index + 1 < state.value.queue.size) state.value.index + 1
            else if (state.value.repeatMode == DesktopRepeatMode.ALL && state.value.queue.isNotEmpty()) 0 else -1
        if (next >= 0) { record("SKIPPED"); start(state.value.queue, next) }
    } }
    fun previous() { if (!closed) scope.launch {
        if (state.value.positionMs > 3000) player?.controls()?.setTime(0)
        else if (state.value.index > 0) { record("SKIPPED"); start(state.value.queue, state.value.index - 1) }
        else if (state.value.repeatMode == DesktopRepeatMode.ALL && state.value.queue.isNotEmpty()) {
            record("SKIPPED"); start(state.value.queue, state.value.queue.lastIndex)
        }
    } }
    fun seek(positionMs: Long) { if (!closed) scope.launch {
        if (!state.value.loading && state.value.durationMs > 0) player?.controls()?.setTime(positionMs.coerceIn(0, state.value.durationMs))
    } }
    fun setVolume(volume: Float) { if (!closed && volume.isFinite()) scope.launch {
        val bounded = volume.coerceIn(0f, 1f)
        player?.audio()?.setVolume((bounded * 100).toInt())
        update { it.copy(volume = bounded) }
    } }
    fun searchSelected(track: Track) { if (!closed && playableDesktopTrack(track)) scope.launch {
        val upcoming = upcomingDesktopTracks(state.value)
        if (upcoming.size < 10_000) { record("SKIPPED"); start(listOf(track) + upcoming, 0) }
    } }
    fun append(items: List<Track>, next: Boolean = false) {
        if (closed || items.isEmpty() || items.size > 5000 || !items.all(::playableDesktopTrack)) return
        val snapshot = items.toList()
        scope.launch {
            val current = state.value.current
            if (current == null || state.value.ended) { record("INTERRUPTED"); start(snapshot, 0) }
            else {
                appendedDesktopQueue(state.value, snapshot, next)?.let { mutableState.value = it }
            }
        }
    }
    suspend fun applySmartQueue(items: List<Track>, expectedOccurrence: Long, anchorId: String?,
        expectedQueueRevision: Long? = null): Boolean {
        if (closed) return false
        val snapshot = items.toList()
        return withContext(dispatcher) {
            val replacement = retainedDesktopQueue(state.value, snapshot, expectedOccurrence, anchorId, expectedQueueRevision) ?: return@withContext false
            if (anchorId != null) mutableState.value = replacement
            else { record("INTERRUPTED"); start(replacement.queue, 0) }
            true
        }
    }
    fun setRepeatMode(mode: DesktopRepeatMode) { if (!closed) scope.launch { update { it.copy(repeatMode = mode, queueRevision = it.queueRevision + 1) } } }
    fun setSleepTimer(minutes: Int?) {
        require(minutes == null || minutes in setOf(15, 30, 45, 60)) { "Choose a supported sleep duration" }
        setSleepTimerMillis(minutes?.times(60_000L))
    }
    internal fun setSleepTimerMillis(durationMs: Long?) {
        require(durationMs == null || durationMs in 1L..3_600_000L)
        if (closed) return
        scope.launch {
            sleepJob?.cancel()
            update { it.copy(sleepRemainingMs = durationMs) }
            sleepJob = durationMs?.let { duration -> scope.launch {
                val started = TimeSource.Monotonic.markNow()
                while (isActive) {
                    val remaining = (duration - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
                    if (remaining == 0L) {
                        wantsPlaying = false
                        stopCounting()
                        player?.controls()?.setPause(true)
                        update { it.copy(playing = false, sleepRemainingMs = null) }
                        break
                    }
                    update { it.copy(sleepRemainingMs = remaining) }
                    delay(minOf(remaining, 1000L))
                }
            } }
        }
    }
    fun shuffleUpcoming() { if (!closed) scope.launch {
        val current = state.value.current ?: return@launch
        update { it.copy(queue = listOf(current) + upcomingDesktopTracks(it).shuffled(), index = 0, queueRevision = it.queueRevision + 1) }
    } }
    fun clearUpcoming() { if (!closed) scope.launch {
        val current = state.value.current ?: return@launch
        update { it.copy(queue = listOf(current), index = 0, queueRevision = it.queueRevision + 1) }
    } }
    suspend fun removeUpcoming(index: Int, expectedOccurrence: Long, expectedQueueRevision: Long): Boolean {
        if (closed) return false
        return withContext(dispatcher) {
            mutableState.value = editDesktopUpcoming(state.value, index, null, expectedOccurrence, expectedQueueRevision) ?: return@withContext false
            true
        }
    }
    suspend fun moveUpcoming(from: Int, to: Int, expectedOccurrence: Long, expectedQueueRevision: Long): Boolean {
        if (closed) return false
        return withContext(dispatcher) {
            mutableState.value = editDesktopUpcoming(state.value, from, to, expectedOccurrence, expectedQueueRevision) ?: return@withContext false
            true
        }
    }
    suspend fun jumpTo(index: Int, expectedQueueRevision: Long): Boolean {
        if (closed) return false
        return withContext(dispatcher) {
            if (state.value.queueRevision != expectedQueueRevision || index !in state.value.queue.indices) return@withContext false
            if (index != state.value.index || state.value.ended) { record("SKIPPED"); start(state.value.queue, index) }
            true
        }
    }
    private fun stopCounting() {
        playingSince?.let { listenedMs += it.elapsedNow().inWholeMilliseconds.coerceAtLeast(0) }
        playingSince = null
    }
    private fun record(event: String) {
        stopCounting()
        val track = state.value.current
        if (track != null && !terminalRecorded && (playedRecorded || event == "ERROR")) {
            terminalRecorded = true
            runCatching { onHistory(track, event, listenedMs) }.onFailure {
                update { it.copy(error = "Playback continued, but listening history could not be saved.") }
            }
            listenedMs = 0
        }
    }
    private fun recordStart() {
        if (playedRecorded) return
        playedRecorded = true
        state.value.current?.let { track -> runCatching { onHistory(track, "PLAYED", 0) }.onFailure {
            update { it.copy(error = "Playback continued, but listening history could not be saved.") }
        } }
    }
    override fun close() {
        if (closed) return
        closed = true
        runBlocking { withContext(dispatcher) {
            ++generation; load?.cancel(); record("INTERRUPTED")
            source?.close(); source = null
            player?.controls()?.stop(); player?.release(); player = null
            factory?.release(); factory = null
            scope.cancel()
        } }
        dispatcher.close()
    }
}

private fun playableDesktopTrack(track: Track) = track.sourceUri != null && track.provider in setOf("local", "youtube")

internal fun localAudioFile(track: Track): File {
    val uri = java.net.URI(track.sourceUri ?: error("Local audio path is missing"))
    require(uri.scheme == "file" && uri.host.isNullOrEmpty()) { "Choose a local audio file" }
    return File(uri).also { require(it.isFile && it.canRead()) { "Local audio file is missing or unreadable" } }
}
