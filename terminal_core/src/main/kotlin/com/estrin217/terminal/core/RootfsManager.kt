package com.estrin217.terminal.core

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RootfsManager {

    /**
     * Checks if the Linux rootfs environment is already installed.
     */
    fun isInstalled(context: Context): Boolean {
        return TerminalConfig.getMarkerFile(context).exists()
    }

    /**
     * Extracts the rootfs from the assets into the private app storage.
     * Sets proper executable permissions on binary directories.
     */
    @Throws(IOException::class)
    fun install(context: Context, progressCallback: (extractedEntries: Int) -> Unit = {}) {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        if (rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
        }
        rootfsDir.mkdirs()

        val assetManager = context.assets
        val assetStream = assetManager.open(TerminalConfig.ROOTFS_ASSET_NAME)
        val bufferedIn = BufferedInputStream(assetStream)
        val xzIn = XZCompressorInputStream(bufferedIn)
        val tarIn = TarArchiveInputStream(xzIn)

        var entry = tarIn.nextEntry
        var entryCount = 0

        while (entry != null) {
            val destFile = File(rootfsDir, entry.name)

            // Guard against Path Traversal (Zip Slip)
            val canonicalDest = destFile.canonicalPath
            val canonicalRoot = rootfsDir.canonicalPath
            if (!canonicalDest.startsWith(canonicalRoot + File.separator)) {
                throw IOException("Security Violation: Entry path traversal detected in tar: ${entry.name}")
            }

            if (entry.isDirectory) {
                destFile.mkdirs()
            } else {
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { outputStream ->
                    tarIn.copyTo(outputStream)
                }

                // Grant execute permissions for binaries and shell scripts
                val path = entry.name
                val isExecutable = path.contains("bin/") || 
                                   path.contains("sbin/") || 
                                   path.contains("libexec/") ||
                                   path.endsWith(".sh") || 
                                   path.endsWith(".so")
                if (isExecutable) {
                    destFile.setExecutable(true, false)
                    destFile.setReadable(true, false)
                }
            }

            entryCount++
            progressCallback(entryCount)
            entry = tarIn.nextEntry
        }

        tarIn.close()

        // Establish core directory structures inside the rootfs
        File(rootfsDir, "home/programador").mkdirs()
        File(rootfsDir, "tmp").mkdirs()

        // Create installation marker
        TerminalConfig.getMarkerFile(context).createNewFile()
    }
}
