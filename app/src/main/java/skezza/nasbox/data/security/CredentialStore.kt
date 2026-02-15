package skezza.nasbox.data.security

interface CredentialStore {
    suspend fun savePassword(alias: String, password: String)
    suspend fun loadPassword(alias: String): String?
    suspend fun deletePassword(alias: String)
}
