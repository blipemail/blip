package dev.bmcreations.blip.cli.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

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
        } catch (e: Exception) {
            // Falling back to an empty config would mint a new session and overwrite
            // the saved token, so stop and let the user fix or remove the file.
            System.err.println("Error: could not read ${configFile.path}: ${e.message}")
            System.err.println("Fix the file or delete it to start a new session.")
            exitProcess(1)
        }
    }

    fun save(config: BlipConfig) {
        configDir.mkdirs()
        restrictPermissions(configDir, "rwx------")
        if (!configFile.exists()) {
            try {
                Files.createFile(
                    configFile.toPath(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystem (Windows); the file is created by writeText below.
            }
        }
        restrictPermissions(configFile, "rw-------")
        configFile.writeText(json.encodeToString(BlipConfig.serializer(), config))
    }

    private fun restrictPermissions(file: File, perms: String) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
        } catch (_: UnsupportedOperationException) {
            // Windows: no POSIX permissions.
        }
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
