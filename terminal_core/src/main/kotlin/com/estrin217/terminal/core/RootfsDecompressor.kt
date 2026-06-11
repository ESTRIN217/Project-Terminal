package com.estrin217.terminal.core

import android.content.Context
import com.estrin217.terminal.core.logger.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RootfsDecompressor {

    suspend fun extractDebianRootfs(
        context: Context,
        tarballSource: File,
        destinationDir: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!destinationDir.exists()) destinationDir.mkdirs()

        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val bsdtarBinary = File(nativeLibraryDir, "libbsdtar.so").absolutePath

        if (!File(bsdtarBinary).exists()) {
            DebugLogger.w("RootfsDecompressor", "libbsdtar.so not found at $bsdtarBinary, falling back to Java extraction")
            return@withContext false
        }

        val command = arrayOf(
            bsdtarBinary,
            "-x",
            "-f", tarballSource.absolutePath,
            "-C", destinationDir.absolutePath,
            "--no-same-owner"
        )

        try {
            DebugLogger.i("RootfsDecompressor", "Extracting with bsdtar: ${command.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                DebugLogger.i("RootfsDecompressor", "bsdtar extraction completed successfully")
                true
            } else {
                DebugLogger.e("RootfsDecompressor", "bsdtar extraction failed with exit code $exitCode")
                false
            }
        } catch (e: Exception) {
            DebugLogger.e("RootfsDecompressor", "bsdtar extraction threw exception", e)
            false
        }
    }
}
