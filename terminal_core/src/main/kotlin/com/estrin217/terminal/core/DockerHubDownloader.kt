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
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.core.isEmpty
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

    suspend fun downloadFromDockerHub(context: Context, image: String, tag: String): File {
        val repo = if (image.contains("/")) image else "library/$image"

        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
        }.use { client ->
            val token = client.get(AUTH_URL) {
                url {
                    parameter("service", "registry.docker.io")
                    parameter("scope", "repository:$repo:pull")
                }
            }.body<TokenResponse>().token

            if (token.isBlank()) {
                throw IOException("Failed to obtain Docker Hub token")
            }

            val manifest = fetchManifest(client, repo, tag, token)
            val layers = resolveLayers(client, repo, token, manifest)
            val chosenLayer = chooseLayer(layers)
                ?: throw IOException("No suitable layer found in manifest for $repo:$tag")

            val blobUrl = "$REGISTRY_BASE_URL/$repo/blobs/${chosenLayer.digest}"
            val outFile = File(context.cacheDir, "docker_rootfs_${repo.replace('/', '_')}_$tag.bin")

            downloadBlobWithStreaming(client, blobUrl, token, outFile)
            return outFile
        }
    }

    private suspend fun fetchManifest(client: HttpClient, repo: String, tag: String, token: String): DockerManifest {
        val response = client.get("$REGISTRY_BASE_URL/$repo/manifests/$tag") {
            header("Authorization", "Bearer $token")
            header(
                "Accept",
                "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json"
            )
        }

        if (!response.status.value.toString().startsWith("2")) {
            throw IOException("Failed to obtain Docker manifest: ${response.status}")
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

        val response = client.get("$REGISTRY_BASE_URL/$repo/manifests/$digest") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
        }

        if (!response.status.value.toString().startsWith("2")) {
            throw IOException("Failed to obtain Docker manifest for digest $digest: ${response.status}")
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
        val response = client.get(url) {
            header("Authorization", "Bearer $token")
        }

        if (!response.status.value.toString().startsWith("2")) {
            throw IOException("Failed to download layer blob: ${response.status}")
        }

        val channel = response.bodyAsChannel()
        try {
            targetFile.outputStream().use { output ->
                while (true) {
                    val packet = channel.readRemaining(8192L)
                    if (packet.isEmpty) break
                    output.write(packet.readBytes())
                }
            }
        } finally {
            channel.cancel(null)
        }
    }
}
