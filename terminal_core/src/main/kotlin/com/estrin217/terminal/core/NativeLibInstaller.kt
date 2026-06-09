package com.estrin217.terminal.core

import android.content.Context
import android.os.Build
import com.estrin217.terminal.core.logger.DebugLogger
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object NativeLibInstaller {
    fun ensureNativeLib(context: Context, libName: String): Boolean {
        try {
            System.loadLibrary(libName)
            DebugLogger.i("NativeLibInstaller", "Loaded library $libName via System.loadLibrary")
            return true
        } catch (e: UnsatisfiedLinkError) {
            DebugLogger.w("NativeLibInstaller", "System.loadLibrary failed for $libName: ${e.message}")
        } catch (e: Exception) {
            DebugLogger.e("NativeLibInstaller", "Unexpected error loading $libName", e)
        }

        // Fallback: extract from APK and load explicitly
        try {
            val apkPath = context.packageCodePath
            val zip = ZipFile(apkPath)
            val abis = Build.SUPPORTED_ABIS.toList()
            val entryName = abis.asSequence().map { abi -> "lib/$abi/lib${libName}.so" }.firstOrNull { zip.getEntry(it) != null }

            if (entryName == null) {
                DebugLogger.e("NativeLibInstaller", "Native library $libName not found inside APK for ABIs: ${abis.joinToString()}")
                zip.close()
                return false
            }

            val entry = zip.getEntry(entryName)
            val nativeDir = context.applicationInfo.nativeLibraryDir?.let { File(it) }
            val targetDir = if (nativeDir != null && nativeDir.exists() && nativeDir.canWrite()) {
                DebugLogger.i("NativeLibInstaller", "Native lib dir accessible: ${nativeDir.absolutePath}")
                nativeDir
            } else {
                val fallbackDir = File(context.filesDir, "lib")
                DebugLogger.w("NativeLibInstaller", "Native library dir unavailable or read-only: ${nativeDir?.absolutePath}. Falling back to ${fallbackDir.absolutePath}")
                fallbackDir
            }

            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = File(targetDir, "lib${libName}.so")

            zip.getInputStream(entry).use { input ->
                FileOutputStream(targetFile).use { out ->
                    input.copyTo(out)
                }
            }
            zip.close()

            // Attempt to load extracted file
            System.load(targetFile.absolutePath)
            DebugLogger.i("NativeLibInstaller", "Extracted and loaded native lib to ${targetFile.absolutePath}")
            return true
        } catch (e: Exception) {
            DebugLogger.e("NativeLibInstaller", "Failed to extract/load native library $libName", e)
            return false
        }
    }
}
