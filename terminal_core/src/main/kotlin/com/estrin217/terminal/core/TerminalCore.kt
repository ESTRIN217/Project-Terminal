package com.estrin217.terminal.core

class TerminalCore {

    /**
     * A native method that is implemented by the 'terminal_core' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    /**
     * Set the terminal size using ioctl(fd, TIOCSWINSZ, &ws).
     */
    external fun setTerminalSize(fd: Int, rows: Int, cols: Int, widthPx: Int, heightPx: Int)

    companion object {
        // Try to load the 'terminal_core' library on class init, but fail gracefully.
        init {
            try {
                System.loadLibrary("terminal_core")
            } catch (e: UnsatisfiedLinkError) {
                // Defer to explicit installer if automatic load fails
                com.estrin217.terminal.core.logger.DebugLogger.w("TerminalCore", "Automatic System.loadLibrary failed: ${e.message}")
            } catch (e: Exception) {
                com.estrin217.terminal.core.logger.DebugLogger.e("TerminalCore", "Unexpected error during System.loadLibrary", e)
            }
        }

        /**
         * Explicit loader that can be invoked from a Context-aware component.
         */
        fun ensureLoaded(context: android.content.Context): Boolean {
            return NativeLibInstaller.ensureNativeLib(context, "terminal_core")
        }
    }
}
