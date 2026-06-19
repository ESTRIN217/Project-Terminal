package com.estrin217.terminal.core

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === Distro Management ===
            SettingsSectionHeader("Distro Management")
            DistroManagementCard()

            HorizontalDivider()

            // === Customization ===
            SettingsSectionHeader("Customization")
            CustomizationCard()

            HorizontalDivider()

            // === Language ===
            SettingsSectionHeader("Language")
            LanguageSelectorCard()

            HorizontalDivider()

            // === About ===
            SettingsSectionHeader("About")
            AboutCard()
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun DistroManagementCard() {
    val context = LocalContext.current
    val distros = remember { ProotDistroManager.listDistros() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            distros.forEach { config ->
                val installed = ProotDistroManager.isDistroInstalled(context, config.id)
                val rootfsDir = TerminalConfig.getRootfsDir(context)
                val distroDir = java.io.File(rootfsDir, config.id)
                val sizeStr = if (distroDir.exists()) {
                    val bytes = distroDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    "%.1f MB".format(bytes / (1024.0 * 1024.0))
                } else "—"

                ListItem(
                    headlineContent = { Text(config.name, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = {
                        Text(
                            if (installed) "Installed ($sizeStr)" else "Not installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (installed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (installed) Icons.Outlined.Storage else Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            tint = if (installed) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        if (installed) {
                            IconButton(onClick = {
                                ProotDistroManager.removeDistro(context, config.id)
                            }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CustomizationCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Theme mode
            val currentTheme = SettingsDataStore.themeMode
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsDataStore.ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentTheme == mode,
                        onClick = { SettingsDataStore.themeMode = mode },
                        label = { Text(mode.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Font size
            var fontSize by remember { mutableStateOf(SettingsDataStore.fontSize) }
            Text("Font Size: $fontSize", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = fontSize.toFloat(),
                onValueChange = {
                    fontSize = it.toInt()
                    SettingsDataStore.fontSize = it.toInt()
                },
                valueRange = 8f..32f,
                steps = 23
            )
        }
    }
}

@Composable
private fun LanguageSelectorCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val currentLang = SettingsDataStore.language.ifEmpty { "System default" }
            Text("Current: $currentLang", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))

            val languages = listOf("" to "System default", "es" to "Español", "en" to "English", "pt" to "Português")
            languages.forEach { (code, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            SettingsDataStore.language = code
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = SettingsDataStore.language == code,
                        onClick = { SettingsDataStore.language = code }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) { null }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Project Terminal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Text("Version: ${packageInfo?.versionName ?: "?"} (${packageInfo?.versionCode ?: "?"})")
            Text("PRoot: ptrace-based chroot for Android")
            Text("License: GPL-3.0")

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/proot-me/proot"))
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("PRoot on GitHub")
            }
        }
    }
}
