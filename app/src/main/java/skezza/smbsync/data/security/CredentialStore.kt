package skezza.smbsync.data.security

interface CredentialStore {
    suspend fun saveSecret(alias: String, secret: String)
    suspend fun loadSecret(alias: String): String?
    suspend fun deleteSecret(alias: String)
}
