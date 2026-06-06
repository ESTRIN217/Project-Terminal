package com.estrin217.terminal.core

import android.content.Context
import java.io.File

object TerminalConfig {
    const val ROOTFS_ASSET_NAME = "rootfs.tar.xz"
    const val ROOTFS_DIR_NAME = "rootfs"
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
     * Android extracts native .so files automatically to applicationInfo.nativeLibraryDir.
     * Our libproot.so (which is actually the PRoot executable renamed to satisfy Android package validation)
     * is located there.
     */
    fun getPRootExecutable(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    }

    fun getEnvironmentVariables(context: Context): Array<String> {
        val rootfsPath = getRootfsDir(context).absolutePath
        val homePath = "$rootfsPath/home/programador"
        val tmpPath = "$rootfsPath/tmp"

        return arrayOf(
            "TERM=xterm-256color",
            "HOME=/home/programador",
            "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
            "USER=programador",
            "LOGNAME=programador",
            "LANG=es_VE.UTF-8", // Idioma prioritario según GEMINI.md
            "PROOT_TMPDIR=$tmpPath",
            "PROOT_NO_SECCOMP=1" // Desactiva seccomp para mayor compatibilidad
        )
    }

    fun getPRootArgs(context: Context, shellCmd: String = "/bin/sh"): Array<String> {
        val rootfsPath = getRootfsDir(context).absolutePath
        val prootExe = getPRootExecutable(context).absolutePath

        // Argumentos para aislar rutas usando PRoot:
        // -r <rootfs>: define el nuevo directorio raíz
        // -0: emula ser root (opcional, pero útil)
        // -b /dev -b /proc -b /sys: monta directorios del sistema de Android
        // -w /home/programador: establece el directorio de trabajo inicial
        return arrayOf(
            prootExe,
            "-r", rootfsPath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/data/data/${context.packageName}:/data_priv", // Montaje del directorio privado
            "-w", "/home/programador",
            shellCmd
        )
    }
}
