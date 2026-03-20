package dev.bmcreations.blip.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.bmcreations.blip.cli.client.ApiClient
import dev.bmcreations.blip.cli.client.SseClient
import dev.bmcreations.blip.cli.config.ConfigManager
import dev.bmcreations.blip.models.EmailDetailDTO
import dev.bmcreations.blip.models.EmailSummaryDTO
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

class InboxCommand : CliktCommand(name = "inbox") {
    override fun help(context: Context) = "List inboxes or view a specific inbox"

    private val address by argument().optional()
    private val watch by option("--watch", "-w").flag()

    override fun run() = runBlocking {
        val client = ApiClient()
        try {
            if (address == null) {
                val inboxes = client.listInboxes()
                if (inboxes.isEmpty()) {
                    echo("No inboxes. Create one with: blip create")
                    return@runBlocking
                }
                for (inbox in inboxes) {
                    echo("${inbox.address}  (${inbox.emailCount} emails, expires ${inbox.expiresAt})")
                }
            } else {
                val inboxes = client.listInboxes()
                val inbox = inboxes.find { it.address == address }
                    ?: run {
                        echo("Inbox not found: $address", err = true)
                        return@runBlocking
                    }

                val detail = client.getInbox(inbox.id)
                echo("Inbox: ${detail.inbox.address}")

                // Tracked emails list — numbered for REPL selection
                val emails = mutableListOf<EmailSummaryDTO>()
                emails.addAll(detail.emails)

                if (emails.isNotEmpty()) {
                    echo("")
                    printEmailList(emails)
                }

                if (watch) {
                    echo("")
                    echo("Watching for new emails...")
                    echo("Commands: [number] read email, [number] reply, /list, /quit")
                    echo("")

                    // Start SSE listener
                    val sseClient = SseClient(client.getSseUrl(inbox.id)) { emailEvent ->
                        synchronized(emails) {
                            emails.add(emailEvent)
                            val idx = emails.size
                            echo("")
                            echo("  ● #$idx  ${emailEvent.from} — ${emailEvent.subject}")
                            echo("")
                            printPrompt()
                        }
                    }
                    sseClient.start(this)

                    // REPL loop on stdin
                    val reader = BufferedReader(InputStreamReader(System.`in`))
                    repl@ while (isActive) {
                        printPrompt()
                        val line = withContext(Dispatchers.IO) {
                            reader.readLine()
                        } ?: break

                        val input = line.trim()
                        if (input.isEmpty()) continue

                        when {
                            input == "/quit" || input == "/q" -> break@repl

                            input == "/list" || input == "/ls" -> {
                                if (emails.isEmpty()) {
                                    echo("  No emails yet.")
                                } else {
                                    echo("")
                                    printEmailList(emails)
                                }
                            }

                            input == "/help" || input == "/?" -> {
                                echo("  [number]         Read email #number")
                                echo("  [number] reply   Reply to email #number")
                                echo("  /list            List all emails")
                                echo("  /quit            Exit watch mode")
                            }

                            input.matches(Regex("^\\d+$")) -> {
                                val idx = input.toInt()
                                val email = getEmail(emails, idx)
                                if (email == null) {
                                    echo("  No email #$idx")
                                    continue@repl
                                }
                                readEmail(client, email)
                            }

                            input.matches(Regex("^\\d+\\s+reply$", RegexOption.IGNORE_CASE)) -> {
                                val idx = input.split("\\s+".toRegex()).first().toInt()
                                val email = getEmail(emails, idx)
                                if (email == null) {
                                    echo("  No email #$idx")
                                    continue@repl
                                }
                                replyToEmail(client, reader, email)
                            }

                            else -> {
                                echo("  Unknown command. Type /help for usage.")
                            }
                        }
                    }

                    sseClient.stop()
                }
            }
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        } finally {
            client.close()
        }
    }

    private fun printEmailList(emails: List<EmailSummaryDTO>) {
        for ((i, email) in emails.withIndex()) {
            echo("  #${i + 1}  ${email.from} — ${email.subject}")
        }
    }

    private fun getEmail(emails: List<EmailSummaryDTO>, idx: Int): EmailSummaryDTO? {
        return emails.getOrNull(idx - 1)
    }

    private suspend fun readEmail(client: ApiClient, summary: EmailSummaryDTO) {
        try {
            val email = client.getEmail(summary.id)
            echo("")
            echo("  From: ${email.from}")
            echo("  Subject: ${email.subject}")
            echo("  Date: ${email.receivedAt}")
            if (email.attachments.isNotEmpty()) {
                echo("  Attachments: ${email.attachments.joinToString { "${it.name} (${it.size / 1024}KB)" }}")
            }
            echo("  ${"─".repeat(56)}")
            val body = email.textBody
                ?: email.htmlBody?.replace(Regex("<[^>]+>"), "")?.trim()
                ?: "(empty)"
            for (line in body.lines()) {
                echo("  $line")
            }
            echo("")
        } catch (e: Exception) {
            echo("  Error reading email: ${e.message}", err = true)
        }
    }

    private suspend fun replyToEmail(client: ApiClient, reader: BufferedReader, summary: EmailSummaryDTO) {
        echo("")
        echo("  Replying to: ${summary.from} — ${summary.subject}")
        echo("  Type your reply. Enter a blank line to send, or /cancel to abort.")
        echo("")

        val lines = mutableListOf<String>()
        while (true) {
            print("  > ")
            System.out.flush()
            val line = withContext(Dispatchers.IO) {
                reader.readLine()
            } ?: break

            if (line.trim() == "/cancel") {
                echo("  Reply cancelled.")
                return
            }

            if (line.isEmpty() && lines.isNotEmpty()) {
                break
            }

            lines.add(line)
        }

        if (lines.isEmpty()) {
            echo("  Empty reply, cancelled.")
            return
        }

        val body = lines.joinToString("\n")
        echo("  Sending...")
        try {
            val result = client.replyToEmail(summary.id, body)
            echo("  Reply ${result.status}.")
        } catch (e: Exception) {
            val isSelfHosted = ConfigManager.getApiUrl() != "https://api.useblip.email"
            if (isSelfHosted) {
                echo("  Reply is not available on self-hosted servers. Use useblip.email for Pro features.", err = true)
            } else {
                echo("  Failed to send: ${e.message}", err = true)
            }
        }
        echo("")
    }

    private fun printPrompt() {
        print("blip> ")
        System.out.flush()
    }
}
