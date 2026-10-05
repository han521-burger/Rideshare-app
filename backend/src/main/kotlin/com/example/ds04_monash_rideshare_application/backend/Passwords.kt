package com.example.ds04_monash_rideshare_application.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// Password hashing with the JDK's PBKDF2-HMAC-SHA256 (no extra dependency). Stored as
// "pbkdf2$<iterations>$<salt>$<hash>" so the iteration count can be raised later without
// breaking existing accounts.
private const val ITERATIONS = 210_000
private const val SALT_BYTES = 16
private const val KEY_BITS = 256
private val random = SecureRandom()

fun hashPassword(password: String): String {
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val hash = pbkdf2(password, salt, ITERATIONS)
    val encoder = Base64.getEncoder()
    return "pbkdf2\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
}

fun verifyPassword(password: String, stored: String): Boolean {
    val parts = stored.split('$')
    if (parts.size != 4 || parts[0] != "pbkdf2") return false
    val iterations = parts[1].toIntOrNull() ?: return false
    val decoder = Base64.getDecoder()
    val salt = decoder.decode(parts[2])
    val expected = decoder.decode(parts[3])
    // Constant-time compare so response timing doesn't leak how close a guess was.
    return MessageDigest.isEqual(pbkdf2(password, salt, iterations), expected)
}

private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

fun newSessionToken(): String {
    val bytes = ByteArray(32).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
