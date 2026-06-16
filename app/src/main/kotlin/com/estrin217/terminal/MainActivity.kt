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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.estrin217.terminal.core.LocaleManager
import com.estrin217.terminal.core.RootfsManager
import com.estrin217.terminal.core.TerminalBridge
import com.estrin217.terminal.core.TerminalConfig
import com.estrin217.terminal.core.TerminalService
import com.estrin217.terminal.core.TerminalSurface
import com.estrin217.terminal.core.TerminalSurfaceState
import com.estrin217.terminal.core.SpecialKeysBar
import com.estrin217.terminal.core.rememberTerminalSurfaceState
import com.estrin217.terminal.core.logger.DebugLogger
import com.estrin217.terminal.core.ConnectivityUtils
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            DebugLogger.i(TAG, "ServiceConnection connected")
            val binder = service as? TerminalService.TerminalServiceBinder
            terminalService = binder?.getService()
            isBound = true
            setupTerminalSession()
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

                    DebugLogger.i(TAG, "Import completed successfully")
                    runOnUiThread {
                        isInstallingState.value = false
                        Toast.makeText(this, "Import and install completed", Toast.LENGTH_LONG).show()
                        startAndBindService()
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Import failed", e)
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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD0BCFF),
                    secondary = Color(0xFFCCC2DC),
                    tertiary = Color(0xFFEFB8C8),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
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
        val shellFile = File(rootfsDir, "bin/sh")

        // Sanity check: si el marcador existe pero el binario principal no, reinstalar
        if (RootfsManager.isInstalled(this) && !shellFile.exists()) {
            DebugLogger.w(TAG, "Corrupted installation detected: marker present but $shellFile missing. Forcing reinstall...")
            rootfsDir.deleteRecursively()
        }

        if (RootfsManager.isInstalled(this)) {
            DebugLogger.i(TAG, "Rootfs is already installed")
            RootfsManager.ensureLoaderPermissions(this)
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
                    runOnUiThread {
                        isInstallingState.value = false
                        startAndBindService()
                    }
                } catch (e: IOException) {
                    DebugLogger.e(TAG, "Error during rootfs extraction", e)
                    runOnUiThread {
                        progressTextState.value = LocaleManager.getString("extraction_error", e.message ?: "")
                        Toast.makeText(this@MainActivity, LocaleManager.getString("install_error_toast", e.message ?: ""), Toast.LENGTH_LONG).show()
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

    private fun setupTerminalSession() {
        DebugLogger.i(TAG, "Setting up terminal session and attaching to TerminalView")
        val service = terminalService ?: return
        val bridge = terminalBridge ?: return
        val state = terminalSurfaceStateRef ?: return
        val session = service.createOrGetSession(this, bridge)
        state.attachSession(session)
        DebugLogger.i(TAG, "Terminal session successfully attached")
    }

    /** Called from AndroidView factory when bridge becomes available */
    private fun onBridgeReady() {
        if (isBound && terminalService != null) {
            setupTerminalSession()
        }
    }

    @Composable
    fun MainScreen() {
        val isInstalling by isInstallingState
        val progressText by progressTextState
        val controlActive by controlActiveState
        val altActive by altActiveState
        val context = LocalContext.current
        val state = rememberTerminalSurfaceState()
        terminalSurfaceStateRef = state

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                TerminalSurface(
                    state = state,
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
                        onBridgeReady()
                    },
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
                bridge = terminalBridge,
                controlActive = controlActive,
                altActive = altActive,
                onControlKeyChanged = { controlActiveState.value = it },
                onAltKeyChanged = { altActiveState.value = it },
                onNavigateToLogger = {
                    DebugLogger.i(TAG, "Navigating to LoggerActivity")
                    val intent = Intent(context, LoggerActivity::class.java)
                    context.startActivity(intent)
                },
                onPickRootfs = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            arrayOf("application/x-xz", "application/gzip", "application/x-tar", "application/octet-stream")
                        )
                    }
                    pickRootfsLauncher.launch(intent)
                }
            )
        }
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
