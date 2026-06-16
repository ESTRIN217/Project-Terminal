package com.estrin217.terminal.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import androidx.core.app.NotificationCompat
import com.estrin217.terminal.core.logger.DebugLogger
import com.termux.terminal.TerminalSession
import java.io.File

class TerminalService : Service() {

    private val binder = TerminalServiceBinder()
    var currentSession: TerminalSession? = null
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiLock? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "terminal_service_channel"
        private const val CHANNEL_NAME = "Terminal Background Service"
        private const val WAKE_LOCK_TAG = "TerminalService:WakeLock"
        private const val WIFI_LOCK_TAG = "TerminalService:WifiLock"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        DebugLogger.i("TerminalService", "onCreate invoked")
        val loaded = TerminalCore.ensureLoaded(this)
        if (loaded) {
            DebugLogger.i("TerminalService", "Native libraries loaded successfully")
        } else {
            DebugLogger.e("TerminalService", "Native libraries failed to load")
        }

        val termuxExecOk = NativeLibInstaller.ensureTermuxExecLib(this)
        if (termuxExecOk) {
            DebugLogger.i("TerminalService", "libtermux_exec.so deployed for LD_PRELOAD")
        } else {
            DebugLogger.w("TerminalService", "libtermux_exec.so not deployed - W^X bypass may not work")
        }

        acquireWakeLock()
        acquireWifiLock()

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun createOrGetSession(context: Context, bridge: TerminalBridge): TerminalSession {
        currentSession?.let { return it }

        val rootfsTmp = File(TerminalConfig.getRootfsDir(context), "tmp")
        if (!rootfsTmp.exists()) {
            rootfsTmp.mkdirs()
            DebugLogger.w("TerminalService", "PROOT_TMP_DIR did not exist, created: ${rootfsTmp.absolutePath}")
        }
        rootfsTmp.setWritable(true, false)
        rootfsTmp.setExecutable(true, false)
        rootfsTmp.setReadable(true, false)

        val shellPath = TerminalConfig.getPRootExecutable(context).absolutePath
        val cwd = context.filesDir.absolutePath
        val args = TerminalConfig.getPRootArgs(context, "/bin/sh")
        val env = TerminalConfig.getEnvironmentVariables(context)

        DebugLogger.i("TerminalService", "Creating session: shellPath=$shellPath, cwd=$cwd")
        args.forEachIndexed { i, arg -> DebugLogger.d("TerminalService", "  args[$i]=\"$arg\"") }
        env.forEach { e -> DebugLogger.d("TerminalService", "  env: $e") }

        val session = TerminalSession(
            shellPath,
            cwd,
            args,
            env,
            10000,
            bridge
        )

        currentSession = session
        DebugLogger.i("TerminalService", "Session created: handle=${session.mHandle}")
        return session
    }

    fun stopSession() {
        currentSession?.finishIfRunning()
        currentSession = null
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        releaseWakeLock()
        releaseWifiLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Terminal Aislada")
            .setContentText("El entorno Linux está ejecutándose en segundo plano.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
            DebugLogger.i("TerminalService", "WakeLock acquired")
        } catch (e: Exception) {
            DebugLogger.e("TerminalService", "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    DebugLogger.i("TerminalService", "WakeLock released")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e("TerminalService", "Error releasing WakeLock", e)
        }
        wakeLock = null
    }

    private fun acquireWifiLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lockType = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifiManager.createWifiLock(
                lockType,
                WIFI_LOCK_TAG
            )
            wifiLock?.acquire()
            DebugLogger.i("TerminalService", "WifiLock acquired")
        } catch (e: Exception) {
            DebugLogger.e("TerminalService", "Failed to acquire WifiLock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    DebugLogger.i("TerminalService", "WifiLock released")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e("TerminalService", "Error releasing WifiLock", e)
        }
        wifiLock = null
    }

    inner class TerminalServiceBinder : Binder() {
        fun getService(): TerminalService = this@TerminalService
    }
}
