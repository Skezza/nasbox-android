package skezza.smbsync.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KeystoreCredentialStore(
    context: Context,
) : CredentialStore {
    private val appContext = context.applicationContext
    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val securePrefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun saveSecret(alias: String, secret: String) {
        withContext(Dispatchers.IO) {
            securePrefs
                .edit()
                .putString(alias, secret)
                .apply()
        }
    }

    override suspend fun loadSecret(alias: String): String? {
        return withContext(Dispatchers.IO) {
            securePrefs.getString(alias, null)
        }
    }

    override suspend fun deleteSecret(alias: String) {
        withContext(Dispatchers.IO) {
            securePrefs
                .edit()
                .remove(alias)
                .apply()
        }
    }

    private companion object {
        const val PREFS_FILE = "smbsync-secure-credentials"
    }
}
