package com.estrin217.terminal.core.logger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SystemComponent {
    INTERFACE,
    JNI_BRIDGE,
    PROOT_CORE,
    CONTAINER_DEBIAN
}

enum class LogSeverity { TRACE, DEBUG, INFO, WARN, ERROR }

data class DiagnosticLog(
    val timestamp: Long,
    val component: SystemComponent,
    val severity: LogSeverity,
    val threadName: String,
    val message: String,
    val exception: Throwable? = null,
    val metadata: Map<String, String> = emptyMap()
)

interface DiagnosticSink {
    fun dispatch(log: DiagnosticLog)
}

object DiagnosticPipeline {
    private val logChannel = Channel<DiagnosticLog>(capacity = 5000)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSinks = mutableListOf<DiagnosticSink>()

    init {
        scope.launch {
            logChannel.consumeAsFlow().collect { logEntry ->
                activeSinks.forEach { sink ->
                    try {
                        sink.dispatch(logEntry)
                    } catch (e: Exception) {
                        Log.e("DiagnosticPipeline", "Error en sink: ${sink.javaClass.name}", e)
                    }
                }
            }
        }
    }

    fun registerSink(sink: DiagnosticSink) {
        activeSinks.add(sink)
    }

    fun postLog(
        component: SystemComponent,
        severity: LogSeverity,
        message: () -> String,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        val entry = DiagnosticLog(
            timestamp = System.currentTimeMillis(),
            component = component,
            severity = severity,
            threadName = Thread.currentThread().name,
            message = message(),
            exception = throwable,
            metadata = metadata
        )
        logChannel.trySend(entry)
    }

    fun postLogBlocking(
        component: SystemComponent,
        severity: LogSeverity,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        val entry = DiagnosticLog(
            timestamp = System.currentTimeMillis(),
            component = component,
            severity = severity,
            threadName = Thread.currentThread().name,
            message = message,
            exception = throwable,
            metadata = metadata
        )
        runCatching { logChannel.trySend(entry) }
    }
}

class DiskSink(private val file: File) : DiagnosticSink {
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun dispatch(log: DiagnosticLog) {
        try {
            FileWriter(file, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    val timestampStr = format.format(Date(log.timestamp))
                    pw.print("[$timestampStr] [${log.severity.name}] [${log.component.name}] [Thread: ${log.threadName}] ")
                    pw.print(log.message)
                    if (log.metadata.isNotEmpty()) {
                        pw.print(" | Meta: ${log.metadata}")
                    }
                    pw.println()
                    log.exception?.let {
                        pw.println("--- Stack Trace ---")
                        it.printStackTrace(pw)
                        pw.println("--- End Stack Trace ---")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DiskSink", "Error writing to diagnostic log file", e)
        }
    }
}

class LogcatSink : DiagnosticSink {
    override fun dispatch(log: DiagnosticLog) {
        val tag = "Diag[${log.component.name}]"
        val msg = "[${log.threadName}] ${log.message}"

        when (log.severity) {
            LogSeverity.TRACE, LogSeverity.DEBUG -> Log.d(tag, msg)
            LogSeverity.INFO -> Log.i(tag, msg)
            LogSeverity.WARN -> Log.w(tag, msg)
            LogSeverity.ERROR -> Log.e(tag, msg, log.exception)
        }
    }
}

class DebugLoggerSink : DiagnosticSink {
    override fun dispatch(log: DiagnosticLog) {
        val tag = "[${log.component.name}] ${log.threadName}"
        val msg = log.message

        when (log.severity) {
            LogSeverity.TRACE, LogSeverity.DEBUG -> DebugLogger.d(tag, msg)
            LogSeverity.INFO -> DebugLogger.i(tag, msg)
            LogSeverity.WARN -> DebugLogger.w(tag, msg)
            LogSeverity.ERROR -> DebugLogger.e(tag, msg, log.exception)
        }
    }
}
