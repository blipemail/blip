package dev.bmcreations.blip.cli.client

import dev.bmcreations.blip.models.EmailSummaryDTO
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

class SseClient(
    private val url: String,
    private val onEmail: (EmailSummaryDTO) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connectTimeout = 5000
            connection.readTimeout = 0 // No timeout for SSE

            try {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                var eventType = ""
                var data = ""

                while (isActive) {
                    val line = reader.readLine() ?: break

                    when {
                        line.startsWith("event: ") -> eventType = line.removePrefix("event: ")
                        line.startsWith("data: ") -> data = line.removePrefix("data: ")
                        line.isEmpty() && data.isNotEmpty() -> {
                            if (eventType == "email") {
                                try {
                                    val email = json.decodeFromString<EmailSummaryDTO>(data)
                                    onEmail(email)
                                } catch (e: Exception) {
                                    // Skip malformed events
                                }
                            }
                            eventType = ""
                            data = ""
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
