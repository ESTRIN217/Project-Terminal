package com.estrin217.terminal.core.logger

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
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
    @Volatile
    var verbose: Boolean = false

    data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val threadName: String, // ¡Nuevo!
    val message: String,
    val component: String? = null,
    val sessionId: String? = null,
    val pid: String? = null
    ) {
    override fun toString(): String {
        return buildString {
            append("[$timestamp] [$level] [$threadName] [$tag]")
            component?.let { append(" [component=$it]") }
            sessionId?.let { append(" [session=$it]") }
            pid?.let { append(" [pid=$it]") }
            append(" $message")
        }
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
    private fun addLog(level: LogLevel, customTag: String, message: String) {
    synchronized(logLock) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val autoTag = getAutoTagAndLine()
        val finalTag = if (customTag.isEmpty()) autoTag else "$customTag -> $autoTag"
        val threadName = Thread.currentThread().name

        logs.add(LogEntry(timestamp, level, finalTag, threadName, message))

        if (logs.size > maxLogs) {
            logs.removeAt(0)
        }
    }
    }

    fun addDiagnosticLog(log: com.estrin217.terminal.core.logger.DiagnosticLog) {
        synchronized(logLock) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
            val logLevel = when (log.severity) {
                com.estrin217.terminal.core.logger.LogSeverity.TRACE,
                com.estrin217.terminal.core.logger.LogSeverity.DEBUG -> LogLevel.DEBUG
                com.estrin217.terminal.core.logger.LogSeverity.INFO -> LogLevel.INFO
                com.estrin217.terminal.core.logger.LogSeverity.WARN -> LogLevel.WARNING
                com.estrin217.terminal.core.logger.LogSeverity.ERROR -> LogLevel.ERROR
            }
            val entry = LogEntry(
                timestamp = timestamp,
                level = logLevel,
                tag = "[${log.component.name}] ${log.threadName}",
                threadName = log.threadName,
                message = log.message,
                component = log.component.name,
                sessionId = log.metadata["session"],
                pid = log.metadata["pid"]
            )
            logs.add(entry)
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
    private fun getAutoTagAndLine(): String {
    val stackTrace = Throwable().stackTrace
    // Buscamos el primer elemento fuera de la clase DebugLogger
    val element = stackTrace.firstOrNull { it.className != DebugLogger::class.java.name }
    return if (element != null) {
        val simpleClassName = element.className.substringAfterLast('.')
        "$simpleClassName.${element.methodName}() [Line ${element.lineNumber}]"
    } else {
        "UnknownSource"
    }
    }
    fun initCrashHandler(context: Context) {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val stackTraceStr = writer.toString()

        // Registramos el error de forma síncrona en nuestro logger
        e("CRASH", "La aplicación se cerró inesperadamente en el hilo: ${thread.name}", throwable)
        
        // Forzamos la exportación inmediata a un archivo de emergencia
        exportLogsToFile(context)

        // Devolvemos el control al sistema operativo para que la app cierre correctamente
        defaultHandler?.uncaughtException(thread, throwable)
    }
    }
}
