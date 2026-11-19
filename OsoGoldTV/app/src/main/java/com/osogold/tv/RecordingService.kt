package com.osogold.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a recording alive for hours: a foreground service with a notification (running timer and
 * a stop button), a CPU wake lock and a Wi-Fi lock. The screen can be off and the app in the
 * background. Only one recording at a time.
 */
class RecordingService : Service() {

    private var recorder: StreamRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishRecording()
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                val name = intent?.getStringExtra(EXTRA_NAME) ?: "stream"
                if (url == null) {
                    stopSelf()
                } else {
                    beginRecording(url, name)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun beginRecording(url: String, name: String) {
        startAsForeground(name)
        if (recorder?.isRecording == true) return
        val rec = StreamRecorder(applicationContext)
        rec.onWorkerEnded = {
            if (rec.lowSpace) lastMessage = "توقف التسجيل: مساحة الهاتف شبه ممتلئة"
            finishRecording()
        }
        if (!rec.start(url, name)) {
            lastMessage = "تعذر بدء التسجيل (تحقق من صلاحية التخزين)"
            notifyState(false, null)
            stopForegroundCompat()
            stopSelf()
            return
        }
        recorder = rec
        startedAt = System.currentTimeMillis()
        title = name
        active = true
        acquireLocks()
        notifyState(true, null)
    }

    private fun finishRecording() {
        val rec = recorder
        if (rec == null || !rec.isRecording) {
            if (rec == null) {
                active = false
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        active = false
        rec.stopAndSave { path ->
            releaseLocks()
            recorder = null
            notifyState(false, path)
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ------------------------------------------------------------------ notification

    private fun startAsForeground(name: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "التسجيل", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(name), type)
    }

    private fun buildNotification(name: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("● يتم التسجيل")
            .setContentText(name)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "إيقاف وحفظ", stop)
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun notifyState(recording: Boolean, savedPath: String?) {
        main.post { listener?.invoke(recording, savedPath) }
    }

    // ------------------------------------------------------------------ locks

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsoGoldTV:rec").apply {
                setReferenceCounted(false)
                acquire(12L * 60L * 60L * 1000L) // never longer than 12 hours
            }
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OsoGoldTV:rec").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        if (recorder?.isRecording == true) {
            // the system is destroying us: save what we have
            recorder?.stopAndSave { path -> notifyState(false, path) }
            active = false
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "oso_recording"
        private const val NOTIFICATION_ID = 77
        private const val ACTION_STOP = "com.osogold.tv.STOP_RECORDING"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        @Volatile
        var active = false
            private set

        @Volatile
        var startedAt = 0L
            private set

        @Volatile
        var title = ""
            private set

        @Volatile
        var lastMessage: String? = null

        /** (isRecording, savedPath) - always on the main thread. Set by the visible screen. */
        var listener: ((Boolean, String?) -> Unit)? = null

        fun start(context: Context, url: String, name: String) {
            lastMessage = null
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_NAME, name)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
