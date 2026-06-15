package com.estrin217.terminal.core

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking

object RootfsManager {

    /**
     * Comprueba si el entorno Linux rootfs ya está instalado.
     */
    fun isInstalled(context: Context): Boolean {
        val marker = TerminalConfig.getMarkerFile(context)
        val installed = marker.exists()
        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Checking installation status at ${marker.absolutePath}: installed=$installed")
        return installed
    }

    /**
     * Descarga el rootfs directamente desde Docker Hub y lo extrae 
     * en el almacenamiento privado de la aplicación.
     */
    @Throws(IOException::class)
    fun install(context: Context, progressCallback: (extractedEntries: Int) -> Unit = {}) {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Starting rootfs installation into ${rootfsDir.absolutePath}")
        
        if (rootfsDir.exists()) {
            com.estrin217.terminal.core.logger.DebugLogger.w("RootfsManager", "Rootfs directory already exists. Purging it before install.")
            rootfsDir.deleteRecursively()
        }
        rootfsDir.mkdirs()

        // Único modo de obtención: Descarga directa desde Docker Hub
        val image = TerminalConfig.DOCKER_IMAGE
        val tag = TerminalConfig.DOCKER_TAG

        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Resolving rootfs package from Docker Hub: image=$image, tag=$tag")
        val downloadedBlob = runBlocking {
            DockerHubDownloader.downloadFromDockerHub(context, image, tag) 
        }

        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Rootfs download complete: ${downloadedBlob.absolutePath}. Commencing extraction...")

        // Extracción del tarball descargado
        try {
            java.io.FileInputStream(downloadedBlob).use { inputStream ->
                extractTarArchive(inputStream, rootfsDir, progressCallback)
            }
        } catch (e: Exception) {
            com.estrin217.terminal.core.logger.DebugLogger.e("RootfsManager", "Error extracting rootfs tar archive", e)
            throw e
        }

        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Creating fallback directories: /home/programador and /tmp")
        // Asegurar las estructuras de directorios base del entorno integrado
        File(rootfsDir, "home/programador").mkdirs()
        File(rootfsDir, "tmp").mkdirs()

        // Crear el marcador de instalación completada exitosamente
        val marker = TerminalConfig.getMarkerFile(context)
        marker.createNewFile() 
        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Installation completed. Created marker: ${marker.absolutePath}")

        // Limpieza del archivo binario temporal de la caché
        if (downloadedBlob.delete()) {
            com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Cleaned up temporary blob file.")
        } else {
            com.estrin217.terminal.core.logger.DebugLogger.w("RootfsManager", "Failed to clean up temporary blob file: ${downloadedBlob.absolutePath}")
        }
    }

