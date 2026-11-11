package dev.aasim.bootstick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Hidden UsbManager.ACTION_USB_STATE: sticky, with "connected" (cable has data) and "configured" (a computer set us up). */
const val USB_STATE = "android.hardware.usb.action.USB_STATE"

/**
 * Runs while images are mounted: shows them in a notification with Eject, and watches USB state so that
 * - a USB HAL that wiped our configfs link gets it re-linked, and
 * - an image the computer ejected is re-inserted when the cable is plugged in again (e.g. moved to the PC to boot).
 */
class MountService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var connected: Boolean? = null
    private var replugged = false
    @Volatile private var ejected = false

    private val usbState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = i.getBooleanExtra("connected", false)
            if (connected == false && now) replugged = true
            connected = now
            // Our own attach and every mode change fire a burst of these; act once it settles.
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ val r = replugged; replugged = false; worker.execute { check(r) } }, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_mounted), NotificationManager.IMPORTANCE_LOW)
        )
        // USB_STATE is a hidden-constant, protected system broadcast, so exporting the receiver is safe.
        ContextCompat.registerReceiver(
            this, usbState, IntentFilter(USB_STATE), ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        when (intent?.action) {
            EJECT -> worker.execute { Engine.detach(this); events++; stopSelf() }
            INSERT -> worker.execute { check(replugged = true) }
        }
        return START_STICKY
    }

    private fun check(replugged: Boolean) {
        val want = Engine.wanted(this).ifEmpty { return stopSelf() }
        val info = Engine.run(this, "info")
        val luns = info.luns
        val empty = luns.size < want.size || luns.any { it.path.isEmpty() }
        ejected = if (info.values["linked"] != "1" || (empty && replugged)) !Engine.attach(this, want).ok else empty
        events++
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }

    private fun notification(): Notification {
        val want = Engine.wanted(this)
        val names = want.joinToString(", ") { it.path.substringAfterLast('/') }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bootstick)
            .setContentTitle(getString(if (ejected) R.string.notif_ejected else R.string.notif_mounted, names))
            .setContentText(
                if (ejected) getString(R.string.notif_ejected_body)
                else want.joinToString(" · ") { getString(modeLabel(it.mode)) },
            )
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.eject), action(EJECT)).build())
            .apply { if (ejected) addAction(Notification.Action.Builder(null, getString(R.string.reinsert), action(INSERT)).build()) }
            .build()
    }

    private fun action(name: String) = PendingIntent.getService(
        this, name.hashCode(), Intent(this, MountService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        unregisterReceiver(usbState)
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CHANNEL = "mounted"
        private const val EJECT = "eject"
        const val INSERT = "insert"

        /** Bumped whenever the service changes what's mounted, so the open app can refresh. */
        var events by mutableIntStateOf(0)

        fun start(ctx: Context, action: String? = null) =
            ctx.startForegroundService(Intent(ctx, MountService::class.java).setAction(action))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, MountService::class.java))
    }
}

/** Mounts the images again after the phone restarts, when the user switched that on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && Engine.autoMount(c) && Engine.wanted(c).isNotEmpty()) {
            MountService.start(c, MountService.INSERT)
        }
    }
}

/** Quick settings tile: ejects what's mounted, or mounts again what was mounted last. */
class MountTile : TileService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() = update()

    private fun update() {
        val tile = qsTile ?: return
        val want = Engine.wanted(this)
        val last = Engine.last(this)
        tile.state = when {
            want.isNotEmpty() -> Tile.STATE_ACTIVE
            last.isEmpty() -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = want.ifEmpty { last }.firstOrNull()?.path?.substringAfterLast('/')
                ?: getString(R.string.tile_nothing)
        }
        tile.updateTile()
    }

    // Unlock first: mounting from the lock screen would hand the phone's images to whoever holds it.
    override fun onClick() = unlockAndRun {
        val mounted = Engine.wanted(this).isNotEmpty()
        val last = Engine.last(this)
        if (!mounted && last.isEmpty()) return@unlockAndRun
        thread {
            val r = if (mounted) Engine.detach(this).also { MountService.stop(this) } else Engine.attach(this, last)
            // Android may refuse a foreground service started from the tile; the drive is mounted either way,
            // just without the notification until the app is opened.
            if (!mounted && r.ok) runCatching { MountService.start(this) }
            MountService.events++
            main.post {
                Toast.makeText(
                    this,
                    if (!r.ok) r.error else getString(if (mounted) R.string.ejected else R.string.tile_mounted),
                    Toast.LENGTH_SHORT,
                ).show()
                update()
            }
        }
    }
}
