package dev.aasim.bootstick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the app alive while [Downloader] works, with one progress notification for all downloads. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Downloader.init(this)
        channel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, progress(), type)
        if (intent?.action == PAUSE) Downloader.pauseAll()
        if (loop?.isActive != true) loop = scope.launch {
            while (Downloader.active.isNotEmpty()) {
                getSystemService(NotificationManager::class.java).notify(ID, progress())
                delay(1000)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15+ limits dataSync services to 6 hours a day; pause cleanly so the downloads can resume later.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloader.pauseAll()
        stopSelf()
    }

    private fun progress(): Notification {
        val active = Downloader.active
        val total = active.sumOf { it.total.coerceAtLeast(0) }
        val done = active.sumOf { it.done }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val speed = active.sumOf { it.speed }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(
                if (active.size == 1) getString(R.string.dl_notif_one, active[0].name)
                else resources.getQuantityString(R.plurals.dl_notif_many, active.size, active.size),
            )
            .setContentText(getString(R.string.dl_notif_progress, pct, size(this, speed)))
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(this))
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.pause),
                    PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE),
                ).build(),
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val ID = 2
        private const val CHANNEL = "downloads"
        private const val PAUSE = "pause"

        private fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW))

        private fun open(ctx: Context) =
            PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        fun notifyDone(ctx: Context, name: String, check: String?) {
            channel(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(
                name.hashCode(),
                Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(ctx.getString(R.string.dl_done_title, name))
                    .setContentText(
                        ctx.getString(
                            when (check) {
                                "ok" -> R.string.dl_done_verified
                                "bad" -> R.string.dl_done_bad
                                else -> R.string.dl_done_body
                            },
                        ),
                    )
                    .setContentIntent(open(ctx))
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
