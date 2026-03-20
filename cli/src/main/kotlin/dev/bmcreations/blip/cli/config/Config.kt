package dev.bmcreations.blip.cli.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BlipConfig(
    val token: String? = null,
    val apiUrl: String = "https://api.useblip.email",
)

object ConfigManager {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configDir = File(System.getProperty("user.home"), ".config/blip")
    private val configFile = File(configDir, "config.json")

    fun load(): BlipConfig {
        if (!configFile.exists()) return BlipConfig()
        return try {
            json.decodeFromString<BlipConfig>(configFile.readText())
        } catch (_: Exception) {
            BlipConfig()
        }
    }

    fun save(config: BlipConfig) {
        configDir.mkdirs()
        configFile.writeText(json.encodeToString(BlipConfig.serializer(), config))
    }

    fun getToken(): String? = load().token

    fun saveToken(token: String) {
        save(load().copy(token = token))
    }

    fun clearToken() {
        save(load().copy(token = null))
    }

    fun getApiUrl(): String = System.getenv("BLIP_API_URL") ?: load().apiUrl
}
