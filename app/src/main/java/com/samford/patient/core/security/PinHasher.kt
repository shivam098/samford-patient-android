package com.samford.patient.core.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional app-lock PIN. Only a salted PBKDF2-HMAC-SHA256 hash is stored. This is a convenience lock
 * for a shared family phone, not a substitute for the phone's own screen lock.
 */
object PinHasher {
    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun matches(pin: String, salt: ByteArray, expected: ByteArray, iterations: Int = ITERATIONS): Boolean {
        val actual = hash(pin, salt, iterations)
        if (actual.size != expected.size) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].toInt() xor expected[i].toInt())
        return diff == 0
    }
}
