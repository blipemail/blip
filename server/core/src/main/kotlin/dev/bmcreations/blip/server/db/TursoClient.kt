package dev.bmcreations.blip.server.db

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class TursoClient(
    private val baseUrl: String,
    private val authToken: String,
    private val json: Json,
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    private val pipelineUrl = "$baseUrl/v2/pipeline"

    suspend fun execute(sql: String, args: List<TursoValue> = emptyList()): TursoResult {
        val results = executeBatch(listOf(Statement(sql, args)))
        return results.first()
    }

    suspend fun executeBatch(statements: List<Statement>): List<TursoResult> {
        val requests = statements.mapIndexed { index, stmt ->
            buildJsonObject {
                put("type", "execute")
                putJsonObject("stmt") {
                    put("sql", stmt.sql)
                    if (stmt.args.isNotEmpty()) {
                        putJsonArray("args") {
                            stmt.args.forEach { arg ->
                                add(arg.toJson())
                            }
                        }
                    }
                }
            }
        } + buildJsonObject {
            put("type", "close")
        }

        val body = buildJsonObject {
            put("baton", JsonNull)
            putJsonArray("requests") {
                requests.forEach { add(it) }
            }
        }

        val response = client.post(pipelineUrl) {
            contentType(ContentType.Application.Json)
            if (authToken.isNotBlank()) {
                header("Authorization", "Bearer $authToken")
            }
            setBody(body.toString())
        }

        if (response.status != HttpStatusCode.OK) {
            val text = response.bodyAsText()
            throw RuntimeException("Turso error ${response.status}: $text")
        }

        val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val results = responseJson["results"]?.jsonArray ?: return emptyList()

        return results.mapNotNull { result ->
            val obj = result.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "ok") {
                val resp = obj["response"]?.jsonObject
                val resType = resp?.get("type")?.jsonPrimitive?.contentOrNull
                if (resType == "execute") {
                    val execResult = resp["result"]?.jsonObject
                    parseResult(execResult)
                } else null
            } else if (type == "error") {
                val error = obj["error"]?.jsonObject
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                throw RuntimeException("Turso query error: $message")
            } else null
        }
    }

    private fun parseResult(result: JsonObject?): TursoResult {
        if (result == null) return TursoResult(emptyList(), emptyList(), 0, 0)

        val cols = result["cols"]?.jsonArray?.map { col ->
            col.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
        } ?: emptyList()

        val rows = result["rows"]?.jsonArray?.map { row ->
            row.jsonArray.map { cell ->
                when {
                    cell is JsonNull -> null
                    cell.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "integer" ->
                        cell.jsonObject["value"]?.jsonPrimitive?.contentOrNull
                    cell.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "float" ->
                        cell.jsonObject["value"]?.jsonPrimitive?.contentOrNull
                    cell.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" ->
                        cell.jsonObject["value"]?.jsonPrimitive?.contentOrNull
                    cell.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "blob" ->
                        cell.jsonObject["base64"]?.jsonPrimitive?.contentOrNull
                    cell.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "null" -> null
                    else -> cell.jsonObject["value"]?.jsonPrimitive?.contentOrNull
                }
            }
        } ?: emptyList()

        val affectedRowCount = result["affected_row_count"]?.jsonPrimitive?.longOrNull ?: 0
        val lastInsertRowid = result["last_insert_rowid"]?.jsonPrimitive?.longOrNull ?: 0

        return TursoResult(cols, rows, affectedRowCount, lastInsertRowid)
    }
}

data class Statement(val sql: String, val args: List<TursoValue> = emptyList())

data class TursoResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val affectedRowCount: Long,
    val lastInsertRowid: Long,
) {
    fun firstOrNull(): Map<String, String?>? {
        if (rows.isEmpty()) return null
        return columns.zip(rows.first()).toMap()
    }

    fun toMaps(): List<Map<String, String?>> {
        return rows.map { row -> columns.zip(row).toMap() }
    }
}

sealed class TursoValue {
    data class Text(val value: String) : TursoValue()
    data class Integer(val value: Long) : TursoValue()
    data class Blob(val base64: String) : TursoValue()
    data object Null : TursoValue()

    fun toJson(): JsonObject = when (this) {
        is Text -> buildJsonObject {
            put("type", "text")
            put("value", value)
        }
        is Integer -> buildJsonObject {
            put("type", "integer")
            put("value", value.toString())
        }
        is Blob -> buildJsonObject {
            put("type", "blob")
            put("base64", base64)
        }
        is Null -> buildJsonObject {
            put("type", "null")
        }
    }
}
