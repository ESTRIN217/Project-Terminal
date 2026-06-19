package com.estrin217.terminal.core

import android.content.Context
import com.estrin217.terminal.core.logger.DebugLogger
import java.io.File
import java.io.IOException

/**
 * Gestor de distribuciones Linux para PRoot, inspirado en proot-distro.
 *
 * proot-distro usa archivos .sh en /etc/proot-distro/ con el formato:
 *   DISTRO_NAME="Debian"
 *   DISTRO_ARCH="aarch64"
 *   DISTRO_URL="https://...rootfs.tar.xz"
 *
 * Esta clase replica ese modelo en Kotlin para no hardcodear URLs
 * y permitir configuraciones modulares.
 */
object ProotDistroManager {

    data class DistroConfig(
        val id: String,
        val name: String,
        val description: String,
        val architectures: Map<String, String>,  // ABI -> download URL
        val defaultShell: String = "/bin/sh",
        val color: String = "\u001B[35m",
        val icon: String = "\uD83D\uDCC1"
    )

    private val distros = mutableMapOf<String, DistroConfig>()

    init {
        registerDefaultDistros()
    }

    private fun registerDefaultDistros() {
        register(
            DistroConfig(
                id = "debian",
                name = "Debian",
                description = "Debian GNU/Linux stable-slim",
                architectures = mapOf(
                    "arm64-v8a" to "library/debian:stable-slim",
                    "x86_64" to "library/debian:stable-slim"
                ),
                defaultShell = "/bin/bash"
            )
        )
        register(
            DistroConfig(
                id = "ubuntu",
                name = "Ubuntu",
                description = "Ubuntu LTS (noble)",
                architectures = mapOf(
                    "arm64-v8a" to "library/ubuntu:noble",
                    "x86_64" to "library/ubuntu:noble"
                ),
                defaultShell = "/bin/bash"
            )
        )
        register(
            DistroConfig(
                id = "alpine",
                name = "Alpine Linux",
                description = "Alpine Linux (edge)",
                architectures = mapOf(
                    "arm64-v8a" to "library/alpine:edge",
                    "x86_64" to "library/alpine:edge"
                ),
                defaultShell = "/bin/sh",
                color = "\u001B[34m"
            )
        )
        register(
            DistroConfig(
                id = "archlinux",
                name = "Arch Linux",
                description = "Arch Linux (base)",
                architectures = mapOf(
                    "arm64-v8a" to "library/archlinux:base",
                    "x86_64" to "library/archlinux:base"
                ),
                defaultShell = "/bin/bash",
                color = "\u001B[34m"
            )
        )
    }

    fun register(config: DistroConfig) {
        distros[config.id] = config
        DebugLogger.i("ProotDistroManager", "Registered distro: ${config.id} (${config.name})")
    }

    fun getDistro(id: String): DistroConfig? = distros[id]

    fun getDistroOrThrow(id: String): DistroConfig =
        distros[id] ?: throw IllegalArgumentException("Unknown distro: $id. Available: ${distros.keys}")

    fun listDistros(): List<DistroConfig> = distros.values.toList()

    fun getSupportedDistroIds(): Set<String> = distros.keys

    /**
     * Resuelve la imagen Docker Hub para la arquitectura actual del dispositivo.
     * Retorna "image:tag" para usar con DockerHubDownloader.
     */
    fun resolveDockerImage(distroId: String): String {
        val config = getDistroOrThrow(distroId)
        val abi = getDeviceAbi()
        val image = config.architectures[abi]
            ?: config.architectures.values.first()
            ?: throw IOException("No download URL configured for distro: $distroId")
        return image
    }

    /**
     * Obtiene la URL de descarga directa para un rootfs pre-empaquetado.
     */
    fun getDirectDownloadUrl(distroId: String): String? {
        val config = getDistroOrThrow(distroId)
        val abi = getDeviceAbi()
        return config.architectures[abi]
    }

    /**
     * Devuelve la configuración de PRoot args específica para la distribución.
     * Cada distro puede necesitar bind mounts adicionales.
     */
    fun getPRootArgs(context: Context, distroId: String, shellCmd: String = "/bin/sh"): Array<String> {
        val config = getDistroOrThrow(distroId)
        val rootfsPath = File(TerminalConfig.getRootfsDir(context), distroId).absolutePath
        val prootExe = TerminalConfig.getPRootExecutable(context).absolutePath

        val baseArgs = mutableListOf(
            prootExe,
            "-r", rootfsPath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/data/data/${context.packageName}:/data_priv",
            "-w", "/home/programador"
        )

        when (config.id) {
            "alpine" -> {
                baseArgs.add("-b")
                baseArgs.add("/etc/resolv.conf:/etc/resolv.conf")
            }
        }

        baseArgs.add(shellCmd)
        return baseArgs.toTypedArray()
    }

