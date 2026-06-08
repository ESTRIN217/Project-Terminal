package com.estrin217.terminal.logger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
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
            Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show() // [cite: 29]
            return
        }

        val logsText = logsList.joinToString("\n") // [cite: 29]
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Logs", logsText) // [cite: 29]
        clipboard.setPrimaryClip(clip) // [cite: 29]

        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show() // [cite: 30]
    }

    fun exportAndShareLogs(context: Context) {
        val filePath = DebugLogger.exportLogsToFile(context) // [cite: 31]
        if (filePath != null) {
            Toast.makeText(context, "Logs exported to: $filePath", Toast.LENGTH_SHORT).show() // [cite: 31]

            try {
                val file = File(filePath) // [cite: 31]
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                ) // [cite: 31]

                val intent = Intent().apply {
                    action = Intent.ACTION_SEND // [cite: 32]
                    putExtra(Intent.EXTRA_STREAM, uri) // [cite: 32]
                    type = "text/plain" // [cite: 32]
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // [cite: 32]
                }

                context.startActivity(Intent.createChooser(intent, "Share logs")) // [cite: 33]
            } catch (e: Exception) {
                DebugLogger.e("LoggerActivity", "Error sharing file", e) // [cite: 33]
            }
        } else {
            Toast.makeText(context, "Error exporting logs", Toast.LENGTH_SHORT).show() // [cite: 34]
        }
    }

    fun clearAllLogs() {
        DebugLogger.clearLogs() // [cite: 35]
        refreshLogs() // [cite: 35]
    }
}