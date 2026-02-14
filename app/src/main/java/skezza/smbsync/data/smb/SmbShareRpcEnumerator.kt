package skezza.smbsync.data.smb

interface SmbShareRpcEnumerator {
    suspend fun listSharesViaRpc(host: String, username: String, password: String, domain: String): List<String>
}
