package dev.bmcreations.blip.server.services

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptionService {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_LENGTH_BITS = 256
    }

    private val secureRandom = SecureRandom()

    fun generateKey(): String {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(KEY_LENGTH_BITS, secureRandom)
        return Base64.getEncoder().encodeToString(keyGen.generateKey().encoded)
    }

    fun encrypt(plaintext: String, keyBase64: String): String {
        val encrypted = encryptBytes(plaintext.toByteArray(Charsets.UTF_8), keyBase64)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun decrypt(ciphertext: String, keyBase64: String): String {
        val decoded = Base64.getDecoder().decode(ciphertext)
        return String(decryptBytes(decoded, keyBase64), Charsets.UTF_8)
    }

    fun encryptBytes(data: ByteArray, keyBase64: String): ByteArray {
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(data)
        return iv + ciphertext
    }

    fun decryptBytes(data: ByteArray, keyBase64: String): ByteArray {
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val iv = data.copyOfRange(0, IV_LENGTH)
        val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
