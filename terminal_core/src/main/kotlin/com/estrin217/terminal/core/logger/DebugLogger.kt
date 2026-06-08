package com.estrin217.terminal.core.logger

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sistema centralizado de logging para debugging
 */
object DebugLogger {
    private val logs = mutableListOf<LogEntry>()
    private val maxLogs = 5000 // Máximo de logs en memoria
    private val logLock = Any()

    data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val tag: String,
        val message: String
    ) {
        override fun toString(): String {
            return "[$timestamp] [$level] [$tag] $message"
        }
    }

    enum class LogLevel {
        DEBUG, INFO, WARNING, ERROR
    }

    /**
     * Registra un mensaje de debug
     */
    fun d(tag: String, message: String) {
        addLog(LogLevel.DEBUG, tag, message)
    }

    /**
     * Registra un mensaje de información
     */
    fun i(tag: String, message: String) {
        addLog(LogLevel.INFO, tag, message)
    }

    /**
     * Registra un mensaje de advertencia
     */
    fun w(tag: String, message: String) {
        addLog(LogLevel.WARNING, tag, message)
    }

    /**
     * Registra un mensaje de error
     */
    fun e(tag: String, message: String, exception: Throwable? = null) {
        val fullMessage = if (exception != null) {
            "$message\n${exception.stackTraceToString()}"
        } else {
            message
        }
        addLog(LogLevel.ERROR, tag, fullMessage)
    }

    /**
     * Agrega un log a la lista
     */
    private fun addLog(level: LogLevel, tag: String, message: String) {
        synchronized(logLock) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logs.add(LogEntry(timestamp, level, tag, message))

            // Limitar el tamaño de los logs
            if (logs.size > maxLogs) {
                logs.removeAt(0)
            }
        }
    }

    /**
     * Obtiene todos los logs actuales
     */
    fun getLogs(): List<LogEntry> {
        return synchronized(logLock) {
            logs.toList()
        }
    }

    /**
     * Obtiene los logs filtrados por nivel
     */
    fun getLogsByLevel(level: LogLevel): List<LogEntry> {
        return synchronized(logLock) {
            logs.filter { it.level == level }
        }
    }

    /**
     * Obtiene los logs filtrados por tag
     */
    fun getLogsByTag(tag: String): List<LogEntry> {
        return synchronized(logLock) {
            logs.filter { it.tag.contains(tag, ignoreCase = true) }
        }
    }

    /**
     * Limpia todos los logs
     */
    fun clearLogs() {
        synchronized(logLock) {
            logs.clear()
        }
    }

    /**
     * Obtiene los logs como texto formateado
     */
    fun getLogsAsText(): String {
        return synchronized(logLock) {
            if (logs.isEmpty()) {
                "No logs available"
            } else {
                logs.joinToString("\n")
            }
        }
    }

    /**
     * Exporta los logs a un archivo
     */
    fun exportLogsToFile(context: Context): String? {
        return try {
            synchronized(logLock) {
                val fileName = "terminal_logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
                val file = File(context.getExternalFilesDir(null), fileName)

                file.parentFile?.mkdirs()

                BufferedWriter(FileWriter(file)).use { writer ->
                    logs.forEach { log ->
                        writer.write(log.toString())
                        writer.newLine()
                    }
                }

                file.absolutePath
            }
        } catch (e: Exception) {
            e(this::class.simpleName.orEmpty(), "Error exporting logs", e)
            null
        }
    }

    /**
     * Importa logs desde un archivo
     */
    fun importLogsFromFile(filePath: String): Boolean {
        return try {
            synchronized(logLock) {
                val file = File(filePath)
                if (!file.exists()) {
                    e(this::class.simpleName.orEmpty(), "File not found: $filePath")
                    return false
                }

                BufferedReader(FileReader(file)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            i("IMPORT", line)
                        }
                    }
                }

                true
            }
        } catch (e: Exception) {
            e(this::class.simpleName.orEmpty(), "Error importing logs", e)
            false
        }
    }

    /**
     * Obtiene el número actual de logs
     */
    fun getLogCount(): Int {
        return synchronized(logLock) {
            logs.size
        }
    }

    /**
     * Obtiene estadísticas de logs
     */
    fun getStatistics(): String {
        return synchronized(logLock) {
            val total = logs.size
            val debug = logs.count { it.level == LogLevel.DEBUG }
            val info = logs.count { it.level == LogLevel.INFO }
            val warning = logs.count { it.level == LogLevel.WARNING }
            val error = logs.count { it.level == LogLevel.ERROR }

            """
                Total Logs: $total
                Debug: $debug
                Info: $info
                Warning: $warning
                Error: $error
            """.trimIndent()
        }
    }
}
