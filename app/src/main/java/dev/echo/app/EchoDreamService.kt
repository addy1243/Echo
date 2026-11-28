package dev.echo.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.ComponentName
import android.app.KeyguardManager
import android.service.dreams.DreamService
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.ViewGroup
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import dev.echo.playback.EchoPlaybackService
import dev.echo.playback.DREAM_CONTROLLER_HINT
import dev.echo.playback.echoPlaybackServiceRunning

/**
 * Passive system DreamService entry point.
 *
 * Android owns whether/when a dream starts (including its timeout, charging and
 * docking rules). This service deliberately does not read Echo standby
 * preferences, start playback, or stop the playback service.
 */
class EchoDreamService : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private var artworkExecutor = Executors.newSingleThreadExecutor()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var artwork: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progress: ProgressBar
    private var attached = false
    private var lastArtworkHash = 0
    private var connectionAttempts = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        connectionAttempts = 0
        artworkExecutor = Executors.newSingleThreadExecutor()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        artwork = ImageView(this).apply {
            setBackgroundColor(Color.rgb(32, 32, 40))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.dream_artwork)
        }
        val artworkSize = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * .55f).toInt()
        content.addView(artwork, LinearLayout.LayoutParams(artworkSize, artworkSize))
        title = textView(26f)
        artist = textView(18f).apply { setTextColor(Color.LTGRAY) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        content.addView(title, centeredParams())
        content.addView(artist, centeredParams())
        content.addView(progress, LinearLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(content)
        title.text = getString(R.string.dream_echo)
        artist.text = getString(R.string.dream_playback_continues)
        connectToPlaybackSession()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        attached = false
        controller?.release()
        controller = null
        controllerFuture?.cancel(false)
        controllerFuture = null
        artworkExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun connectToPlaybackSession() {
        if (controller != null || controllerFuture != null) return
        if (!attached) return
        if (connectionAttempts >= 5) {
            finish()
            return
        }
        connectionAttempts++
        if (!echoPlaybackServiceRunning) {
            if (connectionAttempts < 5) handler.postDelayed(::connectToPlaybackSession, 1_000L)
            else finish()
            return
        }
        val hints = Bundle().apply { putBoolean(DREAM_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, EchoPlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@EchoDreamService.controller === controller) {
                        controller.release()
                        this@EchoDreamService.controller = null
                        controllerFuture = null
                        resetDisplay()
                        connectionAttempts = 0
                        if (attached) handler.postDelayed(::connectToPlaybackSession, 1_000L)
                    }
                }
            })
            .setConnectionHints(hints)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }
                .onSuccess { connected ->
                    if (!attached) {
                        connected.release()
                        return@onSuccess
                    }
                    controller = connected
                    controllerFuture = null
                    connectionAttempts = 0
                    renderMetadata()
                    handler.post(progressUpdater)
                }
                .onFailure {
                    if (controllerFuture === future) {
                        controllerFuture = null
                        if (attached) {
                            if (connectionAttempts < 5) handler.postDelayed(::connectToPlaybackSession, 1_000L)
                            else finish()
                        }
                    }
                }
        }, Executor { command -> handler.post(command) })
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            renderMetadata()
            if (controller != null) handler.postDelayed(this, 500L)
        }
    }

    private fun renderMetadata() {
        val player = controller ?: return
        if (!shouldShowStageStandby(player.currentMediaItem != null, player.isPlaying)) {
            finish()
            return
        }
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            title.text = getString(R.string.dream_echo)
            artist.text = getString(R.string.dream_playback_continues)
            artwork.setImageDrawable(null)
            lastArtworkHash = 0
            progress.progress = 0
            return
        }
        val metadata = player.mediaMetadata
        title.text = metadata.title ?: getString(R.string.dream_echo)
        artist.text = metadata.artist ?: getString(R.string.dream_playback_continues)
        val artworkData = metadata.artworkData
        val artworkHash = artworkData?.contentHashCode() ?: metadata.artworkUri?.hashCode() ?: 0
        if (artworkHash != lastArtworkHash) {
            lastArtworkHash = artworkHash
            if (artworkData == null) {
                artwork.setImageDrawable(null)
                metadata.artworkUri?.takeIf { it.scheme in setOf("content", "file", "android.resource") }?.let { uri ->
                    artworkExecutor.submit { decodeUri(uri)?.let { bitmap -> postArtwork(bitmap, artworkHash) } }
                }
            }
            else {
                artworkExecutor.submit { decodeBytes(artworkData)?.let { bitmap -> postArtwork(bitmap, artworkHash) } }
            }
        }
        val duration = player.duration
        progress.progress = if (duration > 0) ((player.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000) else 0
    }

    private fun resetDisplay() {
        if (!::title.isInitialized) return
        title.text = getString(R.string.dream_echo)
        artist.text = getString(R.string.dream_playback_continues)
        artwork.setImageDrawable(null)
        progress.progress = 0
        lastArtworkHash = 0
    }

    private fun postArtwork(bitmap: android.graphics.Bitmap, hash: Int) {
        handler.post {
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            if (attached && !locked && hash == lastArtworkHash) artwork.setImageBitmap(bitmap) else bitmap.recycle()
        }
    }

    private fun decodeBytes(bytes: ByteArray): android.graphics.Bitmap? =
        decodeSampled { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }

    private fun decodeUri(uri: android.net.Uri): android.graphics.Bitmap? = runCatching {
        decodeSampled { options -> contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
    }.getOrNull()

    private fun decodeSampled(decode: (BitmapFactory.Options) -> android.graphics.Bitmap?): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        return BitmapFactory.Options().apply { inSampleSize = sample }.let(decode)
    }

    private fun textView(size: Float) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        gravity = Gravity.CENTER
        setPadding(0, 12, 0, 0)
    }

    private fun centeredParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
