package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.bmcreations.blip.cli.client.ApiClient
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

class BillingCommand : CliktCommand(name = "billing") {
    override fun help(context: Context) = "Manage your Blip Pro subscription"

    override fun run() = runBlocking {
        val client = ApiClient()
        try {
            val session = client.getSession()
            if (session.userId == null) {
                echo("Not signed in. Run 'blip login' first.", err = true)
                return@runBlocking
            }

            echo("Opening billing portal...")
            val url = client.getBillingPortalUrl()

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                // Fallback for headless environments
                Runtime.getRuntime().exec(arrayOf("open", url))
            }
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        } finally {
            client.close()
        }
    }
}
