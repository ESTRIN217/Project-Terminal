package com.estrin217.terminal.logger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

class LoggerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            // Cumpliendo directrices de Material Design 3 básico
            MaterialTheme(
                colorScheme = darkColorScheme() // O puedes usar lightColorScheme() según tus preferencias
            ) {
                LoggerScreen(
                    onBackPressed = { finish() }
                )
            }
        }
    }
}