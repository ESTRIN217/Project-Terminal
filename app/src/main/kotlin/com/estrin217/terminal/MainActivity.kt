package com.estrin217.terminal

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.estrin217.terminal.core.TerminalCore
import com.estrin217.terminal.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ejemplo de uso de terminal_core
        val core = TerminalCore()
        binding.sampleText.text = core.stringFromJNI()
    }
}
