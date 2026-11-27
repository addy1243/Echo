package dev.echo.app

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.compose.AsyncImage
import dev.echo.core.PlayableSource
import dev.echo.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.draw.blur

/** Muted picture layer. Echo's service player owns audio and the queue. */
@Composable
internal fun StageCinema(
    track: Track?,
    occurrenceKey: String?,
    audioPositionMs: Long,
    audioPlaying: Boolean,
    resolveVideo: suspend (Track, String) -> PlayableSource?,
    modifier: Modifier = Modifier,
    onVideoReady: (Boolean) -> Unit = {},
    showAmbientDetails: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentResolver by rememberUpdatedState(resolveVideo)
    val currentPlaying by rememberUpdatedState(audioPlaying)
    val currentPosition by rememberUpdatedState(audioPositionMs.coerceAtLeast(0))
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { onVideoReady(ready) }
    DisposableEffect(Unit) { onDispose { player = null; onVideoReady(false) } }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    player = null
                    ready = false
                }
                Lifecycle.Event.ON_START -> foreground = true
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(track?.id, occurrenceKey, foreground) {
        player = null
        ready = false
        failed = false
        if (!foreground) return@LaunchedEffect
        val selectedOccurrence = occurrenceKey ?: return@LaunchedEffect
        val selected = track?.takeIf {
            it.provider == "youtube"
        } ?: return@LaunchedEffect
        val source = try { withTimeoutOrNull(12_000) { currentResolver(selected, selectedOccurrence) } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
            ?.takeIf { it.uri.isNotBlank() } ?: run { failed = true; return@LaunchedEffect }
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
        val dataSource = DefaultDataSource.Factory(context, http)
        val created = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        created.volume = 0f
        created.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player !== created) return
                ready = false
                failed = true
            }
        })
        created.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (player !== created) return
                ready = true
                failed = false
            }
        })
        player = created
        created.setMediaItem(MediaItem.Builder().setUri(Uri.parse(source.uri)).setMimeType(source.mimeType).build())
        created.prepare()
        created.seekTo(currentPosition)
        if (currentPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) created.play()
    }

    DisposableEffect(player) {
        val active = player
        onDispose { active?.release() }
    }

    LaunchedEffect(player, ready, failed, audioPlaying) {
        val active = player ?: return@LaunchedEffect
        if (audioPlaying && !ready && !failed) {
            delay(12_000)
            if (!ready && !failed) { failed = true; active.stop() }
        }
    }

    LaunchedEffect(player, audioPlaying) {
        val active = player ?: return@LaunchedEffect
        if (audioPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) active.play() else active.pause()
    }

    LaunchedEffect(player, track?.id, occurrenceKey) {
        val active = player ?: return@LaunchedEffect
        while (true) {
            delay(900)
            val drift = currentPosition - active.currentPosition
            if (kotlin.math.abs(drift) > 1_200L) active.seekTo(currentPosition)
            if (currentPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) active.play() else active.pause()
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF08090D))) {
        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag(if (ready) "stage-cinema-video-ready" else "stage-cinema-video-surface"),
                factory = { view -> TextureView(view).also { player?.setVideoTextureView(it) } },
                update = { view -> player?.setVideoTextureView(view) },
            )
        }
        if (!ready) AmbientStage(track, failed, Modifier.fillMaxSize(), showAmbientDetails)
    }
}

@Composable
private fun AmbientStage(track: Track?, failed: Boolean, modifier: Modifier = Modifier, showDetails: Boolean = true) {
    Box(modifier.background(Color(0xFF08090D))) {
        track?.artworkUri?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(36.dp),
                alpha = .52f,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xAA08090D), Color(0x6610121A), Color(0xDD08090D))
        )))
        if (showDetails) Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ECHO", color = Color.White.copy(alpha = .82f), fontSize = 13.sp, letterSpacing = 5.sp)
            Text(track?.title ?: "Standby", color = Color.White, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!track?.artist.isNullOrBlank()) Text(track!!.artist, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.titleMedium)
            if (failed) Text("Video unavailable · Ambient", color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
