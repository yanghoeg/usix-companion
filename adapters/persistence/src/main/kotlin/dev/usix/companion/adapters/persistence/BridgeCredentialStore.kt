package dev.usix.companion.adapters.persistence

import android.content.Context
import dev.usix.companion.application.CredentialProver
import dev.usix.companion.application.CredentialVerifier
import dev.usix.companion.application.PairingCredentials
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Preserve the installed v1 preferences/key. Keystore/pairing migration is P2 work. */
class BridgeCredentialStore(context: Context) : CredentialVerifier, CredentialProver, PairingCredentials {
    private val preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    @Volatile private var token = preferences.getString("token", null)?.takeIf { it.isNotEmpty() }
        ?: generate().also { preferences.edit().putString("token", it).apply() }

    override fun current() = token

    @Synchronized
    override fun regenerate(): String = generate().also {
        preferences.edit().putString("token", it).apply()
        token = it
    }

    override fun verify(bearer: String?): Boolean = bearer != null && MessageDigest.isEqual(
        token.toByteArray(Charsets.UTF_8), bearer.toByteArray(Charsets.UTF_8))

    override fun sign(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(message)
    }

    private fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
