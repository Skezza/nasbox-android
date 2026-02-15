package skezza.nasbox.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidKeystoreCredentialStore(
    context: Context,
) : CredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun savePassword(alias: String, password: String) = withContext(Dispatchers.IO) {
        val secretKey = getOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
        val encryptedBytes = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
        val payload = EncryptedPayload(
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
        )

        preferences.edit()
            .putString(payloadIvKey(alias), payload.iv)
            .putString(payloadCipherTextKey(alias), payload.ciphertext)
            .apply()
    }

    override suspend fun loadPassword(alias: String): String? = withContext(Dispatchers.IO) {
        val iv = preferences.getString(payloadIvKey(alias), null) ?: return@withContext null
        val ciphertext = preferences.getString(payloadCipherTextKey(alias), null) ?: return@withContext null

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: return@withContext null

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
        }

        val plainBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        String(plainBytes, StandardCharsets.UTF_8)
    }

    override suspend fun deletePassword(alias: String) = withContext(Dispatchers.IO) {
        preferences.edit()
            .remove(payloadIvKey(alias))
            .remove(payloadCipherTextKey(alias))
            .apply()

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private data class EncryptedPayload(
        val iv: String,
        val ciphertext: String,
    )

    private fun payloadIvKey(alias: String) = "$alias.iv"

    private fun payloadCipherTextKey(alias: String) = "$alias.cipher"

    private companion object {
        private const val PREFS_NAME = "credential_store"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
