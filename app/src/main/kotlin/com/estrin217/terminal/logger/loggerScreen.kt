package com.estrin217.terminal.logger

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.estrin217.terminal.core.logger.DebugLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggerScreen(
    onBackPressed: () -> Unit,
    viewModel: LoggerViewModel = viewModel()
) {
    val context = LocalContext.current
    val filteredLogs by viewModel.filteredLogs.collectAsState()
    val selectedLevel by viewModel.selectedLevel.collectAsState()
    val statistics by viewModel.statistics.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    // Diálogo de confirmación para limpiar logs
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Logs?") },
            text = { Text("Are you sure you want to clear all logs?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllLogs()
                    showClearDialog = false
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("No") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Logger", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(imageVector = Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Sección de estadísticas en un contenedor moderno MD3 Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(
                    text = statistics,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // Filtro por Chips Horizontales (Sustituye al clásico Spinner de forma limpia) 
            Text(
                text = "Filter Level:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val levels = listOf("All", "DEBUG", "INFO", "WARNING", "ERROR") // 
                levels.forEach { level ->
                    FilterChip(
                        selected = selectedLevel == level,
                        onClick = { viewModel.changeFilter(level) },
                        label = { Text(level) }
                    )
                }
            }

            // Lista de Logs optimizada con LazyColumn (No recicla destructivamente los estados) 
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs) { log ->
                    LogItemRow(log = log)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fila de botones de acción inferiores utilizando iconos estructuradamente Outlined
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val buttonModifier = Modifier.weight(1f)

                OutlinedButton(onClick = { viewModel.refreshLogs() }, modifier = buttonModifier, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh", fontSize = 11.sp)
                }

                OutlinedButton(onClick = { viewModel.copyLogsToClipboard(context) }, modifier = buttonModifier, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Outlined.Share, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 11.sp)
                }

                OutlinedButton(onClick = { viewModel.exportAndShareLogs(context) }, modifier = buttonModifier, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Outlined.Email, contentDescription = "Export", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export", fontSize = 11.sp)
                }

                Button(
                    onClick = { showClearDialog = true },
                    modifier = buttonModifier,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", fontSize = 11.sp, color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}

@Composable
fun LogItemRow(log: DebugLogger.LogEntry) {
    // Definimos esquemas de color adaptados según las prioridades de logs
    val badgeColor = when (log.level) {
        DebugLogger.LogLevel.DEBUG -> Color(0xFF4CAF50)
        DebugLogger.LogLevel.INFO -> Color(0xFF2196F3)
        DebugLogger.LogLevel.WARNING -> Color(0xFFFFC107)
        DebugLogger.LogLevel.ERROR -> Color(0xFFF44336)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = badgeColor,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = log.level.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text(
                text = log.timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = log.tag,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}