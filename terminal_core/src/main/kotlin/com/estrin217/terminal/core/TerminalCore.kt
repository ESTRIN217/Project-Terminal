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
        // Used to load the 'terminal_core' library on application startup.
        init {
            System.loadLibrary("terminal_core")
        }
    }
}
