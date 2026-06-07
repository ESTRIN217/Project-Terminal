package com.estrin217.terminal.logger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.estrin217.terminal.R
import com.estrin217.terminal.databinding.ActivityLoggerBinding

class LoggerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoggerBinding
    private var currentLogs = listOf<DebugLogger.LogEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoggerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Debug Logger"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupUI()
        refreshLogs()
    }

    private fun setupUI() {
        // Botón para actualizar logs
        binding.btnRefresh.setOnClickListener {
            refreshLogs()
        }

        // Botón para copiar logs
        binding.btnCopy.setOnClickListener {
            copyLogsToClipboard()
        }

        // Botón para exportar logs
        binding.btnExport.setOnClickListener {
            exportLogs()
        }

        // Botón para limpiar logs
        binding.btnClear.setOnClickListener {
            clearLogs()
        }

        // Spinner para filtrar por nivel
        binding.spinnerLevel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("All", "DEBUG", "INFO", "WARNING", "ERROR")
        )
        binding.spinnerLevel.setSelection(0)

        // Listener para cambios en el spinner
        binding.spinnerLevel.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: android.view.View?, position: Int, p3: Long) {
                refreshLogs()
            }

            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        })
    }

    private fun refreshLogs() {
        val allLogs = DebugLogger.getLogs()

        // Filtrar por nivel seleccionado
        val selectedLevel = binding.spinnerLevel.selectedItem.toString()
        currentLogs = if (selectedLevel == "All") {
            allLogs
        } else {
            allLogs.filter { it.level.name == selectedLevel }
        }

        // Actualizar lista
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            currentLogs.map { it.toString() }
        )
        binding.logsList.adapter = adapter

        // Actualizar estadísticas
        updateStatistics()
    }

    private fun updateStatistics() {
        binding.statsText.text = DebugLogger.getStatistics()
    }

    private fun copyLogsToClipboard() {
        if (currentLogs.isEmpty()) {
            Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val logsText = currentLogs.joinToString("\n")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Logs", logsText)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogs() {
        val filePath = DebugLogger.exportLogsToFile(this)
        if (filePath != null) {
            Toast.makeText(this, "Logs exported to: $filePath", Toast.LENGTH_SHORT).show()

            // Opcionalmente, compartir el archivo
            try {
                val file = java.io.File(filePath)
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )

                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = "text/plain"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(intent, "Share logs"))
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Error sharing file", e)
            }
        } else {
            Toast.makeText(this, "Error exporting logs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLogs() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear Logs?")
            .setMessage("Are you sure you want to clear all logs?")
            .setPositiveButton("Yes") { _, _ ->
                DebugLogger.clearLogs()
                refreshLogs()
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_logger, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val TAG = "LoggerActivity"
    }
}
