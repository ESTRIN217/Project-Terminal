package com.estrin217.terminal.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.estrin217.terminal.core.logger.DebugLogger
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

open class TerminalBridge(
    protected val context: Context,
    protected val terminalView: TerminalView? = null
) : TerminalSessionClient, TerminalViewClient {

    private var shellPid: Int = -1

    // --- Font Scaling Support ---
    private var fontSize: Int = 12 // Default font size in pt
        set(value) {
            field = value.coerceIn(4, 48)
            terminalView?.setTextSize(field)
        }

    fun changeFontSize(increase: Boolean) {
        fontSize += if (increase) 1 else -1
    }

    /**
     * Resizes the PTY associated with the TerminalSession using our custom JNI function.
     */
    fun resizeSession(session: TerminalSession, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val fd = session.terminalFileDescriptor
        if (fd > 0) {
            TerminalCore().setTerminalSize(fd, rows, columns, columns * cellWidth, rows * cellHeight)
        }
    }

    // --- TerminalSessionClient Interface ---

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        DebugLogger.i("TerminalBridge", "Title changed: ${changedSession.title}")
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val exitStatus = finishedSession.exitStatus
        val isRunning = finishedSession.isRunning
        DebugLogger.i("TerminalBridge", "Session finished: ${finishedSession.title}. isRunning=$isRunning, exitStatus=$exitStatus")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Terminal", text)
        clipboard.setPrimaryClip(clip)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).coerceToText(context).toString()
            session?.write(text)
        }
    }

    override fun onBell(session: TerminalSession) {
        DebugLogger.d("TerminalBridge", "Bell triggered")
    }

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.postInvalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Handle cursor blink state or custom UI updates
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        this.shellPid = pid
        DebugLogger.d("TerminalBridge", "Subprocess running with PID: $pid")
    }

    override fun getTerminalCursorStyle(): Int {
        return 1 // Outlined block or bar
    }

    // --- TerminalViewClient Interface ---

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFontSize(scale > 1.0f)
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        // Show soft keyboard on click
        terminalView?.let { view ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // Let view process keys by default
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    interface OnModifierKeyConsumedListener {
        fun onControlKeyConsumed()
        fun onAltKeyConsumed()
    }

    var modifierKeyConsumedListener: OnModifierKeyConsumedListener? = null

    var controlKeyPressed: Boolean = false
    var altKeyPressed: Boolean = false

    override fun readControlKey(): Boolean {
        val active = controlKeyPressed
        if (active) {
            controlKeyPressed = false
            modifierKeyConsumedListener?.onControlKeyConsumed()
            terminalView?.postInvalidate()
        }
        return active
    }

    override fun readAltKey(): Boolean {
        val active = altKeyPressed
        if (active) {
            altKeyPressed = false
            modifierKeyConsumedListener?.onAltKeyConsumed()
            terminalView?.postInvalidate()
        }
        return active
    }

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        DebugLogger.d("TerminalBridge", "Emulator instance initialized and attached to view")
    }

    // --- Unified Logging for both interfaces ---

    override fun logError(tag: String, message: String) {
        DebugLogger.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        DebugLogger.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        DebugLogger.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        DebugLogger.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        DebugLogger.d(tag, "[VERBOSE] $message")
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: java.lang.Exception) {
        DebugLogger.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: java.lang.Exception) {
        DebugLogger.e(tag, "Stack trace occurred", e)
    }
}
