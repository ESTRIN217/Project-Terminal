package com.estrin217.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.estrin217.terminal.core.*
import com.estrin217.terminal.core.logger.DebugLogger
import com.estrin217.terminal.logger.LoggerActivity
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var terminalService: TerminalService? = null
    private var terminalBridge: TerminalBridge? = null
    private var isBound = false
    private var terminalSurfaceStateRef: TerminalSurfaceState? = null

    private val isInstallingState = mutableStateOf(false)
    private val progressTextState = mutableStateOf("")
    private val controlActiveState = mutableStateOf(false)
    private val altActiveState = mutableStateOf(false)
    private val showErrorDialogState = mutableStateOf(false)
    private val errorMessageState = mutableStateOf("")

    enum class Tab(val label: String) { TERMINAL("Terminal"), FILES("Files"), SETTINGS("Settings") }
    private val selectedTab = mutableStateOf(Tab.TERMINAL)
    private val sessionTabIds = mutableStateListOf<String>()
    private val activeSessionTab = mutableStateOf<String?>(null)
    private val themeRefreshTrigger = mutableStateOf(0)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            DebugLogger.i(TAG, "ServiceConnection connected")
            val binder = service as? TerminalService.TerminalServiceBinder
            terminalService = binder?.getService()
            isBound = true
            ensureDefaultSession()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DebugLogger.i(TAG, "ServiceConnection disconnected")
            terminalService = null
            isBound = false
        }
    }

    private val pickRootfsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            DebugLogger.i(TAG, "Selected rootfs URI: $uri")
            isInstallingState.value = true
            progressTextState.value = "Importing..."
            Thread {
                try {
                    val tempFile = File(cacheDir, "imported_rootfs.tar.xz")
                    contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                    input.copyTo(output)
                    }
        }
        
        // Ahora solo invocas el nuevo método unificado
        java.io.FileInputStream(tempFile).use { fileStream ->
            RootfsManager.importCustomRootfs(this@MainActivity, fileStream) { count ->
                runOnUiThread {
                    progressTextState.value = LocaleManager.getString("extracted_count", count)
                }
            }
        }

        tempFile.delete()

                    // Validar rootfs importado antes de marcar como instalado
                    val rootfsDir = TerminalConfig.getRootfsDir(this@MainActivity)
                    validateRootfsOrThrow(rootfsDir)
                    TerminalConfig.getMarkerFile(this@MainActivity).createNewFile()
                    DebugLogger.i(TAG, "Import completed successfully. Marker created after validation.")
                    runOnUiThread {
                        isInstallingState.value = false
                        Toast.makeText(this, "Import and install completed", Toast.LENGTH_LONG).show()
                        startAndBindService()
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Import failed", e)
                    TerminalConfig.getMarkerFile(this@MainActivity).delete()
                    val rootfsDir = TerminalConfig.getRootfsDir(this@MainActivity)
                    if (rootfsDir.exists()) {
                        rootfsDir.deleteRecursively()
                    }
                    runOnUiThread {
                        isInstallingState.value = false
                        Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LocaleManager.init(applicationContext)
        super.onCreate(savedInstanceState)

        DebugLogger.i(TAG, "MainActivity created with Jetpack Compose UI")

        val hasNet = ConnectivityUtils.hasInternet(this)
        DebugLogger.i(TAG, "Network available at startup: $hasNet")

        val showCrashDialog = intent?.getBooleanExtra("show_crash_dialog", false) ?: false
        val crashPath = TerminalApplication.pendingCrashReportPath

        setContent {
            // Re-evaluate theme when returning from Settings (onResume increments trigger)
            val themeMode = remember(themeRefreshTrigger.value) { SettingsDataStore.themeMode }
            val colorScheme = when (themeMode) {
                SettingsDataStore.ThemeMode.LIGHT -> lightColorScheme(
                    primary = Color(0xFF6750A4),
                    secondary = Color(0xFF625B71),
                    tertiary = Color(0xFF7D5260),
                    background = Color(0xFFFFFBFE),
                    surface = Color(0xFFFFFBFE)
                )
                SettingsDataStore.ThemeMode.SYSTEM -> {
                    if (isSystemInDarkTheme()) darkColorScheme(
                        primary = Color(0xFFD0BCFF),
                        secondary = Color(0xFFCCC2DC),
                        tertiary = Color(0xFFEFB8C8),
                        background = Color(0xFF121212),
                        surface = Color(0xFF1E1E1E)
                    ) else lightColorScheme(
                        primary = Color(0xFF6750A4),
                        secondary = Color(0xFF625B71),
                        tertiary = Color(0xFF7D5260),
                        background = Color(0xFFFFFBFE),
                        surface = Color(0xFFFFFBFE)
                    )
                }
                SettingsDataStore.ThemeMode.DARK -> darkColorScheme(
                    primary = Color(0xFFD0BCFF),
                    secondary = Color(0xFFCCC2DC),
                    tertiary = Color(0xFFEFB8C8),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            }
            MaterialTheme(colorScheme = colorScheme) {
                if (showCrashDialog && crashPath != null) {
                    CrashReportDialog(
                        crashPath = crashPath,
                        onDismiss = { /* handled inside */ }
                    )
                }
                MainScreen()
            }
        }

        checkAndInstallRootfs()
    }

    private fun checkAndInstallRootfs() {
        DebugLogger.i(TAG, "Checking rootfs installation status")
        val rootfsDir = TerminalConfig.getRootfsDir(this)
        val binSh = File(rootfsDir, "bin/sh")
        val usrBinSh = File(rootfsDir, "usr/bin/sh")

        // Sanity check: si el marcador existe pero ningún shell aparece, reinstalar
        if (RootfsManager.isInstalled(this) && !binSh.exists() && !usrBinSh.exists()) {
            DebugLogger.w(TAG, "Corrupted installation detected: marker present but no shell found (checked bin/sh and usr/bin/sh). Forcing reinstall...")
            rootfsDir.deleteRecursively()
        }

        if (RootfsManager.isInstalled(this)) {
            DebugLogger.i(TAG, "Rootfs is already installed")
            RootfsManager.ensureLoaderPermissions(this)
            validateRootfsOrThrow(rootfsDir)
            startAndBindService()
        } else {
            DebugLogger.i(TAG, "Rootfs is not installed. Initiating installation sequence...")
            isInstallingState.value = true
            progressTextState.value = LocaleManager.getString("extracted_count", 0)

            Thread {
                try {
                    RootfsManager.install(this) { count ->
                        runOnUiThread {
                            progressTextState.value = LocaleManager.getString("extracted_count", count)
                        }
                    }
                    DebugLogger.i(TAG, "Rootfs extraction completed successfully")
                    RootfsManager.ensureLoaderPermissions(this@MainActivity)
                    validateRootfsOrThrow(rootfsDir)
                    // Crear marcador SOLO después de validación exitosa
                    TerminalConfig.getMarkerFile(this@MainActivity).createNewFile()
                    DebugLogger.i(TAG, "Created .installed marker after successful validation")
                    runOnUiThread {
                        isInstallingState.value = false
                        startAndBindService()
                    }
                } catch (e: IOException) {
                    DebugLogger.e(TAG, "Error during rootfs extraction", e)
                    TerminalConfig.getMarkerFile(this@MainActivity).delete()
                    // Limpieza completa para evitar estado inconsistente en reinicio
                    if (rootfsDir.exists()) {
                        rootfsDir.deleteRecursively()
                    }
                    runOnUiThread {
                        isInstallingState.value = false
                        errorMessageState.value = e.message ?: "Unknown error"
                        showErrorDialogState.value = true
                    }
                }
            }.start()
        }
    }

    private fun startAndBindService() {
        DebugLogger.i(TAG, "Starting and binding TerminalService")
        val intent = Intent(this, TerminalService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureDefaultSession() {
        val service = terminalService ?: return
        if (service.activeSessionIds.isEmpty()) {
            val bridge = terminalBridge ?: return
            service.newSession(this, bridge)
            val id = service.currentSessionId ?: return
            sessionTabIds.add(id)
            activeSessionTab.value = id
            DebugLogger.i(TAG, "Default session created: $id")
        } else {
            val id = service.activeSessionIds.first()
            activeSessionTab.value = id
            val state = terminalSurfaceStateRef ?: return
            val session = service.getSession(id) ?: return
            state.attachSession(session)
            DebugLogger.i(TAG, "Using existing session: $id")
        }
    }

    private fun addNewSession() {
        val service = terminalService ?: return
        val bridge = terminalBridge ?: return
        service.newSession(this, bridge)
        val id = service.currentSessionId ?: return
        sessionTabIds.add(id)
        activeSessionTab.value = id
        DebugLogger.i(TAG, "New session added: $id")
    }

    private fun closeSession(id: String) {
        val service = terminalService ?: return
        service.removeSession(id, this)
        sessionTabIds.remove(id)
        if (sessionTabIds.isEmpty()) {
            finish()
        } else {
            val newId = sessionTabIds.firstOrNull() ?: return
            activeSessionTab.value = newId
            val state = terminalSurfaceStateRef ?: return
            val session = service.getSession(newId) ?: return
            state.attachSession(session)
        }
    }

    private fun setupTerminalSession(sessionId: String? = null) {
        DebugLogger.i(TAG, "Setting up terminal session and attaching to TerminalView")
        val service = terminalService ?: return
        val id = sessionId ?: service.currentSessionId ?: return
        val state = terminalSurfaceStateRef ?: return
        val session = service.getSession(id) ?: return
        state.attachSession(session)
        DebugLogger.i(TAG, "Terminal session $id successfully attached")
    }

    private fun validateRootfsOrThrow(rootfsDir: File) {
        val binSh = File(rootfsDir, "bin/sh")
        val usrBinSh = File(rootfsDir, "usr/bin/sh")

        val isShellPresent = binSh.exists() || usrBinSh.exists()

        if (!isShellPresent) {
            DebugLogger.e(TAG, "Shell binary not found — dumping rootfs directory tree for debugging:")
            rootfsDir.listFiles()?.forEach { file ->
                DebugLogger.e(TAG, "  rootfs/${file.name}  isDir=${file.isDirectory}  size=${if (file.isFile) file.length() else "N/A"}")
                if (file.isDirectory) {
                    val children = file.listFiles()
                    if (children.isNullOrEmpty()) {
                        DebugLogger.e(TAG, "    (empty)")
                    } else {
                        children.forEach { sub ->
                            DebugLogger.e(TAG, "    ${sub.name}  isDir=${sub.isDirectory}  size=${if (sub.isFile) sub.length() else "N/A"}")
                        }
                    }
                }
            }
            throw IOException("Shell binary not found at expected locations (bin/sh or usr/bin/sh)")
        }

        val shellFile = if (binSh.exists()) binSh else usrBinSh
        val shellLabel = if (binSh.exists()) "/bin/sh" else "/usr/bin/sh"

        if (!shellFile.canExecute()) {
            DebugLogger.w(TAG, "$shellLabel is not executable after permissions fix, forcing...")
            shellFile.setExecutable(true, false)
        }
        if (!shellFile.canExecute()) {
            throw IOException("Cannot execute $shellLabel after permission fix - PRoot will fail")
        }
        DebugLogger.i(TAG, "Rootfs validation passed: $shellLabel is executable")
    }

    @Composable
    fun MainScreen() {
        val isInstalling by isInstallingState
        val progressText by progressTextState
        val controlActive by controlActiveState
        val altActive by altActiveState
        val context = LocalContext.current
        val tab by selectedTab
        val sessionIds = sessionTabIds.toList()
        val activeId by activeSessionTab

        val state = rememberTerminalSurfaceState()
        terminalSurfaceStateRef = state

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            bottomBar = {
                if (!isInstalling) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.TERMINAL,
                            onClick = { selectedTab.value = Tab.TERMINAL },
                            icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            label = { Text(Tab.TERMINAL.label) }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.FILES,
                            onClick = { selectedTab.value = Tab.FILES },
                            icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            label = { Text(Tab.FILES.label) }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.SETTINGS,
                            onClick = { selectedTab.value = Tab.SETTINGS },
                            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            label = { Text(Tab.SETTINGS.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (tab) {
                    Tab.FILES -> {
                        FileManagerScreen(
                            initialPath = TerminalConfig.getRootfsDir(context),
                            onBackPressed = { selectedTab.value = Tab.TERMINAL }
                        )
                    }
                    Tab.SETTINGS -> {
                        SettingsScreen(
                            onBackPressed = { selectedTab.value = Tab.TERMINAL }
                        )
                    }
                    Tab.TERMINAL -> {
                        TerminalTabContent(
                            state = state,
                            bridge = terminalBridge,
                            sessionIds = sessionIds,
                            activeId = activeId,
                            isInstalling = isInstalling,
                            progressText = progressText,
                            controlActiveState = controlActiveState,
                            altActiveState = altActiveState,
                            onBridgeCreated = { bridge ->
                                terminalBridge = bridge
                                bridge.modifierKeyConsumedListener = object : TerminalBridge.OnModifierKeyConsumedListener {
                                    override fun onControlKeyConsumed() {
                                        controlActiveState.value = false
                                    }
                                    override fun onAltKeyConsumed() {
                                        altActiveState.value = false
                                    }
                                }
                                ensureDefaultSession()
                            },
                            onAddSession = { addNewSession() },
                            onCloseSession = { id -> closeSession(id) },
                            onSwitchSession = { id ->
                                val service = terminalService ?: return@TerminalTabContent
                                service.switchSession(id)
                                activeSessionTab.value = id
                                val session = service.getSession(id) ?: return@TerminalTabContent
                                state.attachSession(session)
                                DebugLogger.i(TAG, "Switched to session: $id")
                            },
                            onNavigateToLogger = {
                                DebugLogger.i(TAG, "Navigating to LoggerActivity")
                                val intent = Intent(context, LoggerActivity::class.java)
                                context.startActivity(intent)
                            },
                            onPickRootfs = {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-xz", "application/gzip", "application/x-tar", "application/octet-stream"))
                                }
                                pickRootfsLauncher.launch(intent)
                            }
                        )
                    }
                }
            }

            // Error dialog for rootfs installation failures (shown on top)
            val showErrorDialog by showErrorDialogState
            val errorMessage by errorMessageState
            if (showErrorDialog) {
                RootfsErrorDialog(
                    errorMessage = errorMessage,
                    onRetry = {
                        showErrorDialogState.value = false
                        RootfsManager.forceReinstall(context)
                        checkAndInstallRootfs()
                    },
                    onClearCache = {
                        showErrorDialogState.value = false
                        context.cacheDir.deleteRecursively()
                        context.cacheDir.mkdirs()
                        RootfsManager.forceReinstall(context)
                        checkAndInstallRootfs()
                    },
                    onDismiss = { showErrorDialogState.value = false }
                )
            }
        }
    }

    @Composable
    fun RootfsErrorDialog(
        errorMessage: String,
        onRetry: () -> Unit,
        onClearCache: () -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = LocaleManager.getString("install_error_title"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = LocaleManager.getString("install_error_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                OutlinedButton(onClick = onRetry) {
                    Text(LocaleManager.getString("retry_download"))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(LocaleManager.getString("close"))
                    }
                    OutlinedButton(onClick = onClearCache) {
                        Text(LocaleManager.getString("clear_cache"))
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        DebugLogger.i(TAG, "MainActivity destroyed")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        DebugLogger.i(TAG, "MainActivity onStart")
    }

    override fun onResume() {
        super.onResume()
        themeRefreshTrigger.value++
        DebugLogger.i(TAG, "MainActivity onResume")
    }

    override fun onPause() {
        DebugLogger.i(TAG, "MainActivity onPause")
        super.onPause()
    }

    override fun onStop() {
        DebugLogger.i(TAG, "MainActivity onStop")
        super.onStop()
    }

    @Composable
    fun CrashReportDialog(crashPath: String, onDismiss: () -> Unit) {
        var showDialog by remember { mutableStateOf(true) }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = {
                    showDialog = false
                    TerminalApplication.pendingCrashReportPath = null
                },
                icon = {
                    Icon(Icons.Outlined.BugReport, contentDescription = null)
                },
                title = { Text("Crash detectado") },
                text = {
                    Text("La aplicación se cerró inesperadamente en la sesión anterior. " +
                            "¿Deseas compartir el reporte de crash para ayudar a corregir el error?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        TerminalApplication.pendingCrashReportPath = null
                        try {
                            val file = File(crashPath)
                            if (file.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    this@MainActivity,
                                    "${packageName}.fileprovider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    type = "text/plain"
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(intent, "Compartir reporte de crash"))
                            }
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "Error sharing crash report", e)
                        }
                    }) {
                        Text("Compartir reporte")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDialog = false
                        TerminalApplication.pendingCrashReportPath = null
                    }) {
                        Text("Descartar")
                    }
                }
            )
        }
    }

    @Composable
    private fun TerminalTabContent(
        state: TerminalSurfaceState,
        bridge: TerminalBridge?,
        sessionIds: List<String>,
        activeId: String?,
        isInstalling: Boolean,
        progressText: String,
        controlActiveState: MutableState<Boolean>,
        altActiveState: MutableState<Boolean>,
        onBridgeCreated: (TerminalBridge) -> Unit,
        onAddSession: () -> Unit,
        onCloseSession: (String) -> Unit,
        onSwitchSession: (String) -> Unit,
        onNavigateToLogger: () -> Unit,
        onPickRootfs: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Session tabs
            if (sessionIds.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = sessionIds.indexOf(activeId).coerceAtLeast(0),
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    sessionIds.forEach { id ->
                        val index = sessionIds.indexOf(id)
                        val isSelected = id == activeId
                        Tab(
                            selected = isSelected,
                            onClick = { onSwitchSession(id) },
                            text = { Text("Session ${index + 1}", maxLines = 1) },
                            icon = {
                                if (sessionIds.size > 1) {
                                    IconButton(
                                        onClick = { onCloseSession(id) },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Close",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    Tab(
                        selected = false,
                        onClick = onAddSession,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "New session",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = {}
                    )
                }
            }

            // Terminal area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                TerminalSurface(
                    state = state,
                    onBridgeCreated = onBridgeCreated,
                    bridge = bridge,
                    modifier = Modifier.fillMaxSize()
                )

                // Loading overlay for first-run rootfs extraction
                if (isInstalling) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xE6121212)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = LocaleManager.getString("loading_text"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = progressText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = LocaleManager.getString("progress_desc"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            SpecialKeysBar(
                state = state,
                bridge = bridge,
                controlActive = controlActiveState.value,
                altActive = altActiveState.value,
                onControlKeyChanged = { controlActiveState.value = it },
                onAltKeyChanged = { altActiveState.value = it },
                onNavigateToLogger = onNavigateToLogger,
                onPickRootfs = onPickRootfs
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
