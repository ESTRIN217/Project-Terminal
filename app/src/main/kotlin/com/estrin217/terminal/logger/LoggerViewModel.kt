package com.estrin217.terminal.logger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.estrin217.terminal.TerminalApplication
import com.estrin217.terminal.core.LocaleManager
import com.estrin217.terminal.core.logger.DebugLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class LoggerViewModel : ViewModel() {

    private val _selectedLevel = MutableStateFlow("All")
    val selectedLevel: StateFlow<String> = _selectedLevel.asStateFlow()

    private val _filteredLogs = MutableStateFlow<List<DebugLogger.LogEntry>>(emptyList())
    val filteredLogs: StateFlow<List<DebugLogger.LogEntry>> = _filteredLogs.asStateFlow()

    private val _statistics = MutableStateFlow("")
    val statistics: StateFlow<String> = _statistics.asStateFlow()

    init {
        refreshLogs()
    }

    fun changeFilter(level: String) {
        _selectedLevel.value = level
        refreshLogs()
    }

    fun refreshLogs() {
        val allLogs = DebugLogger.getLogs()
        _filteredLogs.value = if (_selectedLevel.value == "All") {
            allLogs
        } else {
            allLogs.filter { it.level.name == _selectedLevel.value }
        }
        _statistics.value = DebugLogger.getStatistics()
    }

    fun copyLogsToClipboard(context: Context) {
        val logsList = _filteredLogs.value
        if (logsList.isEmpty()) {
            Toast.makeText(context, LocaleManager.getString("no_logs_copy"), Toast.LENGTH_SHORT).show()
            return
        }

        val logsText = logsList.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(LocaleManager.getString("debug_logger_title"), logsText)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, LocaleManager.getString("logs_copied"), Toast.LENGTH_SHORT).show()
    }

    fun exportAndShareLogs(context: Context) {
        val filePath = DebugLogger.exportLogsToFile(context)
        if (filePath != null) {
            Toast.makeText(context, LocaleManager.getString("logs_exported", filePath), Toast.LENGTH_SHORT).show()

            try {
                val file = File(filePath)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = "text/plain"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(Intent.createChooser(intent, LocaleManager.getString("share_logs")))
            } catch (e: Exception) {
                DebugLogger.e("LoggerActivity", "Error sharing file", e)
            }
        } else {
            Toast.makeText(context, LocaleManager.getString("error_exporting_logs"), Toast.LENGTH_SHORT).show()
        }
    }

    fun clearAllLogs() {
        DebugLogger.clearLogs()
        refreshLogs()
    }

    fun exportCombinedLog() {
        TerminalApplication.instance.exportCombinedLogToShared()
    }

    fun saveLogsToDownloads(context: Context) {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val fileName = "terminal_logs_$timestamp.txt"
            val logsText = DebugLogger.getLogsAsText()

            if (logsText == "No logs available") {
                Toast.makeText(context, LocaleManager.getString("no_logs_copy"), Toast.LENGTH_SHORT).show()
                return
            }

            val combined = buildString {
                appendLine("=" .repeat(50))
                appendLine("  Terminal Logs - Project Terminal")
                appendLine("  Generated: $timestamp")
                appendLine("=" .repeat(50))
                appendLine()
                appendLine(logsText)
                appendLine()
                appendLine(DebugLogger.getStatistics())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TerminalLogs")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(combined.toByteArray())
                    }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val logsDir = File(dir, "TerminalLogs")
                logsDir.mkdirs()
                File(logsDir, fileName).writeText(combined)
            }
            Toast.makeText(context, "Logs saved to Downloads/TerminalLogs/$fileName", Toast.LENGTH_LONG).show()
            DebugLogger.i("LoggerViewModel", "Logs saved to Downloads: $fileName")
        } catch (e: Exception) {
            DebugLogger.e("LoggerViewModel", "Failed to save logs to Downloads", e)
            Toast.makeText(context, "Error saving logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}