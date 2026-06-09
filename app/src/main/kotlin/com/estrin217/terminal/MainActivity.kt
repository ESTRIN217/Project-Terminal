package com.estrin217.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.estrin217.terminal.core.LocaleManager
import com.estrin217.terminal.core.RootfsManager
import com.estrin217.terminal.core.TerminalBridge
import com.estrin217.terminal.core.TerminalConfig
import com.estrin217.terminal.core.TerminalService
import com.estrin217.terminal.core.logger.DebugLogger
import com.estrin217.terminal.core.ConnectivityUtils
import com.estrin217.terminal.logger.LoggerActivity
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var terminalService: TerminalService? = null
    private var terminalBridge: TerminalBridge? = null
    private var terminalSession: TerminalSession? = null
    private var isBound = false

    private val isInstallingState = mutableStateOf(false)
    private val progressTextState = mutableStateOf("")
    private val controlActiveState = mutableStateOf(false)
    private val altActiveState = mutableStateOf(false)
    private var terminalViewInstance: TerminalView? = null

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
                    // Copy to temp directory and extract
                    val tempFile = File(cacheDir, "imported_rootfs.tar.xz")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    val rootfsDir = TerminalConfig.getRootfsDir(this)
                    if (rootfsDir.exists()) {
                        rootfsDir.deleteRecursively()
                    }
                    rootfsDir.mkdirs()
                    
                    // RootfsManager logic for extracting standard tar input streams
                    java.io.FileInputStream(tempFile).use { fileStream ->
                        val extractMethod = RootfsManager::class.java.getDeclaredMethod(
                            "extractTarArchive",
                            java.io.InputStream::class.java,
                            File::class.java,
                            Function1::class.java
                        )
                        extractMethod.isAccessible = true
                        extractMethod.invoke(RootfsManager, fileStream, rootfsDir, { count: Int ->
                            runOnUiThread {
                                progressTextState.value = LocaleManager.getString("extracted_count", count)
                            }
                        })
                    }

                    File(rootfsDir, "home/programador").mkdirs()
                    File(rootfsDir, "tmp").mkdirs()
                    TerminalConfig.getMarkerFile(this).createNewFile()
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
        DebugLogger.initCrashHandler(this)
        LocaleManager.init(applicationContext)
        super.onCreate(savedInstanceState)

        DebugLogger.i(TAG, "MainActivity created with Jetpack Compose UI")

        // Log current network state at startup
        val hasNet = ConnectivityUtils.hasInternet(this)
        DebugLogger.i(TAG, "Network available at startup: $hasNet")

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
                MainScreen()
            }
        }

        checkAndInstallRootfs()
    }

    private fun checkAndInstallRootfs() {
        DebugLogger.i(TAG, "Checking rootfs installation status")
        if (RootfsManager.isInstalled(this)) {
            DebugLogger.i(TAG, "Rootfs is already installed")
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
        val session = service.createOrGetSession(this, bridge)
        terminalSession = session
        terminalViewInstance?.attachSession(session)
        DebugLogger.i(TAG, "Terminal session successfully attached")
    }

    @Composable
    fun MainScreen() {
        val isInstalling by isInstallingState
        val progressText by progressTextState
        val controlActive by controlActiveState
        val altActive by altActiveState
        val context = LocalContext.current

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
                // Host the native Termux TerminalView inside AndroidView
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx, null).also { view ->
                            terminalViewInstance = view
                            terminalBridge = TerminalBridge(ctx, view).apply {
                                modifierKeyConsumedListener = object : TerminalBridge.OnModifierKeyConsumedListener {
                                    override fun onControlKeyConsumed() {
                                        runOnUiThread { controlActiveState.value = false }
                                    }

                                    override fun onAltKeyConsumed() {
                                        runOnUiThread { altActiveState.value = false }
                                    }
                                }
                            }
                            view.setTerminalViewClient(terminalBridge)
                            
                            // If bound already, attach the session
                            terminalSession?.let { session ->
                                view.attachSession(session)
                            }
                        }
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

            // Special Keys Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(4.dp))

                // CTRL
                Button(
                    onClick = {
                        val bridge = terminalBridge ?: return@Button
                        bridge.controlKeyPressed = !bridge.controlKeyPressed
                        controlActiveState.value = bridge.controlKeyPressed
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (controlActive) Color(0xFF4F378B) else Color(0xFF2E2D30)
                    ),
                    modifier = Modifier.widthIn(min = 64.dp)
                ) {
                    Text("CTRL", fontSize = 11.sp, color = Color(0xFFE8DEF8), fontWeight = FontWeight.Bold)
                }

                // ALT
                Button(
                    onClick = {
                        val bridge = terminalBridge ?: return@Button
                        bridge.altKeyPressed = !bridge.altKeyPressed
                        altActiveState.value = bridge.altKeyPressed
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (altActive) Color(0xFF4F378B) else Color(0xFF2E2D30)
                    ),
                    modifier = Modifier.widthIn(min = 64.dp)
                ) {
                    Text("ALT", fontSize = 11.sp, color = Color(0xFFE8DEF8), fontWeight = FontWeight.Bold)
                }

                // ESC
                Button(
                    onClick = {
                        terminalSession?.write("\u001B")
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 60.dp)
                ) {
                    Text("ESC", fontSize = 11.sp, color = Color.White)
                }

                // TAB
                Button(
                    onClick = {
                        terminalSession?.write("\t")
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 60.dp)
                ) {
                    Text("TAB", fontSize = 11.sp, color = Color.White)
                }

                // UP
                Button(
                    onClick = {
                        terminalSession?.write("\u001B[A")
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 48.dp)
                ) {
                    Text("▲", fontSize = 11.sp, color = Color.White)
                }

                // DOWN
                Button(
                    onClick = {
                        terminalSession?.write("\u001B[B")
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 48.dp)
                ) {
                    Text("▼", fontSize = 11.sp, color = Color.White)
                }

                // LEFT
                Button(
                    onClick = {
                        terminalSession?.write("\u001B[D")
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 48.dp)
                ) {
                    Text("◀", fontSize = 11.sp, color = Color.White)
                }

                // RIGHT
                Button(
                    onClick = {
                        terminalSession?.write("\u001B[C")
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 48.dp)
                ) {
                    Text("▶", fontSize = 11.sp, color = Color.White)
                }

                // CLR
                Button(
                    onClick = {
                        terminalSession?.write("clear\n")
                        terminalViewInstance?.requestFocus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 60.dp)
                ) {
                    Text("CLR", fontSize = 11.sp, color = Color(0xFFF2B8B5))
                }

                // KEY
                Button(
                    onClick = {
                        terminalViewInstance?.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(terminalViewInstance, 0)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 60.dp)
                ) {
                    Text("KEY", fontSize = 11.sp, color = Color(0xFFD0BCFF))
                }

                // LOG
                Button(
                    onClick = {
                        DebugLogger.i(TAG, "Navigating to LoggerActivity")
                        val intent = Intent(context, LoggerActivity::class.java)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 60.dp)
                ) {
                    Text("LOG", fontSize = 11.sp, color = Color(0xFFA8DADC))
                }

                // IMPORT
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                arrayOf("application/x-xz", "application/gzip", "application/x-tar", "application/octet-stream")
                            )
                        }
                        pickRootfsLauncher.launch(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
                    modifier = Modifier.widthIn(min = 72.dp)
                ) {
                    Text("IMPORT", fontSize = 11.sp, color = Color(0xFFFFD166))
                }

                Spacer(modifier = Modifier.width(4.dp))
            }
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
