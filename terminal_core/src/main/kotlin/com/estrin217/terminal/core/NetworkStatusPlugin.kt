package com.estrin217.terminal.core

import com.estrin217.terminal.core.logger.DebugLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.util.AttributeKey
import java.io.IOException

class NetworkStatusPlugin private constructor(private val config: Config) {
    class Config {
        lateinit var applicationContext: android.content.Context
    }

    companion object Plugin : HttpClientPlugin<Config, NetworkStatusPlugin> {
        override val key: AttributeKey<NetworkStatusPlugin> = AttributeKey("NetworkStatusPlugin")

        override fun prepare(block: Config.() -> Unit): NetworkStatusPlugin {
            return NetworkStatusPlugin(Config().apply(block))
        }

        // Se cambió 'client' por 'scope' para coincidir con la firma original de HttpClientPlugin
        override fun install(plugin: NetworkStatusPlugin, scope: HttpClient) {
            scope.sendPipeline.intercept(HttpSendPipeline.Before) { subject ->
                val hasInternet = ConnectivityUtils.hasInternet(plugin.config.applicationContext)
                DebugLogger.i("NetworkStatusPlugin", "Connectivity check before request: $hasInternet")
                if (!hasInternet) {
                    val errorMessage = LocaleManager.getString("no_internet_error")
                    DebugLogger.e("NetworkStatusPlugin", "Request blocked: $errorMessage")
                    throw IOException(errorMessage)
                }
                proceedWith(subject)
            }
        }
    }
}