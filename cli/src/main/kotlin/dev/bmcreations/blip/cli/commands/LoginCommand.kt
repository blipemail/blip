package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.bmcreations.blip.cli.client.ApiClient
import dev.bmcreations.blip.cli.config.ConfigManager
import dev.bmcreations.blip.models.DeviceCodeStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

class LoginCommand : CliktCommand(name = "login") {
    override fun help(context: Context) = "Sign in to your Blip account"

    override fun run() = runBlocking {
        val client = ApiClient()
        try {
            // Check if already signed in
            val existingToken = ConfigManager.getToken()
            if (existingToken != null) {
                try {
                    val session = client.getSession()
                    if (session.userId != null) {
                        echo("Already signed in (${session.tier} tier)")
                        echo("Use 'blip logout' first to switch accounts")
                        return@runBlocking
                    }
                } catch (_: Exception) { }
            }

            // Start device code flow
            val deviceCode = client.createDeviceCode()

            echo("Your login code: ${deviceCode.userCode}")
            echo("")
            echo("Opening browser to sign in...")
            echo("If it doesn't open, visit: ${deviceCode.verificationUrl}")

            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI(deviceCode.verificationUrl))
                } else {
                    Runtime.getRuntime().exec(arrayOf("open", deviceCode.verificationUrl))
                }
            } catch (_: Exception) {
                // Browser open failed, user can use the URL manually
            }

            echo("")
            echo("Waiting for confirmation...")

            val intervalMs = (deviceCode.interval * 1000).toLong()
            val deadline = System.currentTimeMillis() + (deviceCode.expiresIn * 1000L)

            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)

                val poll = client.pollDeviceCode(deviceCode.deviceCode)
                when (poll.status) {
                    DeviceCodeStatus.CONFIRMED -> {
                        ConfigManager.saveToken(poll.token!!)
                        echo("Signed in! (${poll.session!!.tier} tier)")
                        return@runBlocking
                    }
                    DeviceCodeStatus.EXPIRED -> {
                        echo("Login expired. Run 'blip login' to try again.", err = true)
                        return@runBlocking
                    }
                    DeviceCodeStatus.PENDING -> continue
                }
            }

            echo("Login timed out. Run 'blip login' to try again.", err = true)
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        } finally {
            client.close()
        }
    }
}
