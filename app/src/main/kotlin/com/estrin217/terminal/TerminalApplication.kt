package com.estrin217.terminal

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.estrin217.terminal.core.logger.DebugLogger
import com.estrin217.terminal.core.logger.DiagnosticPipeline
import com.estrin217.terminal.core.logger.DiskSink
import com.estrin217.terminal.core.logger.LogcatSink
import com.estrin217.terminal.core.logger.DebugLoggerSink
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
    }

    fun exportDiagnosticLogToShared() {
        try {
            val logDir = File(filesDir, "logs")
            val logFiles = logDir.listFiles { f -> f.name.startsWith("diagnostic_") }?.sortedDescending()
            val latestLog = logFiles?.firstOrNull() ?: return

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "terminal_diagnostic_$timestamp.log"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        latestLog.inputStream().use { inp -> inp.copyTo(out) }
                    }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = File(downloadsDir, fileName)
                latestLog.copyTo(dest, overwrite = true)
            }
            DebugLogger.i("TerminalApplication", "Diagnostic log exported to Downloads: $fileName")
        } catch (e: Exception) {
            DebugLogger.e("TerminalApplication", "Failed to export diagnostic log", e)
        }
    }
}
