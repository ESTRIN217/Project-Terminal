package com.estrin217.terminal.core

import android.content.Context
import android.os.ParcelFileDescriptor
import com.estrin217.terminal.core.logger.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RootfsDecompressor {

    private var nativeLoaded = false

    private fun ensureNativeLoaded() {
        if (!nativeLoaded) {
            try {
                System.loadLibrary("terminal_core")
                nativeLoaded = true
                DebugLogger.i("RootfsDecompressor", "terminal_core native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                DebugLogger.w("RootfsDecompressor", "terminal_core not available: ${e.message}")
            }
        }
    }

    private external fun nativeExtractTar(fd: Int, destDir: String): Boolean

    /**
     * Verifica si la extracción nativa (libarchive/bsdtar via JNI) está disponible
     * sin intentar una extracción completa. Útil para diagnóstico previo.
     */
    fun isNativeExtractionAvailable(): Boolean {
        ensureNativeLoaded()
        return nativeLoaded
    }

    suspend fun extractDebianRootfs(
        context: Context,
        tarballSource: File,
        destinationDir: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!destinationDir.exists()) destinationDir.mkdirs()

        ensureNativeLoaded()
        if (!nativeLoaded) {
            DebugLogger.w("RootfsDecompressor", "Native library not loaded, falling back to Java extraction")
            return@withContext false
        }

        try {
            val pfd = ParcelFileDescriptor.open(tarballSource, ParcelFileDescriptor.MODE_READ_ONLY)
            val fd = pfd.fd
            DebugLogger.i("RootfsDecompressor", "Extracting via native JNI with fd=$fd to ${destinationDir.absolutePath}")
            val success = nativeExtractTar(fd, destinationDir.absolutePath)
            pfd.close()
            if (success) {
                DebugLogger.i("RootfsDecompressor", "Native extraction completed successfully")
            } else {
                DebugLogger.e("RootfsDecompressor", "Native extraction returned false")
            }
            success
        } catch (e: Exception) {
            DebugLogger.e("RootfsDecompressor", "Native extraction threw exception", e)
            false
        }
    }
}
