package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.bmcreations.blip.cli.client.ApiClient
import kotlinx.coroutines.runBlocking

class ReadCommand : CliktCommand(name = "read") {
    override fun help(context: Context) = "Read a specific email"

    private val emailId by argument()

    override fun run() = runBlocking {
        val client = ApiClient()
        try {
            val email = client.getEmail(emailId)

            echo("Subject: ${email.subject}")
            echo("From: ${email.from}")
            echo("To: ${email.to}")
            echo("Date: ${email.receivedAt}")

            if (email.attachments.isNotEmpty()) {
                echo("Attachments: ${email.attachments.joinToString { "${it.name} (${it.size / 1024}KB)" }}")
            }

            echo("─".repeat(60))
            echo(email.textBody ?: email.htmlBody?.replace(Regex("<[^>]+>"), "") ?: "(empty)")
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        } finally {
            client.close()
        }
    }
}
