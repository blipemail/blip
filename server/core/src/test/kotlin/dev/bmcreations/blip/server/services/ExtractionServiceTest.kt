package dev.bmcreations.blip.server.services

import kotlin.test.*

class ExtractionServiceTest {

    private val service = ExtractionService()

    // --- Code extraction ---

    @Test
    fun `extracts 6-digit OTP code from text`() {
        val text = "Your verification code is 482931. Enter this to complete signup."
        val result = service.extract(text, null)

        assertEquals(1, result.codes.size)
        assertEquals("482931", result.codes[0].value)
        assertEquals("code", result.codes[0].type)
    }

    @Test
    fun `extracts OTP code with otp keyword`() {
        val text = "Your OTP is 123456"
        val result = service.extract(text, null)

        assertEquals(1, result.codes.size)
        assertEquals("123456", result.codes[0].value)
        assertEquals("otp", result.codes[0].type)
    }

    @Test
    fun `extracts PIN code`() {
        val text = "Your PIN code is 8472"
        val result = service.extract(text, null)

        assertEquals(1, result.codes.size)
        assertEquals("8472", result.codes[0].value)
        assertEquals("pin", result.codes[0].type)
    }

    @Test
    fun `filters out years`() {
        val text = "Your verification code was sent in 2024. Please check your email."
        val result = service.extract(text, null)

        assertTrue(result.codes.isEmpty())
    }

    @Test
    fun `extracts alphanumeric codes`() {
        val text = "Your confirmation code is AB3F7K"
        val result = service.extract(text, null)

        assertEquals(1, result.codes.size)
        assertEquals("AB3F7K", result.codes[0].value)
    }

    @Test
    fun `does not extract codes without keyword context`() {
        val text = "The total is 123456 items in stock."
        val result = service.extract(text, null)

        assertTrue(result.codes.isEmpty())
    }

    @Test
    fun `extracts 4-digit code`() {
        val text = "Your one-time code is 7291"
        val result = service.extract(text, null)

        assertEquals(1, result.codes.size)
        assertEquals("7291", result.codes[0].value)
        assertEquals("otp", result.codes[0].type)
    }

    @Test
    fun `extracts 8-digit code`() {
        val text = "Your verification code: 12345678"
        val result = service.extract(text, null)

        assertEquals(1, result.codes.size)
        assertEquals("12345678", result.codes[0].value)
    }

    // --- Link extraction ---

    @Test
    fun `extracts verification link from HTML`() {
        val html = """<a href="https://example.com/verify?token=abc123">Verify your email</a>"""
        val result = service.extract(null, html)

        assertEquals(1, result.links.size)
        assertEquals("https://example.com/verify?token=abc123", result.links[0].url)
        assertEquals("verification", result.links[0].type)
    }

    @Test
    fun `extracts confirmation link from text`() {
        val text = "Click here to confirm: https://example.com/confirm/abc123"
        val result = service.extract(text, null)

        assertEquals(1, result.links.size)
        assertEquals("https://example.com/confirm/abc123", result.links[0].url)
        assertEquals("verification", result.links[0].type)
    }

    @Test
    fun `extracts reset link`() {
        val html = """<a href="https://example.com/reset/password?token=xyz">Reset your password</a>"""
        val result = service.extract(null, html)

        assertEquals(1, result.links.size)
        assertEquals("reset", result.links[0].type)
    }

    @Test
    fun `extracts magic link`() {
        val html = """<a href="https://example.com/auth/link?token=magic123">Sign in</a>"""
        val result = service.extract(null, html)

        assertEquals(1, result.links.size)
        assertEquals("magic_link", result.links[0].type)
    }

    @Test
    fun `does not extract unrelated links`() {
        val html = """<a href="https://example.com/about">About us</a>"""
        val result = service.extract(null, html)

        assertTrue(result.links.isEmpty())
    }

    @Test
    fun `extracts link with code query param`() {
        val text = "Visit https://example.com/signup?code=ABCDEF to finish."
        val result = service.extract(text, null)

        assertEquals(1, result.links.size)
        assertEquals("verification", result.links[0].type)
    }

    // --- Combined extraction ---

    @Test
    fun `extracts both codes and links from same email`() {
        val text = "Your code is 482931. Or click https://example.com/verify?token=abc"
        val result = service.extract(text, null)

        assertEquals(1, result.codes.size)
        assertEquals("482931", result.codes[0].value)
        assertEquals(1, result.links.size)
        assertEquals("verification", result.links[0].type)
    }

    @Test
    fun `deduplicates across text and HTML`() {
        val text = "Your verification code is 482931"
        val html = "<p>Your verification code is 482931</p>"
        val result = service.extract(text, html)

        assertEquals(1, result.codes.size)
    }

    @Test
    fun `handles null bodies gracefully`() {
        val result = service.extract(null, null)

        assertTrue(result.codes.isEmpty())
        assertTrue(result.links.isEmpty())
    }

    @Test
    fun `handles empty bodies`() {
        val result = service.extract("", "")

        assertTrue(result.codes.isEmpty())
        assertTrue(result.links.isEmpty())
    }

    @Test
    fun `decodes HTML entities in links`() {
        val html = """<a href="https://example.com/verify?a=1&amp;token=abc">Verify</a>"""
        val result = service.extract(null, html)

        assertEquals(1, result.links.size)
        assertEquals("https://example.com/verify?a=1&token=abc", result.links[0].url)
    }
}
