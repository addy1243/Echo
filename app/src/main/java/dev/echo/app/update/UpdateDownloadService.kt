package dev.echo.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChangedBy

@AndroidEntryPoint
class UpdateDownloadService : Service() {
    @Inject lateinit var updater: AppUpdater
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var download: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            download?.cancel()
            if (download == null) stopSelf()
            return START_NOT_STICKY
        }
        if (download != null) return START_NOT_STICKY
        val release = runCatching { requireNotNull(intent).release().also(::validateRelease) }.getOrElse {
            stopSelf(); return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION, notification("Starting download", 0).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        download = scope.launch {
            try {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echo:UpdateDownload").apply { acquire(11 * 60_000L) }
                val progress = launch {
                    updater.status.distinctUntilChangedBy { (it as? UpdateStatus.Downloading)?.percent?.div(5) ?: it }.collect {
                        if (it is UpdateStatus.Downloading) notifications.notify(NOTIFICATION, notification("${it.percent}% · ${release.versionName}", it.percent).build())
                    }
                }
                try { updater.download(release) } finally { progress.cancelAndJoin() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                when (val result = updater.status.value) {
                    is UpdateStatus.Ready -> notifications.notify(NOTIFICATION, notification("Ready to install · ${release.versionName}", null).build())
                    is UpdateStatus.Failed -> notifications.notify(NOTIFICATION, notification(result.message, null).build())
                    else -> Unit
                }
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                if (!currentCoroutineContext().isActive) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, percent: Int?) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_echo).setContentTitle("Echo Music update").setContentText(text)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOnlyAlertOnce(true).setOngoing(percent != null).setAutoCancel(percent == null)
        .apply {
            if (percent != null) {
                setProgress(100, percent, false)
                addAction(0, "Cancel", PendingIntent.getService(this@UpdateDownloadService, 0, Intent(this@UpdateDownloadService, UpdateDownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }

    override fun onTimeout(startId: Int, fgsType: Int) { download?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "echo_app_updates"
        private const val NOTIFICATION = 410
        private const val CANCEL = "dev.echo.app.CANCEL_UPDATE"
        fun start(context: Context, release: UpdateRelease) {
            validateRelease(release)
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java)
                .putExtra("code", release.versionCode).putExtra("name", release.versionName).putExtra("url", release.url)
                .putExtra("size", release.size).putExtra("sha256", release.sha256).putExtra("notes", release.notes))
        }
        fun cancel(context: Context) { context.startService(Intent(context, UpdateDownloadService::class.java).setAction(CANCEL)) }
        private fun Intent.release() = UpdateRelease(getLongExtra("code", 0), getStringExtra("name").orEmpty(), getStringExtra("url").orEmpty(), getLongExtra("size", 0), getStringExtra("sha256").orEmpty(), getStringExtra("notes").orEmpty())
    }
}
