package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.bmcreations.blip.cli.client.ApiClient
import kotlinx.coroutines.runBlocking

class WhoamiCommand : CliktCommand(name = "whoami") {
    override fun help(context: Context) = "Show current session info"

    override fun run() = runBlocking {
        val client = ApiClient()
        try {
            val session = client.getSession()
            echo("Session: ${session.id}")
            echo("Tier: ${session.tier}")
            echo("User: ${session.userId ?: "(anonymous)"}")
            echo("Expires: ${session.expiresAt}")
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        } finally {
            client.close()
        }
    }
}
