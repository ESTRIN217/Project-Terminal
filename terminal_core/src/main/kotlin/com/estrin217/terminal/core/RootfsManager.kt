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
        return TerminalConfig.getMarkerFile(context).exists()
    }

    /**
     * Descarga el rootfs directamente desde Docker Hub y lo extrae 
     * en el almacenamiento privado de la aplicación.
     */
    @Throws(IOException::class)
    fun install(context: Context, progressCallback: (extractedEntries: Int) -> Unit = {}) {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        if (rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
        }
        rootfsDir.mkdirs()

        // Único modo de obtención: Descarga directa desde Docker Hub
        val image = TerminalConfig.DOCKER_IMAGE
        val tag = TerminalConfig.DOCKER_TAG

        val downloadedBlob = runBlocking {
            DockerHubDownloader.downloadFromDockerHub(context, image, tag) 
        }

        // Extracción del tarball descargado
        java.io.FileInputStream(downloadedBlob).use { inputStream ->
            extractTarArchive(inputStream, rootfsDir, progressCallback)
        }

        // Asegurar las estructuras de directorios base del entorno integrado
        File(rootfsDir, "home/programador").mkdirs()
        File(rootfsDir, "tmp").mkdirs()

        // Crear el marcador de instalación completada exitosamente
        TerminalConfig.getMarkerFile(context).createNewFile() 

        // Limpieza del archivo binario temporal de la caché
        downloadedBlob.delete()
    }

    /**
     * Procesa, descomprime y extrae de forma unificada flujos de datos TAR, XZ o GZIP (DRY).
     */
    @Throws(IOException::class)
    private fun extractTarArchive(inputStream: InputStream, rootfsDir: File, progressCallback: (Int) -> Unit) {
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
                val sample = String(header, 0, read.coerceAtMost(read), Charsets.UTF_8).trim()
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
    }
}