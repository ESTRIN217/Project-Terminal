package com.estrin217.terminal.core

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SpecialKeysBar(
    state: TerminalSurfaceState,
    bridge: TerminalBridge?,
    controlActive: Boolean,
    altActive: Boolean,
    onControlKeyChanged: (Boolean) -> Unit,
    onAltKeyChanged: (Boolean) -> Unit,
    onNavigateToLogger: () -> Unit,
    onPickRootfs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(4.dp))

        Button(
            onClick = {
                val b = bridge ?: return@Button
                b.controlKeyPressed = !b.controlKeyPressed
                onControlKeyChanged(b.controlKeyPressed)
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (controlActive) Color(0xFF4F378B) else Color(0xFF2E2D30)
            ),
            modifier = Modifier.widthIn(min = 64.dp)
        ) {
            androidx.compose.material3.Text("CTRL", fontSize = 11.sp, color = Color(0xFFE8DEF8), fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = {
                val b = bridge ?: return@Button
                b.altKeyPressed = !b.altKeyPressed
                onAltKeyChanged(b.altKeyPressed)
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (altActive) Color(0xFF4F378B) else Color(0xFF2E2D30)
            ),
            modifier = Modifier.widthIn(min = 64.dp)
        ) {
            androidx.compose.material3.Text("ALT", fontSize = 11.sp, color = Color(0xFFE8DEF8), fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = {
                state.terminalSession?.write("\u001B")
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 60.dp)
        ) {
            androidx.compose.material3.Text("ESC", fontSize = 11.sp, color = Color.White)
        }

        Button(
            onClick = {
                state.terminalSession?.write("\t")
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 60.dp)
        ) {
            androidx.compose.material3.Text("TAB", fontSize = 11.sp, color = Color.White)
        }

        Button(
            onClick = {
                state.terminalSession?.write("\u001B[A")
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 48.dp)
        ) {
            androidx.compose.material3.Text("\u25B2", fontSize = 11.sp, color = Color.White)
        }

        Button(
            onClick = {
                state.terminalSession?.write("\u001B[B")
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 48.dp)
        ) {
            androidx.compose.material3.Text("\u25BC", fontSize = 11.sp, color = Color.White)
        }

        Button(
            onClick = {
                state.terminalSession?.write("\u001B[D")
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 48.dp)
        ) {
            androidx.compose.material3.Text("\u25C0", fontSize = 11.sp, color = Color.White)
        }

        Button(
            onClick = {
                state.terminalSession?.write("\u001B[C")
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 48.dp)
        ) {
            androidx.compose.material3.Text("\u25B6", fontSize = 11.sp, color = Color.White)
        }

        Button(
            onClick = {
                state.terminalSession?.write("clear\n")
                state.terminalView?.requestFocus()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 60.dp)
        ) {
            androidx.compose.material3.Text("CLR", fontSize = 11.sp, color = Color(0xFFF2B8B5))
        }

        Button(
            onClick = {
                state.terminalView?.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(state.terminalView, 0)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 60.dp)
        ) {
            androidx.compose.material3.Text("KEY", fontSize = 11.sp, color = Color(0xFFD0BCFF))
        }

        Button(
            onClick = onNavigateToLogger,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 60.dp)
        ) {
            androidx.compose.material3.Text("LOG", fontSize = 11.sp, color = Color(0xFFA8DADC))
        }

        Button(
            onClick = onPickRootfs,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2D30)),
            modifier = Modifier.widthIn(min = 72.dp)
        ) {
            androidx.compose.material3.Text("IMPORT", fontSize = 11.sp, color = Color(0xFFFFD166))
        }

        Spacer(modifier = Modifier.width(4.dp))
    }
}
