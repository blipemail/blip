package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.bmcreations.blip.cli.client.ApiClient
import dev.bmcreations.blip.models.CreateInboxRequest
import kotlinx.coroutines.runBlocking

class CreateCommand : CliktCommand(name = "create") {
    override fun help(context: Context) = "Create a new disposable inbox"

    private val slug by option("--slug", "-s", help = "Custom address slug (PRO only)")
    private val domain by option("--domain", "-d", help = "Domain to use")
    private val sniperWindow by option("--sniper", help = "Sniper window in minutes (PRO only)").int()

    override fun run() = runBlocking {
        val client = ApiClient()
        try {
            val request = CreateInboxRequest(
                slug = slug,
                domain = domain,
                windowMinutes = sniperWindow,
            )
            val inbox = client.createInbox(request)
            echo("Inbox created: ${inbox.address}")
            echo("  ID: ${inbox.id}")
            echo("  Expires: ${inbox.expiresAt}")
            if (inbox.sniperWindow != null) {
                echo("  Sniper window: ${inbox.sniperWindow!!.opensAt} - ${inbox.sniperWindow!!.closesAt}")
            }
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        } finally {
            client.close()
        }
    }
}
