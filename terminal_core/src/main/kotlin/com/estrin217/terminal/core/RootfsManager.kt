package com.estrin217.terminal.core

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

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

        // Prepare InputStream for the rootfs archive. Prefer remote URL if configured,
        // otherwise fall back to the bundled asset.
        val assetManager = context.assets
        val rootfsInputStream = try {
            if (TerminalConfig.ROOTFS_REMOTE_URL.isNotBlank()) {
                val downloaded = downloadRemoteRootfs(context, TerminalConfig.ROOTFS_REMOTE_URL)
                BufferedInputStream(java.io.FileInputStream(downloaded))
            } else {
                BufferedInputStream(assetManager.open(TerminalConfig.ROOTFS_ASSET_NAME))
            }
        } catch (e: Exception) {
            // Fallback to bundled asset on any failure
            BufferedInputStream(assetManager.open(TerminalConfig.ROOTFS_ASSET_NAME))
        }

        val header = ByteArray(6)
        rootfsInputStream.mark(8192)
        val read = rootfsInputStream.read(header)
        rootfsInputStream.reset()

        val tarIn = when {
            read >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() && header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() && header[4] == 0x5A.toByte() && header[5] == 0x00.toByte() -> {
                // XZ compressed
                val xzIn = XZCompressorInputStream(rootfsInputStream)
                TarArchiveInputStream(xzIn)
            }
            read >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> {
                // GZIP compressed
                val gzIn = GZIPInputStream(rootfsInputStream)
                TarArchiveInputStream(gzIn)
            }
            else -> {
                // Assume plain tar
                TarArchiveInputStream(rootfsInputStream)
            }
        }

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

    /**
     * Intenta resolver y descargar un recurso remoto que represente el rootfs.
     * El URL puede apuntar a un `index.json` OCI, a manifiestos que referencien otros ficheros,
     * o directamente a un blob tar.xz/gzip. El método sigue referencias relativas cuando
     * el contenido descargado es un puntero (p. ej. "../image-manifest.json").
     */
    @Throws(IOException::class)
    private fun downloadRemoteRootfs(context: Context, remoteUrl: String): File {
        var currentUrl = remoteUrl
        val outFile = File(context.cacheDir, "remote_rootfs.bin")

        repeat(8) {
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 120000
                instanceFollowRedirects = true
            }

            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(8192)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        fos.write(buf, 0, r)
                    }
                }
            }

            // Inspect beginning of file to decide qué es
            val header = ByteArray(512)
            val fh = java.io.FileInputStream(outFile)
            val got = fh.read(header)
            fh.close()

            if (got >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() && header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() && header[4] == 0x5A.toByte() && header[5] == 0x00.toByte()) {
                return outFile // XZ
            }
            if (got >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
                return outFile // GZIP
            }

            // Try to interpret as UTF-8 text pointer or JSON
            val text = try { String(header, 0, if (got>0) got else 0, Charsets.UTF_8) } catch (e: Exception) { "" }
            val trimmed = text.trim()

            // If file seems like a simple pointer (../something) follow it
            if (trimmed.startsWith("..") || trimmed.startsWith("/")) {
                val base = URL(currentUrl)
                val next = URL(base, trimmed.split("\n")[0].trim()).toString()
                currentUrl = next
                return@repeat
            }

            // Try parse JSON for OCI index/manifest
            try {
                val jsonText = outFile.readText()
                val json = JSONObject(jsonText)
                if (json.has("manifests")) {
                    val manifests = json.getJSONArray("manifests")
                    for (i in 0 until manifests.length()) {
                        val m = manifests.getJSONObject(i)
                        val platform = m.optJSONObject("platform")
                        if (platform != null && platform.optString("architecture") == "arm64") {
                            val digest = m.optString("digest")
                            if (digest.isNotBlank()) {
                                // Replace the last path segment with blobs/sha256/<digest>
                                val baseStr = currentUrl.substringBeforeLast('/')
                                currentUrl = baseStr + "/blobs/sha256/" + digest
                                return@repeat
                            }
                        }
                    }
                }

                if (json.has("layers")) {
                    val layers = json.getJSONArray("layers")
                    // pick the first layer that looks like a tar (mediaType may hint)
                    for (i in 0 until layers.length()) {
                        val layer = layers.getJSONObject(i)
                        val digest = layer.optString("digest")
                        if (digest.isNotBlank()) {
                            val baseStr = currentUrl.substringBeforeLast('/')
                            currentUrl = baseStr + "/blobs/sha256/" + digest
                            return@repeat
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore parse errors
            }

            // Nothing more to resolve; return downloaded file (may be plain tar)
            return outFile
        }

        return outFile
    }
}
