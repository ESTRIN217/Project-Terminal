package com.estrin217.terminal.core

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.estrin217.terminal.core.logger.DebugLogger
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@Stable
class TerminalSurfaceState(
    private val coroutineScope: CoroutineScope
) {
    var terminalSession by mutableStateOf<TerminalSession?>(null)
        private set

    var terminalView by mutableStateOf<TerminalView?>(null)
        private set

    private val _renderRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val renderRequests: SharedFlow<Unit> = _renderRequests.asSharedFlow()

    private var onSessionReady: ((TerminalSession, TerminalView) -> Unit)? = null

    fun attachSession(session: TerminalSession) {
        this.terminalSession = session
        val view = terminalView
        if (view != null) {
            ensureRendererInitialized(view)
            view.attachSession(session)
            DebugLogger.i("TerminalSurfaceState", "Session attached to TerminalView")
            onSessionReady?.invoke(session, view)
        }
    }

    fun attachView(view: TerminalView) {
        this.terminalView = view
        ensureRendererInitialized(view)
        val session = terminalSession
        if (session != null) {
            view.attachSession(session)
            DebugLogger.i("TerminalSurfaceState", "View attached and session bound")
            onSessionReady?.invoke(session, view)
        }
    }

    private fun ensureRendererInitialized(view: TerminalView) {
        if (view.mRenderer == null) {
            view.setTextSize(12)
        }
    }

    fun setOnSessionReadyListener(listener: (TerminalSession, TerminalView) -> Unit) {
        onSessionReady = listener
        val s = terminalSession
        val v = terminalView
        if (s != null && v != null) {
            listener(s, v)
        }
    }

    fun writeInput(bytes: ByteArray) {
        terminalSession?.write(bytes, 0, bytes.size)
    }

    fun requestTerminalRedraw() {
        _renderRequests.tryEmit(Unit)
    }
}

@Composable
fun rememberTerminalSurfaceState(
    coroutineScope: CoroutineScope = rememberCoroutineScope()
): TerminalSurfaceState {
    return remember { TerminalSurfaceState(coroutineScope) }
}

@Composable
fun TerminalSurface(
    state: TerminalSurfaceState,
    modifier: Modifier = Modifier,
    onBridgeCreated: (TerminalBridge) -> Unit = {},
    bridge: TerminalBridge? = null
) {
    val providedBridge = bridge ?: remember { null }

    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).also { view ->
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                state.attachView(view)

                val terminalBridge = providedBridge ?: TerminalBridge(ctx, view)
                view.setTerminalViewClient(terminalBridge)
                onBridgeCreated(terminalBridge)
            }
        },
        modifier = modifier
    )
}
