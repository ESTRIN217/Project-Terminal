package com.estrin217.terminal.core

import android.content.Context
import java.io.File

object TerminalConfig {
    const val ROOTFS_ASSET_NAME = "debian_rootfs.tar.xz"
    // Si se establece, RootfsManager intentará descargar este recurso remoto al iniciar.
    // Puede apuntar a un `index.json` OCI o a un blob tar.xz directo.
    const val ROOTFS_REMOTE_URL = "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/cc5fa8529b7279ece04540fdc22b1a60b30f5dae/stable/oci/index.json"
    const val ROOTFS_DIR_NAME = "rootfs"
    // Docker Hub defaults
    const val DOCKER_IMAGE = "debian"
    const val DOCKER_TAG = "stable-slim"
    const val MARKER_FILE_NAME = ".installed"

    // Material 3 CLI Color Scheme (ANSI escape codes)
    const val COLOR_PRIMARY = "\u001B[35m"      // Violet / Magenta
    const val COLOR_SECONDARY = "\u001B[36m"    // Cyan
    const val COLOR_TERTIARY = "\u001B[32m"     // Green
    const val COLOR_ERROR = "\u001B[31m"        // Red
    const val COLOR_RESET = "\u001B[0m"

    fun getRootfsDir(context: Context): File {
        return File(context.filesDir, ROOTFS_DIR_NAME)
    }

    fun getMarkerFile(context: Context): File {
        return File(getRootfsDir(context), MARKER_FILE_NAME)
    }

    /**
     * Force native libraries to be loaded and run explicitly from the native /lib directory of the application.
     */
    fun getPRootExecutable(context: Context): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: "${context.applicationInfo.dataDir}/lib"
        val xedFile = File(nativeDir, "libproot-xed.so")
        if (xedFile.exists()) {
            com.estrin217.terminal.core.logger.DebugLogger.i("TerminalConfig", "Using extended PRoot: ${xedFile.absolutePath}")
            return xedFile
        }
        val prootFile = File(nativeDir, "libproot.so")
        com.estrin217.terminal.core.logger.DebugLogger.i("TerminalConfig", "Using standard PRoot: ${prootFile.absolutePath}")
        return prootFile
    }

    fun getEnvironmentVariables(context: Context): Array<String> {
        return PRootCommandBuilder.defaultEnv(context)
    }

    /**
     * Construye los argumentos de PRoot con mejores prácticas para Android 10+:
     * - --link2symlink: convierten enlaces simbólicos en enlaces físicos para
     *   evitar restricciones de SELinux en el almacenamiento interno.
     * - -b /dev/pts: montaje explícito de pseudo-terminales para PTY.
     */
    fun getPRootArgs(context: Context, shellCmd: String = "/bin/sh"): Array<String> {
        return PRootCommandBuilder.create(context)
            .setShellCommand(shellCmd)
            .buildArgs()
    }
}
