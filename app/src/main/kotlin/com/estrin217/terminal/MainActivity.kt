package com.estrin217.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.estrin217.terminal.core.RootfsManager
import com.estrin217.terminal.core.TerminalBridge
import com.estrin217.terminal.core.TerminalService
import com.estrin217.terminal.databinding.ActivityMainBinding
import com.termux.terminal.TerminalSession
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var terminalService: TerminalService? = null
    private var terminalBridge: TerminalBridge? = null
    private var terminalSession: TerminalSession? = null
    private var isBound = false

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
        if (RootfsManager.isInstalled(this)) {
            startAndBindService()
        } else {
            binding.loadingLayout.visibility = View.VISIBLE
            binding.terminalView.visibility = View.GONE

            Thread {
                try {
                    RootfsManager.install(this) { count ->
                        runOnUiThread {
                            binding.progressText.text = "Archivos extraídos: $count"
                        }
                    }
                    runOnUiThread {
                        binding.loadingLayout.visibility = View.GONE
                        binding.terminalView.visibility = View.VISIBLE
                        startAndBindService()
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        binding.progressText.text = "Error de extracción: ${e.message}"
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Error instalando rootfs: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, TerminalService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupTerminalSession() {
        val service = terminalService ?: return
        val bridge = terminalBridge ?: return
        val session = service.createOrGetSession(this, bridge)
        terminalSession = session
        binding.terminalView.attachSession(session)
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
    }

    fun resetCtrlButtonHighlight() {
        binding.btnCtrl.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E2D30.toInt())
    }

    fun resetAltButtonHighlight() {
        binding.btnAlt.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E2D30.toInt())
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
