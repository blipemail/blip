package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.bmcreations.blip.cli.client.ApiClient
import dev.bmcreations.blip.cli.config.ConfigManager
import kotlinx.coroutines.runBlocking

class DeleteAccountCommand : CliktCommand(name = "delete-account") {
    override fun help(context: Context) = "Permanently delete your Blip account and all data"

    override fun run() = runBlocking {
        val api = ApiClient()
        val session = try {
            api.getSession()
        } catch (_: Exception) {
            echo("Not logged in. Use 'blip login' first.", err = true)
            api.close()
            return@runBlocking
        }

        if (session.userId == null) {
            val isSelfHosted = ConfigManager.getApiUrl() != "https://api.useblip.email"
            if (isSelfHosted) {
                echo("Account deletion requires authentication. On self-hosted servers, delete user data directly from your database.", err = true)
            } else {
                echo("Not logged in. Use 'blip login' first.", err = true)
            }
            return@runBlocking
        }

        echo("This will permanently delete your account including:")
        echo("  - All inboxes and emails")
        echo("  - All webhooks and forwarding rules")
        echo("  - All API keys")
        echo("  - Your billing subscription (if any)")
        echo()
        echo("This action cannot be undone.")
        echo()

        print("Type 'DELETE' to confirm: ")
        val confirmation = readlnOrNull()?.trim()
        if (confirmation != "DELETE") {
            echo("Aborted.")
            return@runBlocking
        }

        try {
            api.deleteAccount()
            ConfigManager.clearToken()
            echo("Account deleted. All your data has been permanently removed.")
        } catch (e: Exception) {
            echo("Failed to delete account: ${e.message}", err = true)
        } finally {
            api.close()
        }
    }
}
