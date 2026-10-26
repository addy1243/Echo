package dev.echo.app.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.echo.app.MainActivity
import dev.echo.app.R
import dev.echo.data.library.LibraryRepository
import dev.echo.data.offline.OfflineRepository
import dev.echo.data.offline.downloadPercent
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChangedBy

@AndroidEntryPoint
class SongDownloadService : Service() {
    @Inject lateinit var offline: OfflineRepository
    @Inject lateinit var library: LibraryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = mutableMapOf<String, Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Music downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            tasks.values.toList().forEach(Job::cancel)
            if (tasks.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("track")
        if (id.isNullOrBlank() || id.length > 512) { if (tasks.isEmpty()) stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, NOTIFICATION, notification("Preparing music download", null).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (id in tasks) return START_NOT_STICKY
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echo:MusicDownload").apply { acquire(6 * 60 * 60_000L) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val track = withContext(Dispatchers.IO) { library.tracks.first().firstOrNull { it.id == id } } ?: error("Song is no longer in the library")
                withContext(Dispatchers.IO) { offline.download(track) }
                val progress = launch {
                    offline.songs.distinctUntilChangedBy { songs -> songs.map { Triple(it.trackId, it.status, downloadPercent(it.receivedBytes, it.totalBytes)?.div(5)) } }.collect { songs ->
                        val active = songs.firstOrNull { it.status == "downloading" }
                        val pending = songs.count { it.status in setOf("queued", "downloading") }
                        val percent = active?.let { downloadPercent(it.receivedBytes, it.totalBytes) }
                        notifications.notify(NOTIFICATION, notification("${active?.title ?: "Preparing"} · $pending pending" + (percent?.let { " · $it%" } ?: ""), percent).build())
                    }
                }
                try { offline.songs.first { songs -> songs.any { it.trackId == id && it.status in setOf("saved", "failed") } } }
                finally { progress.cancelAndJoin() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                notifications.notify(412, notification("Could not start download. Open Downloads to retry.", null)
                    .setContentTitle("Music download interrupted").setOngoing(false).setAutoCancel(true)
                    .setProgress(0, 0, false).clearActions().build())
            }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { offline.cancel(id) }
                tasks.remove(id)
                if (tasks.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        tasks[id] = job
        job.start()
        return START_NOT_STICKY
    }

    private fun notification(text: String, percent: Int?) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_echo).setContentTitle("Echo music downloads").setContentText(text)
        .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("echoDownloads", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent ?: 0, percent == null)
        .addAction(0, "Cancel downloads", PendingIntent.getService(this, 1, Intent(this, SongDownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    override fun onTimeout(startId: Int, fgsType: Int) { scope.cancel(); stopSelf() }
    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "echo_music_downloads"
        private const val NOTIFICATION = 411
        private const val CANCEL = "dev.echo.app.CANCEL_MUSIC_DOWNLOADS"
        fun enqueue(context: Context, id: String) {
            require(id.isNotBlank() && id.length <= 512) { "Invalid song identity" }
            ContextCompat.startForegroundService(context, Intent(context, SongDownloadService::class.java).putExtra("track", id))
        }
    }
}
