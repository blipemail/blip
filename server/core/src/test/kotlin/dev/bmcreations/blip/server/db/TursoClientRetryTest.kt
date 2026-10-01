package dev.bmcreations.blip.server.db

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TursoClientRetryTest {

    private val okBody =
        """{"results":[{"type":"ok","response":{"type":"execute","result":{"cols":[{"name":"x"}],"rows":[[{"type":"integer","value":"1"}]],"affected_row_count":0,"last_insert_rowid":null}}}]}"""

    private fun server(statuses: List<Int>, hits: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v2/pipeline") { ex ->
                val status = statuses[minOf(hits.getAndIncrement(), statuses.size - 1)]
                val bytes = (if (status == 200) okBody else "err").toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun client(s: HttpServer) =
        TursoClient("http://127.0.0.1:${s.address.port}", "", Json { ignoreUnknownKeys = true })

    @Test
    fun `retries 5xx and 429 then succeeds`() = runTest {
        val hits = AtomicInteger()
        val s = server(listOf(503, 429, 200), hits)
        try {
            assertEquals("1", client(s).execute("SELECT 1").firstOrNull()?.get("x"))
            assertEquals(3, hits.get())
        } finally { s.stop(0) }
    }

    @Test
    fun `does not retry 4xx`() = runTest {
        val hits = AtomicInteger()
        val s = server(listOf(400), hits)
        try {
            assertFailsWith<RuntimeException> { client(s).execute("SELECT 1") }
            assertEquals(1, hits.get())
        } finally { s.stop(0) }
    }

    @Test
    fun `gives up after three attempts on persistent 5xx`() = runTest {
        val hits = AtomicInteger()
        val s = server(listOf(500), hits)
        try {
            assertFailsWith<RuntimeException> { client(s).execute("SELECT 1") }
            assertEquals(3, hits.get())
        } finally { s.stop(0) }
    }
}
