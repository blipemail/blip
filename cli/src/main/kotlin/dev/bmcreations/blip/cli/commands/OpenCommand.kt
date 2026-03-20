package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.bmcreations.blip.cli.config.ConfigManager
import java.awt.Desktop
import java.net.URI

class OpenCommand : CliktCommand(name = "open") {
    override fun help(context: Context) = "Open Blip in your browser with your current session"

    override fun run() {
        val token = ConfigManager.getToken()
        if (token == null) {
            echo("No session yet. Run 'blip create' first.", err = true)
            return
        }

        val frontendUrl = System.getenv("BLIP_FRONTEND_URL") ?: "https://app.useblip.email"
        val url = "$frontendUrl/app?token=$token"

        echo("Opening Blip in browser...")
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                Runtime.getRuntime().exec(arrayOf("open", url))
            }
        } catch (_: Exception) {
            echo("Could not open browser. Visit: $url")
        }
    }
}
