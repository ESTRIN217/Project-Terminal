package com.estrin217.terminal.core

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object DockerHubDownloader {
    private const val AUTH_URL = "https://auth.docker.io/token"
    private const val REGISTRY_BASE_URL = "https://registry-1.docker.io/v2"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TokenResponse(val token: String = "")

    @Serializable
    private data class DockerManifestLayer(
        val mediaType: String? = null,
        val digest: String = "",
        val size: Long? = null
    )

    @Serializable
    private data class DockerPlatform(
        val architecture: String? = null,
        val os: String? = null
    )

    @Serializable
    private data class DockerManifestReference(
        val mediaType: String? = null,
        val digest: String = "",
        val size: Long? = null,
        val platform: DockerPlatform? = null
    )

    @Serializable
    private data class DockerManifest(
        val schemaVersion: Int? = null,
        val mediaType: String? = null,
        val config: JsonElement? = null,
        val layers: List<DockerManifestLayer> = emptyList(),
        val manifests: List<DockerManifestReference> = emptyList()
    )

    private fun isNetworkAvailable(context: Context): Boolean {
        return ConnectivityUtils.hasInternet(context)
    }

    suspend fun downloadFromDockerHub(context: Context, image: String, tag: String): File {
        val repo = if (image.contains("/")) image else "library/$image"
        
        com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Starting Docker Hub rootfs download check for $repo:$tag")

        if (!isNetworkAvailable(context)) {
            val errMsg = LocaleManager.getString("no_internet_error")
            com.estrin217.terminal.core.logger.DebugLogger.e("DockerHubDownloader", "Pre-download network validation failed: $errMsg")
            throw IOException(errMsg)
        }

        try {
            HttpClient(CIO) {
                install(NetworkStatusPlugin) {
                    applicationContext = context
                }
                install(ContentNegotiation) {
                    json(json)
                }
            }.use { client ->
                com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Requesting auth token from $AUTH_URL for image $repo")
                val token = client.get(AUTH_URL) {
                    url {
                        parameter("service", "registry.docker.io")
                        parameter("scope", "repository:$repo:pull")
                    }
                }.body<TokenResponse>().token

                if (token.isBlank()) {
                    val errMsg = LocaleManager.getString("connection_error") + ": Failed to obtain Docker Hub token"
                    com.estrin217.terminal.core.logger.DebugLogger.e("DockerHubDownloader", errMsg)
                    throw IOException(errMsg)
                }

                com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Token obtained successfully. Fetching manifest...")
                val manifest = fetchManifest(client, repo, tag, token)
                
                com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Manifest fetched. Resolving layers...")
                val layers = resolveLayers(client, repo, token, manifest)
                
                val chosenLayer = chooseLayer(layers)
                    ?: throw IOException("No suitable layer found in manifest for $repo:$tag")

                com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Layer resolved: ${chosenLayer.digest}. Commencing download...")
                val blobUrl = "$REGISTRY_BASE_URL/$repo/blobs/${chosenLayer.digest}"
                val outFile = File(context.cacheDir, "docker_rootfs_${repo.replace('/', '_')}_$tag.bin")

                downloadBlobWithStreaming(client, blobUrl, token, outFile)
                com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Download completed successfully: ${outFile.absolutePath}")
                return outFile
            }
        } catch (e: Exception) {
            com.estrin217.terminal.core.logger.DebugLogger.e("DockerHubDownloader", "Critical error during download from Docker Hub", e)
            throw if (e is IOException) e else IOException(LocaleManager.getString("connection_error") + ": " + e.message, e)
        }
    }

    private suspend fun fetchManifest(client: HttpClient, repo: String, tag: String, token: String): DockerManifest {
        val url = "$REGISTRY_BASE_URL/$repo/manifests/$tag"
        com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Fetching manifest from URL: $url")
        val response = client.get(url) {
            header("Authorization", "Bearer $token")
            header(
                "Accept",
                "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json"
            )
        }

        if (!response.status.value.toString().startsWith("2")) {
            val errMsg = "Failed to obtain Docker manifest: ${response.status}"
            com.estrin217.terminal.core.logger.DebugLogger.e("DockerHubDownloader", errMsg)
            throw IOException(errMsg)
        }

        val manifestText = response.bodyAsText()
        return json.decodeFromString(DockerManifest.serializer(), manifestText)
    }

    private suspend fun resolveLayers(client: HttpClient, repo: String, token: String, manifest: DockerManifest): List<DockerManifestLayer> {
        if (manifest.layers.isNotEmpty()) {
            return manifest.layers
        }

        val reference = manifest.manifests.firstOrNull { it.platform?.architecture == "arm64" }
            ?: manifest.manifests.firstOrNull()
            ?: throw IOException("No manifest entries found for repository $repo")

        val digest = reference.digest
        if (digest.isBlank()) {
            throw IOException("Selected manifest reference has no digest for $repo")
        }

        val url = "$REGISTRY_BASE_URL/$repo/manifests/$digest"
        com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Resolving nested layer manifest from URL: $url")
        val response = client.get(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
        }

        if (!response.status.value.toString().startsWith("2")) {
            val errMsg = "Failed to obtain Docker manifest for digest $digest: ${response.status}"
            com.estrin217.terminal.core.logger.DebugLogger.e("DockerHubDownloader", errMsg)
            throw IOException(errMsg)
        }

        val manifestText = response.bodyAsText()
        return json.decodeFromString(DockerManifest.serializer(), manifestText).layers
    }

    private fun chooseLayer(layers: List<DockerManifestLayer>): DockerManifestLayer? {
        return layers.firstOrNull { layer ->
            val media = layer.mediaType?.lowercase() ?: ""
            media.contains("tar") || media.contains("gzip") || media.contains("xz") || media.contains("rootfs")
        } ?: layers.firstOrNull()
    }

    private suspend fun downloadBlobWithStreaming(client: HttpClient, url: String, token: String, targetFile: File) {
        com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Downloading layer blob from URL: $url")
        val response = client.get(url) {
            header("Authorization", "Bearer $token")
        }

        if (!response.status.value.toString().startsWith("2")) {
            val errMsg = "Failed to download layer blob: ${response.status}"
            com.estrin217.terminal.core.logger.DebugLogger.e("DockerHubDownloader", errMsg)
            throw IOException(errMsg)
        }

        val channel = response.bodyAsChannel()
        try {
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesWritten = 0L
                while (true) {
                    val packet = channel.readRemaining(8192L)
                    if (packet.exhausted()) break
                    val bytesRead = packet.readAvailable(buffer)
                    if (bytesRead <= 0) break
                    output.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead
                }
                com.estrin217.terminal.core.logger.DebugLogger.i("DockerHubDownloader", "Streaming complete. Total bytes written: $bytesWritten")
            }
        } catch (e: Exception) {
            com.estrin217.terminal.core.logger.DebugLogger.e("DockerHubDownloader", "Error writing downloaded blob to file", e)
            throw e
        } finally {
            channel.cancel(null)
        }
    }
}
