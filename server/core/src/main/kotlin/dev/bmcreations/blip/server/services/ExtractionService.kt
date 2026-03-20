package dev.bmcreations.blip.server.services

import dev.bmcreations.blip.models.ExtractedCode
import dev.bmcreations.blip.models.ExtractedLink
import dev.bmcreations.blip.models.ExtractionResult

class ExtractionService {

    fun extract(textBody: String?, htmlBody: String?): ExtractionResult {
        val codes = mutableListOf<ExtractedCode>()
        val links = mutableListOf<ExtractedLink>()

        // Extract from both text and HTML bodies
        val bodies = listOfNotNull(textBody, htmlBody?.stripHtmlTags())

        for (body in bodies) {
            codes.addAll(extractCodes(body))
        }

        // Extract links from HTML first (preserves href), fall back to text
        if (htmlBody != null) {
            links.addAll(extractLinksFromHtml(htmlBody))
        }
        if (textBody != null) {
            links.addAll(extractLinksFromText(textBody))
        }

        // Deduplicate
        return ExtractionResult(
            codes = codes.distinctBy { it.value },
            links = links.distinctBy { it.url },
        )
    }

    private fun extractCodes(text: String): List<ExtractedCode> {
        val codes = mutableListOf<ExtractedCode>()
        val codeKeywords = listOf(
            "code", "otp", "verification", "verify", "pin", "confirm",
            "confirmation", "token", "one-time", "one time", "passcode",
            "pass code", "security code", "access code",
        )

        // Split into lines/sentences for context-aware matching
        val lines = text.split("\n", ". ", "! ", "? ")

        for (line in lines) {
            val lower = line.lowercase()
            val hasKeyword = codeKeywords.any { it in lower }
            if (!hasKeyword) continue

            // Match 4-8 digit numeric codes
            val numericPattern = Regex("\\b(\\d{4,8})\\b")
            for (match in numericPattern.findAll(line)) {
                val value = match.value
                if (isLikelyYear(value)) continue
                if (isCommonNumber(value)) continue

                val type = when {
                    "otp" in lower || "one-time" in lower || "one time" in lower -> "otp"
                    "pin" in lower -> "pin"
                    else -> "code"
                }

                codes.add(ExtractedCode(
                    value = value,
                    type = type,
                    context = line.trim().take(200),
                ))
            }

            // Match 6-8 char alphanumeric codes (like ABC123)
            val alphanumericPattern = Regex("\\b([A-Z0-9]{6,8})\\b")
            for (match in alphanumericPattern.findAll(line)) {
                val value = match.value
                // Skip if it's all letters (likely a word) or all digits (handled above)
                if (value.all { it.isLetter() }) continue
                if (value.all { it.isDigit() }) continue

                codes.add(ExtractedCode(
                    value = value,
                    type = "code",
                    context = line.trim().take(200),
                ))
            }
        }

        return codes
    }

    private fun extractLinksFromHtml(html: String): List<ExtractedLink> {
        val links = mutableListOf<ExtractedLink>()
        val anchorPattern = Regex("""<a\s[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)

        for (match in anchorPattern.findAll(html)) {
            val url = match.groupValues[1].decodeHtmlEntities()
            val anchorText = match.groupValues[2].stripHtmlTags().trim()
            val type = classifyLink(url, anchorText)
            if (type != null) {
                links.add(ExtractedLink(url = url, type = type, text = anchorText.take(200)))
            }
        }

        return links
    }

    private fun extractLinksFromText(text: String): List<ExtractedLink> {
        val links = mutableListOf<ExtractedLink>()
        val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

        for (match in urlPattern.findAll(text)) {
            val url = match.value.trimEnd('.', ',', ')', ';')
            val type = classifyLink(url, "")
            if (type != null) {
                // Get surrounding context
                val start = maxOf(0, match.range.first - 50)
                val end = minOf(text.length, match.range.last + 50)
                val context = text.substring(start, end).trim()
                links.add(ExtractedLink(url = url, type = type, text = context.take(200)))
            }
        }

        return links
    }

    private fun classifyLink(url: String, anchorText: String): String? {
        val lower = url.lowercase()
        val textLower = anchorText.lowercase()

        val verifyPatterns = listOf("/verify", "/confirm", "/activate", "/validate", "/auth/callback")
        val resetPatterns = listOf("/reset", "/password")
        val magicPatterns = listOf("/magic", "/login/email", "/signin/email", "/auth/link")
        val queryPatterns = listOf("token=", "code=", "verify=", "confirmation_token=", "otp=")

        // Check more specific patterns first (reset, magic link) before generic verification
        return when {
            resetPatterns.any { it in lower } ||
                textLower.containsAny("reset", "password") -> "reset"
            magicPatterns.any { it in lower } ||
                textLower.containsAny("magic link", "sign in", "log in") -> "magic_link"
            verifyPatterns.any { it in lower } || queryPatterns.any { it in lower } ||
                textLower.containsAny("verify", "confirm", "activate", "validate") -> "verification"
            else -> null
        }
    }

    private fun isLikelyYear(value: String): Boolean {
        if (value.length != 4) return false
        val num = value.toIntOrNull() ?: return false
        return num in 1900..2099
    }

    private fun isCommonNumber(value: String): Boolean {
        val num = value.toLongOrNull() ?: return false
        return num == 0L || num == 1L || num == 100L || num == 1000L
    }

    private fun String.stripHtmlTags(): String = replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun String.decodeHtmlEntities(): String = replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { this.contains(it) }
}
