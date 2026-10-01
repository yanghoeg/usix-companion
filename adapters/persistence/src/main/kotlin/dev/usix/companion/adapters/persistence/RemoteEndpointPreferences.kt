package dev.usix.companion.adapters.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.usix.companion.application.RemoteEndpointStore
import dev.usix.companion.domain.RemoteEndpoint
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

interface SecretCipher { fun encrypt(value: String): String; fun decrypt(value: String): String }
class KeystoreSecretCipher(private val alias: String = "usix-companion-remote-v2") : SecretCipher {
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    override fun decrypt(value: String): String {
        val parts = value.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))) }
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
/** Small connection preferences use DataStore; only ciphertext is persisted for the bearer. */
class RemoteEndpointPreferences(context: Context, private val cipher: SecretCipher = KeystoreSecretCipher()) : RemoteEndpointStore {
    private val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        context.applicationContext.preferencesDataStoreFile("remote-v2")
    }
    override suspend fun load(): RemoteEndpoint? {
        val values = store.data.first()
        val url = values[URL] ?: return null
        return RemoteEndpoint(url, cipher.decrypt(values[TOKEN] ?: error("Remote credential missing")), values[CERT])
    }
    override suspend fun save(endpoint: RemoteEndpoint?) {
        val encrypted = endpoint?.let { cipher.encrypt(it.deviceBearer) }
        store.edit {
            it.clear()
            if (endpoint != null) {
                it[URL] = endpoint.url; it[TOKEN] = encrypted!!
                endpoint.trustedCertificatePem?.let { pem -> it[CERT] = pem }
            }
        }
    }
    companion object {
        private val URL = stringPreferencesKey("url")
        private val TOKEN = stringPreferencesKey("encryptedDeviceBearer")
        private val CERT = stringPreferencesKey("trustedCertificatePem")
    }
}
