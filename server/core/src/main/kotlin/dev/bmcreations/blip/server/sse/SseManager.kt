package dev.bmcreations.blip.server.sse

import dev.bmcreations.blip.models.EmailSummary
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

class SseManager {
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<EmailSummary>>()

    fun subscribe(inboxId: String): Flow<EmailSummary> {
        return getOrCreateFlow(inboxId).asSharedFlow()
    }

    /** Never suspends: a slow subscriber loses its oldest events instead of blocking ingest. */
    fun publish(inboxId: String, email: EmailSummary) {
        getOrCreateFlow(inboxId).tryEmit(email)
    }

    private fun getOrCreateFlow(inboxId: String): MutableSharedFlow<EmailSummary> {
        return flows.getOrPut(inboxId) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    }

    fun removeFlow(inboxId: String) {
        flows.remove(inboxId)
    }

    fun pruneStaleFlows(activeInboxIds: Set<String>) {
        flows.keys.removeAll { it !in activeInboxIds }
    }
}
