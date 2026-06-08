package com.estrin217.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.estrin217.terminal.core.RootfsManager
import com.estrin217.terminal.core.TerminalBridge
import com.estrin217.terminal.core.TerminalConfig
import com.estrin217.terminal.core.TerminalService
import com.estrin217.terminal.databinding.ActivityMainBinding
import com.estrin217.terminal.logger.DebugLogger
import com.estrin217.terminal.logger.LoggerActivity
import com.termux.terminal.TerminalSession
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var terminalService: TerminalService? = null
    private var terminalBridge: TerminalBridge? = null
    private var terminalSession: TerminalSession? = null
    private var isBound = false
    private val PICK_ROOTFS = 1101

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TerminalService.TerminalServiceBinder
            terminalService = binder?.getService()
            isBound = true
            setupTerminalSession()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DebugLogger.i(TAG, "MainActivity created")

        terminalBridge = TerminalBridge(this, binding.terminalView).apply {
            modifierKeyConsumedListener = object : TerminalBridge.OnModifierKeyConsumedListener {
                override fun onControlKeyConsumed() {
                    runOnUiThread { resetCtrlButtonHighlight() }
                }

                override fun onAltKeyConsumed() {
                    runOnUiThread { resetAltButtonHighlight() }
                }
            }
        }
        binding.terminalView.setTerminalViewClient(terminalBridge)

        setupSpecialKeysClickListeners()
        checkAndInstallRootfs()
    }

    private fun checkAndInstallRootfs() {
        DebugLogger.i(TAG, "Checking rootfs installation")
        if (RootfsManager.isInstalled(this)) {
            DebugLogger.i(TAG, "Rootfs already installed")
            startAndBindService()
        } else {
            DebugLogger.i(TAG, "Rootfs not installed, starting extraction")
            binding.loadingLayout.visibility = View.VISIBLE
            binding.terminalView.visibility = View.GONE

            Thread {
                try {
                    RootfsManager.install(this) { count ->
                        runOnUiThread {
                            binding.progressText.text = "Archivos extraídos: $count"
                        }
                    }
                    DebugLogger.i(TAG, "Rootfs extraction completed successfully")
                    runOnUiThread {
                        binding.loadingLayout.visibility = View.GONE
                        binding.terminalView.visibility = View.VISIBLE
                        startAndBindService()
                    }
                } catch (e: IOException) {
                    DebugLogger.e(TAG, "Error during rootfs extraction", e)
                    runOnUiThread {
                        binding.progressText.text = "Error de extracción: ${e.message}"
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Error instalando rootfs: ${e.message}", Toast.LENGTH_LONG)
                            .show()
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
        DebugLogger.i(TAG, "Setting up terminal session")
        val service = terminalService ?: return
        val bridge = terminalBridge ?: return
        val session = service.createOrGetSession(this, bridge)
        terminalSession = session
        binding.terminalView.attachSession(session)
        DebugLogger.i(TAG, "Terminal session attached")
    }

    private fun setupSpecialKeysClickListeners() {
        binding.btnCtrl.setOnClickListener {
            val bridge = terminalBridge ?: return@setOnClickListener
            bridge.controlKeyPressed = !bridge.controlKeyPressed
            binding.btnCtrl.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (bridge.controlKeyPressed) 0xFF4F378B.toInt() else 0xFF2E2D30.toInt()
            )
            binding.terminalView.requestFocus()
        }

        binding.btnAlt.setOnClickListener {
            val bridge = terminalBridge ?: return@setOnClickListener
            bridge.altKeyPressed = !bridge.altKeyPressed
            binding.btnAlt.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (bridge.altKeyPressed) 0xFF4F378B.toInt() else 0xFF2E2D30.toInt()
            )
            binding.terminalView.requestFocus()
        }

        binding.btnEsc.setOnClickListener {
            terminalSession?.write("\u001B")
            binding.terminalView.requestFocus()
        }

        binding.btnTab.setOnClickListener {
            terminalSession?.write("\t")
            binding.terminalView.requestFocus()
        }

        binding.btnUp.setOnClickListener {
            terminalSession?.write("\u001B[A")
            binding.terminalView.requestFocus()
        }

        binding.btnDown.setOnClickListener {
            terminalSession?.write("\u001B[B")
            binding.terminalView.requestFocus()
        }

        binding.btnLeft.setOnClickListener {
            terminalSession?.write("\u001B[D")
            binding.terminalView.requestFocus()
        }

        binding.btnRight.setOnClickListener {
            terminalSession?.write("\u001B[C")
            binding.terminalView.requestFocus()
        }

        binding.btnClr.setOnClickListener {
            terminalSession?.write("clear\n")
            binding.terminalView.requestFocus()
        }

        binding.btnKeyboard.setOnClickListener {
            binding.terminalView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
        }

        binding.btnLogger.setOnClickListener {
            DebugLogger.i(TAG, "Opening Logger Activity")
            val intent = Intent(this, LoggerActivity::class.java)
            startActivity(intent)
        }

        binding.btnImportRootfs.setOnClickListener {
            // Open file picker to import rootfs archive
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/x-xz", "application/gzip", "application/x-tar", "application/octet-stream")
                )
            }
            startActivityForResult(intent, PICK_ROOTFS)
        }
    }

    fun resetCtrlButtonHighlight() {
        binding.btnCtrl.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E2D30.toInt())
    }

    fun resetAltButtonHighlight() {
        binding.btnAlt.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E2D30.toInt())
    }

    override fun onDestroy() {
        DebugLogger.i(TAG, "MainActivity destroyed")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_ROOTFS && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            DebugLogger.i(TAG, "Selected rootfs URI: $uri")
            binding.loadingLayout.visibility = View.VISIBLE
            Thread {
                try {
                    runOnUiThread {
                        binding.loadingLayout.visibility = View.GONE
                        Toast.makeText(this, "Import and install completed", Toast.LENGTH_LONG).show()
                        startAndBindService()
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Import failed", e)
                    runOnUiThread {
                        binding.loadingLayout.visibility = View.GONE
                        Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
