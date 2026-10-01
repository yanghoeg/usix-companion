package dev.usix.companion.adapters.transport

import dev.usix.companion.application.CredentialCrypto
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

class ExecutionCrypto : CredentialCrypto {
    override fun newSecret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(Locale.US, it) }
    override fun digest(secret: String): String = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.US, it) }
    override fun matches(secret: String, digest: String) = MessageDigest.isEqual(this.digest(secret).toByteArray(Charsets.US_ASCII), digest.toByteArray(Charsets.US_ASCII))
}
