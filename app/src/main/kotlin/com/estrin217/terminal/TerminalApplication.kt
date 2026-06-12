package com.estrin217.terminal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.estrin217.terminal.core.logger.DebugLogger
import com.estrin217.terminal.core.logger.DiagnosticPipeline
import com.estrin217.terminal.core.logger.DiskSink
import com.estrin217.terminal.core.logger.LogcatSink
import com.estrin217.terminal.core.logger.DebugLoggerSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TerminalApplication : Application() {

    companion object {
        lateinit var sessionId: String
            private set
        lateinit var instance: TerminalApplication
            private set
        var pendingCrashReportPath: String? = null
            private set
        private const val CRASH_NOTIFICATION_ID = 1001
        private const val CRASH_CHANNEL_ID = "crash_reports"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionId = UUID.randomUUID().toString().take(8)

        DiagnosticPipeline.setSessionId(sessionId)
        DebugLogger.initCrashHandler(this)
        DebugLogger.i("TerminalApplication", "App started. Session: $sessionId")

        val logDir = File(filesDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()
        val logFile = File(logDir, "diagnostic_$sessionId.log")

        DiagnosticPipeline.registerSink(LogcatSink())
        DiagnosticPipeline.registerSink(DiskSink(logFile, maxFileSize = 5 * 1024 * 1024, maxRotatedFiles = 3))
        DiagnosticPipeline.registerSink(DebugLoggerSink())

        DebugLogger.i("TerminalApplication", "DiagnosticPipeline initialized with LogcatSink, DiskSink, DebugLoggerSink")

        checkForPreviousCrash()
    }

    private fun checkForPreviousCrash() {
        if (!DebugLogger.hasPendingCrashReport(this)) return

        val crashPath = DebugLogger.getPendingCrashReportPath(this)
        pendingCrashReportPath = crashPath
        DebugLogger.clearCrashFlag(this)

        DebugLogger.w("TerminalApplication", "Previous crash detected. Report: $crashPath")

        showCrashNotification(crashPath)
    }

    private fun showCrashNotification(crashPath: String?) {
        createCrashNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_crash_dialog", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CRASH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Crash detectado")
            .setContentText("La aplicación se cerró inesperadamente en la sesión anterior")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(CRASH_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // No notification permission — will show dialog on next activity launch
        }
    }

    private fun createCrashNotificationChannel() {
        val channel = NotificationChannel(
            CRASH_CHANNEL_ID,
            "Reportes de crash",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notificaciones de cierres inesperados de la aplicación"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun exportCombinedLogToShared() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "terminal_combined_log_$timestamp.log"

            val logDir = File(filesDir, "logs")
            val diagnosticLogs = logDir.listFiles { f -> f.name.startsWith("diagnostic_") }
                ?.sortedDescending()
                ?.firstOrNull()

            val combined = buildString {
                appendLine("=" .repeat(50))
                appendLine("  Combined Diagnostic Log - Project Terminal")
                appendLine("=" .repeat(50))
                appendLine()

                appendLine("--- DebugLogger in-memory logs (${DebugLogger.getLogCount()} total) ---")
                appendLine(DebugLogger.getLogsAsText())
                appendLine()

                if (diagnosticLogs != null) {
                    appendLine("--- DiskSink diagnostic file ---")
                    appendLine(diagnosticLogs.readText())
                }

                appendLine()
                appendLine("--- Statistics ---")
                appendLine(DebugLogger.getStatistics())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        out.write(combined.toByteArray())
                    }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = File(downloadsDir, fileName)
                dest.writeText(combined)
            }
            DebugLogger.i("TerminalApplication", "Combined log exported to Downloads: $fileName")
        } catch (e: Exception) {
            DebugLogger.e("TerminalApplication", "Failed to export combined log", e)
        }
    }
}
