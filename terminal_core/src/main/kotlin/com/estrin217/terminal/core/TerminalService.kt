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
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import java.io.File

class TerminalService : Service() {

    private val binder = TerminalServiceBinder()
    var currentSession: TerminalSession? = null
        private set

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "terminal_service_channel"
        private const val CHANNEL_NAME = "Terminal Background Service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep service running until explicitly stopped
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Spawns a new TerminalSession running under PRoot, or returns the current one.
     */
    fun createOrGetSession(context: Context, bridge: TerminalBridge): TerminalSession {
        currentSession?.let { return it }

        val shellPath = TerminalConfig.getPRootExecutable(context).absolutePath
        val cwd = context.filesDir.absolutePath
        val args = TerminalConfig.getPRootArgs(context, "/bin/sh")
        val env = TerminalConfig.getEnvironmentVariables(context)

        val session = TerminalSession(
            shellPath,
            cwd,
            args,
            env,
            10000, // transcript rows
            bridge
        )

        currentSession = session
        return session
    }

    fun stopSession() {
        currentSession?.finishIfRunning()
        currentSession = null
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
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
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Default generic icon
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    inner class TerminalServiceBinder : Binder() {
        fun getService(): TerminalService = this@TerminalService
    }
}
