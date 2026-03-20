package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.bmcreations.blip.cli.config.ConfigManager

class LogoutCommand : CliktCommand(name = "logout") {
    override fun help(context: Context) = "Sign out of your Blip account"

    override fun run() {
        ConfigManager.clearToken()
        echo("Signed out")
    }
}
