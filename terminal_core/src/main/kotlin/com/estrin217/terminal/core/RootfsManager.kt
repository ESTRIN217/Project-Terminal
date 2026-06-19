package com.estrin217.terminal.core

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
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

        // Extracción: intentar bsdtar nativo primero, fallback a Java
        try {
            val nativeSuccess = runBlocking {
                RootfsDecompressor.extractDebianRootfs(context, downloadedBlob, rootfsDir)
            }
            if (!nativeSuccess) {
                com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Native extraction unavailable, falling back to Java-based extraction")
                if (rootfsDir.exists()) {
                    com.estrin217.terminal.core.logger.DebugLogger.w("RootfsManager", "Cleaning up partial extraction from failed native attempt before fallback")
                    rootfsDir.deleteRecursively()
                }
                rootfsDir.mkdirs()
                java.io.FileInputStream(downloadedBlob).use { inputStream ->
                    extractTarArchive(inputStream, rootfsDir, progressCallback)
                }
            }
        } catch (e: Exception) {
            com.estrin217.terminal.core.logger.DebugLogger.e("RootfsManager", "Error extracting rootfs tar archive", e)
            // Limpieza completa en caso de fallo para evitar estado inconsistente
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            throw e
        }

        com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Creating fallback directories: /home/programador and /tmp")
        // Asegurar las estructuras de directorios base del entorno integrado
        File(rootfsDir, "home/programador").mkdirs()
        val tmpDir = File(rootfsDir, "tmp")
        tmpDir.mkdirs()
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)
        tmpDir.setReadable(true, false)

        // Forzar permisos de ejecución en binarios críticos
        fixRootfsPermissions(rootfsDir)

        // NOTA: el marcador .installed se crea en MainActivity DESPUÉS de validateRootfsOrThrow
        // para evitar que quede un marcador huérfano si la validación falla

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
                try {
                    java.nio.file.Files.deleteIfExists(destFile.toPath())
                } catch (_: Exception) {}
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

                // Restaurar permisos POSIX reales desde el modo del entry en el tar
                destFile.restorePermissionsFromMode(entry.mode)
            }

            entryCount++
            progressCallback(entryCount) 
            entry = tarIn.nextEntry
        }

        tarIn.close()
    }
    private fun File.restorePermissionsFromMode(mode: Int) {
        val executable = (mode and 0b001001001) != 0
        if (executable) {
            this.setExecutable(true, false)
        }
        val readable = (mode and 0b100100100) != 0
        if (readable) {
            this.setReadable(true, false)
        }
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
                        path.startsWith("lib/") ||
                        path.startsWith("lib64/") ||
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
     * Fuerza permisos de lectura y ejecución en binarios críticos que PRoot necesita
     * para arrancar una sesión de shell. También repara enlaces simbólicos rotos
     * (ej: /bin/sh -> inexistente) recreándolos contra un shell real disponible.
     */
    private fun fixRootfsPermissions(rootfsDir: File) {
        val criticalBinaries = listOf(
            "bin/sh",
            "bin/bash",
            "usr/bin/sh",
            "usr/bin/bash"
        )

        criticalBinaries.forEach { relPath ->
            val binFile = File(rootfsDir, relPath)

            if (!binFile.exists() && java.nio.file.Files.isSymbolicLink(binFile.toPath())) {
                recreateBrokenSymlink(binFile, rootfsDir)
            }

            if (binFile.exists()) {
                binFile.setExecutable(true, false)
                binFile.setReadable(true, false)
            }
        }

        // Buscar y reparar linkers dinámicos (ld-linux-*, ld.so.*) en todo el rootfs
        rootfsDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val name = file.name
                if (name.startsWith("ld-linux") || name.startsWith("ld.so")) {
                    if (!file.canExecute()) {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                        com.estrin217.terminal.core.logger.DebugLogger.i(
                            "RootfsManager",
                            "Fixed linker permissions: ${file.relativeTo(rootfsDir).path}"
                        )
                    }
                }
            }
        }
    }

    private fun recreateBrokenSymlink(symlink: File, rootfsDir: File) {
        val rawTarget = java.nio.file.Files.readSymbolicLink(symlink.toPath())
        val resolvedTarget = symlink.parentFile?.toPath()?.resolve(rawTarget)?.toFile()
        if (resolvedTarget != null && !resolvedTarget.exists()) {
            val realShell = findRealShell(rootfsDir)
            if (realShell != null) {
                symlink.delete()
                try {
                    android.system.Os.symlink(realShell, symlink.absolutePath)
                    com.estrin217.terminal.core.logger.DebugLogger.i(
                        "RootfsManager",
                        "Recreated broken symlink ${symlink.name} -> $realShell"
                    )
                } catch (e: Exception) {
                    com.estrin217.terminal.core.logger.DebugLogger.w(
                        "RootfsManager",
                        "Failed to recreate symlink ${symlink.name} -> $realShell",
                        e
                    )
                }
            } else {
                com.estrin217.terminal.core.logger.DebugLogger.w(
                    "RootfsManager",
                    "No real shell found to fix broken symlink ${symlink.absolutePath}"
                )
            }
        }
    }

    private fun findRealShell(rootfsDir: File): String? {
        val candidates = listOf(
            "bin/bash", "usr/bin/bash",
            "bin/dash", "usr/bin/dash",
            "bin/ash", "usr/bin/ash"
        )
        for (relPath in candidates) {
            val file = File(rootfsDir, relPath)
            if (file.exists() && file.canExecute()) {
                return "/$relPath"
            }
        }
        return null
    }

    fun forceReinstall(context: Context) {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        val marker = TerminalConfig.getMarkerFile(context)
        if (marker.exists()) {
            marker.delete()
            com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Deleted .installed marker to force reinstall")
        }
        val permsMarker = File(rootfsDir, PERMS_MARKER)
        if (permsMarker.exists()) {
            permsMarker.delete()
            com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Deleted .perms_fixed marker")
        }
    }

    fun diagnosePermissions(context: Context) {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        val criticalPaths = listOf(
            "bin/sh", "bin/bash",
            "usr/bin/sh", "usr/bin/bash",
            "lib/ld-linux-aarch64.so.1",
            "lib/ld-linux-armhf.so.3",
            "lib/ld-linux-x86-64.so.2",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
            "lib64/ld-linux-x86-64.so.2"
        )
        criticalPaths.forEach { relPath ->
            val file = File(rootfsDir, relPath)
            if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
                com.estrin217.terminal.core.logger.DebugLogger.d(
                    "PermissionsDiag",
                    "$relPath: exists=true isSymlink=${java.nio.file.Files.isSymbolicLink(file.toPath())} canExe=${file.canExecute()} canRead=${file.canRead()}"
                )
            }
        }
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

    // Volcar InputStream a un archivo temporal para intentar extracción nativa
    val tempFile = File(context.cacheDir, "import_rootfs_${System.currentTimeMillis()}.tar")
    try {
        tempFile.outputStream().use { output -> inputStream.copyTo(output) }

        try {
            val nativeSuccess = runBlocking {
                RootfsDecompressor.extractDebianRootfs(context, tempFile, rootfsDir)
            }
            if (!nativeSuccess) {
                com.estrin217.terminal.core.logger.DebugLogger.i("RootfsManager", "Native extraction unavailable for custom rootfs, falling back to Java")
                tempFile.inputStream().use { savedStream ->
                    extractTarArchive(savedStream, rootfsDir, progressCallback)
                }
            }
        } catch (e: Exception) {
            com.estrin217.terminal.core.logger.DebugLogger.e("RootfsManager", "Error extracting custom rootfs", e)
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            throw e
        }
    } finally {
        tempFile.delete()
    }

    // Estructuras base obligatorias
    File(rootfsDir, "home/programador").mkdirs()
    val customTmp = File(rootfsDir, "tmp")
    customTmp.mkdirs()
    customTmp.setWritable(true, false)
    customTmp.setExecutable(true, false)
    customTmp.setReadable(true, false)

    fixRootfsPermissions(rootfsDir)
    
    // NOTA: el marcador .installed se crea en MainActivity DESPUÉS de la validación
    }
}