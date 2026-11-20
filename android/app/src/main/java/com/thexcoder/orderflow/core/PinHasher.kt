package com.thexcoder.orderflow.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A salted password verifier only. The clear-text PIN is never persisted. */
object PinHasher {
    private const val ITERATIONS = 210_000
    private const val PREFIX = "pbkdf2-sha256"

    fun create(pin: String): String {
        require(pin.matches(Regex("[0-9]{6,12}"))) { "Choose a PIN containing 6–12 digits." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        val encoder = Base64.getEncoder()
        return "$PREFIX\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    fun verify(pin: String, encoded: String): Boolean {
        if (!pin.matches(Regex("[0-9]{6,12}")) || encoded.length > 256) return false
        return runCatching {
            val fields = encoded.split('$')
            require(fields.size == 4 && fields[0] == PREFIX)
            val iterations = fields[1].toInt()
            require(iterations in 100_000..1_000_000)
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(fields[2])
            val expected = decoder.decode(fields[3])
            require(salt.size == 16 && expected.size == 32)
            MessageDigest.isEqual(expected, derive(pin, salt, iterations))
        }.getOrDefault(false)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
