package com.dropfly.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Does the actual sending, as a foreground service. That is what keeps big files going
 * when you leave the app / lock the phone. Progress is shown as a thin notification bar.
 */
class UploadService : Service() {

    companion object {
        private const val CH = "dropfly_transfers"
        private const val NOTIF_ID = 42

        fun start(ctx: Context, target: Target, uris: List<Uri>, text: String?) {
            val i = Intent(ctx, UploadService::class.java)
            i.putExtra("host", target.host)
            i.putExtra("port", target.port)
            i.putExtra("name", target.name)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri("files", uris[0])
                for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
                i.clipData = clip
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (text != null) i.putExtra("text", text)
            ctx.startForegroundService(i)
        }
    }

    private val pool = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var active = 0
    @Volatile private var anyFail = false
    @Volatile private var lastName = "laptop"
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "File transfers", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val first = build("Sending...", "Starting", 0, true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }

        val target = Target(
            intent.getStringExtra("host") ?: "",
            intent.getIntExtra("port", 53317),
            intent.getStringExtra("name") ?: "laptop"
        )
        lastName = target.name
        val text = intent.getStringExtra("text")
        val uris = ArrayList<Uri>()
        val clip = intent.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) {
                val u = clip.getItemAt(k).uri
                if (u != null) uris.add(u)
            }
        }
        val code = Sender.prefs(this).getString("code", "") ?: ""

        synchronized(lock) {
            active++
            if (active == 1) acquireLocks()
        }
        pool.execute { runJob(target, code, uris, text) }
        return START_NOT_STICKY
    }

    private fun runJob(target: Target, code: String, uris: List<Uri>, text: String?) {
        var ok = true
        var lastUi = 0L
        try {
            if (text != null && uris.isEmpty()) ok = Sender.sendText(target, code, text)
            for ((idx, u) in uris.withIndex()) {
                val label = if (uris.size > 1) "File ${idx + 1} of ${uris.size}" else "Sending file"
                val good = Sender.sendFile(this, target, code, u) { sent, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 500) {
                        lastUi = now
                        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                        nm.notify(
                            NOTIF_ID,
                            build("$label to ${target.name}", "$pct%  -  ${size(sent)} of ${size(total)}", pct, true)
                        )
                    }
                }
                if (!good) ok = false
            }
        } catch (e: Exception) {
            ok = false
        }
        if (!ok) anyFail = true

        synchronized(lock) {
            active--
            if (active == 0) {
                releaseLocks()
                val done = build(
                    if (anyFail) "Couldn't send everything" else "Sent to $lastName",
                    if (anyFail) "Check that DropFly is running on the laptop" else "",
                    100, false
                )
                nm.notify(NOTIF_ID, done)
                stopForeground(Service.STOP_FOREGROUND_DETACH)
                anyFail = false
                stopSelf()
            }
        }
    }

    private fun size(b: Long): String =
        if (b >= 1_073_741_824L) String.format("%.2f GB", b / 1_073_741_824.0)
        else String.format("%.1f MB", b / 1_048_576.0)

    private fun build(title: String, text: String, pct: Int, ongoing: Boolean): Notification {
        val b = Notification.Builder(this, CH)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (ongoing) {
            b.setProgress(100, pct, pct == 0)
        } else {
            b.setAutoCancel(true).setTimeoutAfter(5000)
        }
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dropfly:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dropfly:upload").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wifi?.let { if (it.isHeld) it.release() }
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        releaseLocks()
        pool.shutdown()
        super.onDestroy()
    }
}