    /**
     * Obtiene las variables de entorno específicas de la distribución.
     */
    fun getEnvironmentVariables(context: Context, distroId: String): Array<String> {
        val config = getDistroOrThrow(distroId)
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        val distroRootfs = File(rootfsDir, distroId)
        val rootfsTmpDir = File(distroRootfs, "tmp").absolutePath

        val env = mutableListOf(
            "TERM=xterm-256color",
            "HOME=/home/programador",
            "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
            "USER=programador",
            "LOGNAME=programador",
            "PROOT_TMP_DIR=$rootfsTmpDir",
            "PROOT_NO_SECCOMP=1"
        )

        when (config.id) {
            "debian", "ubuntu" -> env.add("LANG=${java.util.Locale.getDefault().toLanguageTag()}.UTF-8")
            "alpine" -> env.add("LANG=C.UTF-8")
        }

        return env.toTypedArray()
    }

    /**
     * Instala una distribución: descarga desde Docker Hub y extrae.
     */
    suspend fun installDistro(
        context: Context,
        distroId: String,
        progressCallback: (Int) -> Unit = {}
    ) {
        val config = getDistroOrThrow(distroId)
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        val distroRootfs = File(rootfsDir, distroId)

        DebugLogger.i("ProotDistroManager", "Installing distro: ${config.id} (${config.name}) into ${distroRootfs.absolutePath}")

        if (distroRootfs.exists()) {
            DebugLogger.w("ProotDistroManager", "Rootfs directory for ${config.id} already exists, purging")
            distroRootfs.deleteRecursively()
        }
        distroRootfs.mkdirs()

        val image = resolveDockerImage(distroId)
        DebugLogger.i("ProotDistroManager", "Resolved Docker image: $image")

        val downloadedBlob = kotlinx.coroutines.runBlocking {
            DockerHubDownloader.downloadFromDockerHub(context, image.substringBefore(":"), image.substringAfter(":"))
        }

        DebugLogger.i("ProotDistroManager", "Download complete. Extracting...")

        try {
            val nativeSuccess = kotlinx.coroutines.runBlocking {
                RootfsDecompressor.extractDebianRootfs(context, downloadedBlob, distroRootfs)
            }
            if (!nativeSuccess) {
                DebugLogger.i("ProotDistroManager", "Native extraction unavailable, falling back to Java")
                if (distroRootfs.exists()) distroRootfs.deleteRecursively()
                distroRootfs.mkdirs()
                java.io.FileInputStream(downloadedBlob).use { inputStream ->
                    RootfsManager.extractTarArchive(inputStream, distroRootfs, progressCallback)
                }
            }
        } catch (e: Exception) {
            DebugLogger.e("ProotDistroManager", "Extraction failed for ${config.id}", e)
            if (distroRootfs.exists()) distroRootfs.deleteRecursively()
            throw e
        }

        // Post-install setup
        File(distroRootfs, "home/programador").mkdirs()
        val tmpDir = File(distroRootfs, "tmp")
        tmpDir.mkdirs()
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)
        tmpDir.setReadable(true, false)

        RootfsManager.fixRootfsPermissions(distroRootfs)
        RootfsManager.verifyCriticalSymlinks(distroRootfs)
        RootfsManager.repairCriticalSymlinks(distroRootfs)

        downloadedBlob.delete()
        DebugLogger.i("ProotDistroManager", "Distro ${config.id} installed successfully")
    }

    fun isDistroInstalled(context: Context, distroId: String): Boolean {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        val distroRootfs = File(rootfsDir, distroId)
        val marker = File(distroRootfs, TerminalConfig.MARKER_FILE_NAME)
        return marker.exists()
    }

    fun getInstalledDistros(context: Context): List<DistroConfig> {
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        return distros.values.filter { config ->
            val marker = File(File(rootfsDir, config.id), TerminalConfig.MARKER_FILE_NAME)
            marker.exists()
        }
    }

    fun removeDistro(context: Context, distroId: String) {
        val config = getDistroOrThrow(distroId)
        val rootfsDir = TerminalConfig.getRootfsDir(context)
        val distroRootfs = File(rootfsDir, config.id)
        if (distroRootfs.exists()) {
            distroRootfs.deleteRecursively()
            DebugLogger.i("ProotDistroManager", "Removed distro: ${config.id}")
        }
    }

    /**
     * Carga una configuración de distribución desde un mapa (útil para deserializar JSON).
     */
    fun loadFromMap(id: String, map: Map<String, Any>): DistroConfig {
        @Suppress("UNCHECKED_CAST")
        val archMap = (map["architectures"] as? Map<String, String>) ?: emptyMap()
        return DistroConfig(
            id = id,
            name = map["name"] as? String ?: id,
            description = map["description"] as? String ?: "",
            architectures = archMap,
            defaultShell = map["defaultShell"] as? String ?: "/bin/sh",
            color = map["color"] as? String ?: "\u001B[35m",
            icon = map["icon"] as? String ?: "\uD83D\uDCC1"
        )
    }

    /**
     * Detecta la ABI del dispositivo actual.
     */
    private fun getDeviceAbi(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        DebugLogger.d("ProotDistroManager", "Device ABI: $abi")
        return abi
    }
}
