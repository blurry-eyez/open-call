package xyz.s2105.opencall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the call alive when the app is in the background or the screen is off (foreground service with
 * microphone / camera / mediaProjection types), shows the ongoing-call notification with Mute and Hang up,
 * and receives the screen-capture permission result to start sharing.
 */
class CallService : Service() {
    companion object {
        const val CHANNEL = "call"
        const val NID = 1
        const val A_START = "xyz.s2105.opencall.START"
        const val A_STOP = "xyz.s2105.opencall.STOP"
        const val A_MUTE = "xyz.s2105.opencall.MUTE"
        const val A_SHARE = "xyz.s2105.opencall.SHARE"

        fun start(ctx: Context, code: String, name: String, wantCam: Boolean) {
            val i = Intent(ctx, CallService::class.java).setAction(A_START)
                .putExtra("code", code).putExtra("name", name).putExtra("cam", wantCam)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun share(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startService(Intent(ctx, CallService::class.java).setAction(A_SHARE)
                .putExtra("rc", resultCode).putExtra("data", data))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastSharing = false
    private var lastKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_START -> {
                val code = intent.getStringExtra("code") ?: ""
                if (!goForeground(false, CallEngine.ui.value.copy(code = code))) { stopSelf(); return START_NOT_STICKY }
                acquireLocks()
                CallEngine.join(code, intent.getStringExtra("name") ?: "Guest", intent.getBooleanExtra("cam", true))
                observe()
            }
            A_SHARE -> {
                val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)
                val rc = intent.getIntExtra("rc", 0)
                if (data != null && CallEngine.busy) {
                    // Android 14+: the mediaProjection service type must be active BEFORE getMediaProjection()
                    goForeground(true, CallEngine.ui.value)
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        CallEngine.startScreen(mpm.getMediaProjection(rc, data) ?: throw IllegalStateException("no projection"))
                    } catch (e: Exception) {
                        CallEngine.events.tryEmit("Could not start screen sharing")
                        goForeground(false, CallEngine.ui.value)
                    }
                }
            }
            A_MUTE -> CallEngine.toggleMic()
            A_STOP -> CallEngine.leave()
            else -> if (!CallEngine.busy) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        observer?.cancel()
        observer = scope.launch {
            CallEngine.ui.collect { ui ->
                if (ui.phase == Phase.Idle) { shutdown(); return@collect }
                val key = "${ui.code}|${ui.micOn}|${ui.sharing}|${ui.phase}"
                if (key != lastKey) { lastKey = key; goForeground(ui.sharing, ui) }
            }
        }
    }

    private fun shutdown() {
        observer?.cancel()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(sharing: Boolean, ui: CallUi): Boolean {
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            if (Build.VERSION.SDK_INT >= 30 && has(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= 30 && has(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (sharing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (type == 0) return false
        }
        lastSharing = sharing
        return try {
            ServiceCompat.startForeground(this, NID, notification(ui, sharing), type)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun notification(ui: CallUi, sharing: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(a: String, req: Int) = PendingIntent.getService(this, req,
            Intent(this, CallService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = buildString {
            append(when (ui.phase) { Phase.InCall -> "In call"; Phase.Waiting -> "Waiting to be let in"; else -> "Connecting" })
            if (ui.code.isNotEmpty()) append(" · ").append(ui.code)
            if (!ui.micOn) append(" · muted")
            if (sharing) append(" · sharing screen")
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Open Call")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(if (ui.micOn) R.drawable.ic_mic_off else R.drawable.ic_mic, if (ui.micOn) "Mute" else "Unmute", act(A_MUTE, 2))
            .addAction(R.drawable.ic_call_end, "Hang up", act(A_STOP, 3))
            .build()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencall:call").apply { setReferenceCounted(false); acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "opencall:wifi").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null; wifi = null
    }

    override fun onDestroy() {
        releaseLocks()
        if (CallEngine.busy) CallEngine.leave()
        scope.cancel()
        super.onDestroy()
    }
}
