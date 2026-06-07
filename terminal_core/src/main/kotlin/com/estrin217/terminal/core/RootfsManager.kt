package com.estrin217.terminal.core

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.runBlocking

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

        // Prepare InputStream for the rootfs archive. Strategy:
        // 1) If a remote URL is configured, try to download and use it.
        // 2) Try an externally provided file in the app external files dir (useful for manual installs).
        // 3) Fall back to the bundled asset if present.
        val assetManager = context.assets
        val attemptedLocations = mutableListOf<String>()
        var rootfsInputStream: BufferedInputStream? = null

        // 1) Remote URL
        if (TerminalConfig.ROOTFS_REMOTE_URL.isNotBlank()) {
            try {
                val downloaded = downloadRemoteRootfs(context, TerminalConfig.ROOTFS_REMOTE_URL)
                attemptedLocations.add("remote: ${downloaded.absolutePath}")
                rootfsInputStream = BufferedInputStream(java.io.FileInputStream(downloaded))
            } catch (e: Exception) {
                // log and continue to next fallback
                attemptedLocations.add("remote failed: ${e.message}")
            }
        }

        // 2) External files dir (allow user to drop the archive into app external files)
        if (rootfsInputStream == null) {
            try {
                val external = File(context.getExternalFilesDir(null), TerminalConfig.ROOTFS_ASSET_NAME)
                if (external.exists()) {
                    attemptedLocations.add("external: ${external.absolutePath}")
                    rootfsInputStream = BufferedInputStream(java.io.FileInputStream(external))
                } else {
                    attemptedLocations.add("external not found: ${external.absolutePath}")
                }
            } catch (e: Exception) {
                attemptedLocations.add("external failed: ${e.message}")
            }
        }

        // 3) Bundled asset
        if (rootfsInputStream == null) {
            try {
                attemptedLocations.add("asset: ${TerminalConfig.ROOTFS_ASSET_NAME}")
                rootfsInputStream = BufferedInputStream(assetManager.open(TerminalConfig.ROOTFS_ASSET_NAME))
            } catch (e: Exception) {
                attemptedLocations.add("asset failed: ${e.message}")
            }
        }

        if (rootfsInputStream == null) {
            throw IOException("Rootfs archive not found. Tried: ${attemptedLocations.joinToString("; ")}")
        }

        val wrappedRootfsInput = rootfsInputStream

        val header = ByteArray(6)
        wrappedRootfsInput.mark(8192)
        val read = wrappedRootfsInput.read(header)
        wrappedRootfsInput.reset()

        val tarIn = when {
            read >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() && header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() && header[4] == 0x5A.toByte() && header[5] == 0x00.toByte() -> {
                // XZ compressed
                val xzIn = XZCompressorInputStream(wrappedRootfsInput)
                TarArchiveInputStream(xzIn)
            }
            read >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> {
                // GZIP compressed
                val gzIn = GZIPInputStream(wrappedRootfsInput)
                TarArchiveInputStream(gzIn)
            }
            else -> {
                // Assume plain tar
                TarArchiveInputStream(wrappedRootfsInput)
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

    @Throws(IOException::class)
    fun installFromUri(context: Context, uri: Uri, progressCallback: (Int) -> Unit = {}) {
        val out = File(context.getExternalFilesDir(null), TerminalConfig.ROOTFS_ASSET_NAME)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open selected URI")

        installFromFile(context, out, progressCallback)
    }

    @Throws(IOException::class)
    fun installFromFile(context: Context, file: File, progressCallback: (Int) -> Unit = {}) {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        val input = BufferedInputStream(java.io.FileInputStream(file))

        val header = ByteArray(6)
        input.mark(8192)
        val read = input.read(header)
        input.reset()

        val tarIn = when {
            read >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() && header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() && header[4] == 0x5A.toByte() && header[5] == 0x00.toByte() -> {
                val xzIn = XZCompressorInputStream(input)
                TarArchiveInputStream(xzIn)
            }
            read >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> {
                val gzIn = GZIPInputStream(input)
                TarArchiveInputStream(gzIn)
            }
            else -> TarArchiveInputStream(input)
        }

        var entry = tarIn.nextEntry
        var entryCount = 0

        while (entry != null) {
            val destFile = File(rootfsDir, entry.name)

            val canonicalDest = destFile.canonicalPath
            val canonicalRoot = rootfsDir.canonicalPath
            if (!canonicalDest.startsWith(canonicalRoot + File.separator)) {
                throw IOException("Security Violation: Entry path traversal detected in tar: ${entry.name}")
            }

            if (entry.isDirectory) destFile.mkdirs() else {
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { outputStream ->
                    tarIn.copyTo(outputStream)
                }

                val path = entry.name
                val isExecutable = path.contains("bin/") || path.contains("sbin/") || path.contains("libexec/") || path.endsWith(".sh") || path.endsWith(".so")
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

        File(rootfsDir, "home/programador").mkdirs()
        File(rootfsDir, "tmp").mkdirs()

        TerminalConfig.getMarkerFile(context).createNewFile()
    }

    /**
     * Descarga la capa raíz de una imagen desde Docker Hub (registries públicas)
     */
    @Throws(IOException::class)
    fun downloadFromDockerHub(context: Context, image: String, tag: String): File = runBlocking {
        DockerHubDownloader.downloadFromDockerHub(context, image, tag)
    }
}

