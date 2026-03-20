package dev.bmcreations.blip.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import dev.bmcreations.blip.cli.commands.*

class Blip : CliktCommand(name = "blip") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Instant disposable email inboxes"
    override fun run() = Unit
}

fun main(args: Array<String>) {
    Blip().subcommands(
        LoginCommand(),
        LogoutCommand(),
        CreateCommand(),
        InboxCommand(),
        ReadCommand(),
        OpenCommand(),
        BillingCommand(),
        WhoamiCommand(),
        DeleteAccountCommand(),
    ).main(args)
}