    /**
     * Procesa, descomprime y extrae de forma unificada flujos de datos TAR, XZ o GZIP (DRY).
     */
    @Throws(IOException::class)
    internal fun extractTarArchive(inputStream: InputStream, rootfsDir: File, progressCallback: (Int) -> Unit) {
        val bufferedInput = BufferedInputStream(inputStream)
        
        val header = ByteArray(6)
        bufferedInput.mark(8192)
        val read = bufferedInput.read(header)
        bufferedInput.reset()

        val tarIn = when {
            // Validación de cabecera mágica para archivos comprimidos con XZ
            read >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() && header[2] == 0x7A.toByte() && 
            header[3] == 0x58.toByte() && header[4] == 0x5A.toByte() && header[5] == 0x00.toByte() -> {
                TarArchiveInputStream(XZCompressorInputStream(bufferedInput)) 
            }
            // Validación de cabecera mágica para archivos comprimidos con GZIP
            read >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> {
                TarArchiveInputStream(GZIPInputStream(bufferedInput)) 
            }
            else -> {
                // Validación de resguardo frente a respuestas erróneas en formato Texto/JSON (como errores 404)
                val sample = String(header, 0, read.coerceAtMost(header.size), Charsets.UTF_8).trim()
                if (sample.startsWith("{") || sample.startsWith("anti") || sample.startsWith("appl") || sample.startsWith("404")) { 
                    throw IOException("Error: Se esperaba un archivo TAR comprimido, pero se recibió texto/JSON: '$sample'") 
                }
                TarArchiveInputStream(bufferedInput)
            }
        }

        var entry = tarIn.nextEntry
        var entryCount = 0

        while (entry != null) {
            val destFile = File(rootfsDir, entry.name) 

            // Protección crítica contra vulnerabilidades Path Traversal (Zip Slip)
            val canonicalDest = destFile.canonicalPath
            val canonicalRoot = rootfsDir.canonicalPath
            // Allow entries that resolve to the rootfs directory itself (e.g. './')
            if (!(canonicalDest == canonicalRoot || canonicalDest.startsWith(canonicalRoot + File.separator))) {
                throw IOException("Security Violation: Entry path traversal detected in tar: ${entry.name}")
            }

            if (entry.isDirectory) {
                destFile.mkdirs()
            } else if (entry.isSymbolicLink) {
                destFile.parentFile?.mkdirs()
                if (destFile.exists()) {
                    destFile.delete()
                }
                try {
                    android.system.Os.symlink(entry.linkName, destFile.absolutePath)
                } catch (e: Exception) {
                    throw IOException("Failed to create symlink from ${entry.linkName} to ${destFile.absolutePath}", e)
                }
            } else {
                destFile.parentFile?.mkdirs()
                if (destFile.exists()) {
                    destFile.delete()
                }
                FileOutputStream(destFile).use { outputStream ->
                    tarIn.copyTo(outputStream) 
                }

                // Asignación automática de permisos de lectura y ejecución para binarios y scripts del sistema
                val path = entry.name
                val isExecutable = path.contains("bin/") ||
                        path.contains("sbin/") ||
                        path.contains("libexec/") ||
                        path.endsWith(".sh") ||
                        path.endsWith(".so") ||
                        path.contains(".so.") // Captura .so.1, .so.6, etc. (loaders, libc)
                        
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
    }
    /**
     * Fix para rootfs existente: asigna permisos de ejecución a todos los .so y .so.*
     * (loaders como ld-linux-aarch64.so.1, libc.so.6, etc.).
     * Se invoca automáticamente si el marcador de fix-permisos no existe.
     */
    private const val PERMS_MARKER = ".perms_fixed"

    fun ensureLoaderPermissions(context: Context) {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        val permsMarker = File(rootfsDir, PERMS_MARKER)
        if (permsMarker.exists()) return

        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Fixing loader/library permissions in rootfs")
        val fixed = fixLoaderPermissions(rootfsDir)
        if (fixed > 0) {
            com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Fixed $fixed files with execute permissions")
        }
        permsMarker.createNewFile()
    }

    private fun fixLoaderPermissions(rootfsDir: File): Int {
        var count = 0
        rootfsDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val name = file.name
                val path = file.relativeTo(rootfsDir).path
                val needsExec = name.endsWith(".so") ||
                        name.contains(".so.") ||
                        path.startsWith("bin/") ||
                        path.startsWith("sbin/") ||
                        path.startsWith("usr/bin/") ||
                        path.startsWith("usr/sbin/") ||
                        path.startsWith("usr/libexec/") ||
                        name.endsWith(".sh")
                if (needsExec && !file.canExecute()) {
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                    count++
                }
            }
        }
        return count
    }

    /**
     * Importa un archivo rootfs externo desde un InputStream (por ejemplo, desde el almacenamiento local).
     */
    @Throws(IOException::class)
    fun importCustomRootfs(context: Context, inputStream: InputStream, progressCallback: (Int) -> Unit = {}) {
    val rootfsDir = TerminalConfig.getRootfsDir(context)
    if (rootfsDir.exists()) {
        rootfsDir.deleteRecursively()
    }
    rootfsDir.mkdirs()

    // Llamamos internamente a la extracción sin problemas de visibilidad
    extractTarArchive(inputStream, rootfsDir, progressCallback)

    // Estructuras base obligatorias
    File(rootfsDir, "home/programador").mkdirs()
    File(rootfsDir, "tmp").mkdirs()
    
    // Crear el marcador para que la app sepa que está listo
    TerminalConfig.getMarkerFile(context).createNewFile()
    }
}