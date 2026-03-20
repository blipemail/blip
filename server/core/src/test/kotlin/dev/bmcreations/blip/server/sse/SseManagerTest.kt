package dev.bmcreations.blip.server.sse

import dev.bmcreations.blip.models.EmailSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SseManagerTest {

    private lateinit var sseManager: SseManager

    @BeforeTest
    fun setup() {
        sseManager = SseManager()
    }

    private fun email(id: String = "e1", subject: String = "Test") = EmailSummary(
        id = id, from = "a@b.com", subject = subject,
        receivedAt = "2026-01-01T00:00:00Z", preview = "preview"
    )

    @Test
    fun `subscribe receives published emails`() = runTest {
        val received = mutableListOf<EmailSummary>()
        val job = launch(UnconfinedTestDispatcher()) {
            sseManager.subscribe("inbox-1").take(2).toList(received)
        }
        yield()
        sseManager.publish("inbox-1", email("e1", "First"))
        sseManager.publish("inbox-1", email("e2", "Second"))
        job.join()
        assertEquals(2, received.size)
        assertEquals("First", received[0].subject)
        assertEquals("Second", received[1].subject)
    }

    @Test
    fun `publish to one inbox does not leak to another`() = runTest {
        val received = mutableListOf<EmailSummary>()
        val job = launch(UnconfinedTestDispatcher()) {
            sseManager.subscribe("inbox-A").take(1).toList(received)
        }
        yield()
        sseManager.publish("inbox-B", email("e1"))
        sseManager.publish("inbox-A", email("e2"))
        job.join()
        assertEquals(1, received.size)
        assertEquals("e2", received[0].id)
    }

    @Test
    fun `removeFlow cleans up`() = runTest {
        // Subscribe first to create the flow
        val job = launch(UnconfinedTestDispatcher()) {
            sseManager.subscribe("inbox-1").first()
        }
        yield()
        sseManager.removeFlow("inbox-1")
        // Publishing after removal should not error
        sseManager.publish("inbox-1", email())
        job.cancelAndJoin()
    }

    @Test
    fun `multiple subscribers on same inbox all receive`() = runTest {
        val received1 = mutableListOf<EmailSummary>()
        val received2 = mutableListOf<EmailSummary>()
        val job1 = launch(UnconfinedTestDispatcher()) {
            sseManager.subscribe("inbox-1").take(1).toList(received1)
        }
        val job2 = launch(UnconfinedTestDispatcher()) {
            sseManager.subscribe("inbox-1").take(1).toList(received2)
        }
        yield()
        sseManager.publish("inbox-1", email("e1"))
        joinAll(job1, job2)
        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
    }
}
