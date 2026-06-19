package com.estrin217.terminal.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    initialPath: File = TerminalConfig.getRootfsDir(LocalContext.current),
    onBackPressed: () -> Unit
) {
    var currentDir by remember { mutableStateOf(initialPath) }
    val context = LocalContext.current
    val rootfs = TerminalConfig.getRootfsDir(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("File Manager", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = currentDir.relativeToOrNull(rootfs)?.path ?: "/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (currentDir != rootfs) {
                        IconButton(onClick = { currentDir = currentDir.parentFile ?: rootfs }) {
                            Icon(Icons.Outlined.ArrowUpward, contentDescription = "Up")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        val children = remember(currentDir) {
            currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyArray()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(children) { file ->
                FileRow(
                    file = file,
                    rootfs = rootfs,
                    onClick = {
                        if (file.isDirectory) {
                            currentDir = file
                        }
                    }
                )
            }

            if (children.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Empty directory",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: File,
    rootfs: File,
    onClick: () -> Unit
) {
    val isDir = file.isDirectory
    val isSymlink = java.nio.file.Files.isSymbolicLink(file.toPath())
    val relativePath = file.relativeToOrNull(rootfs)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    isDir -> Icons.Outlined.Folder
                    isSymlink -> Icons.Outlined.Shortcut
                    file.name.endsWith(".sh") -> Icons.Outlined.Terminal
                    file.name.endsWith(".so") -> Icons.Outlined.Memory
                    else -> Icons.Outlined.Description
                },
                contentDescription = null,
                tint = when {
                    isDir -> MaterialTheme.colorScheme.primary
                    isSymlink -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSymlink) {
                        Text(
                            text = "-> ${java.nio.file.Files.readSymbolicLink(file.toPath())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (!isDir) {
                        Text(
                            text = formatFileSize(file.length()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun File.relativeToOrNull(base: File): File? {
    return try {
        this.relativeTo(base)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
