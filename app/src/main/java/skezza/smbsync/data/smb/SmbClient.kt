package skezza.smbsync.data.smb

data class SmbConnectionRequest(
    val host: String,
    val shareName: String,
    val username: String,
    val password: String,
)

data class SmbConnectionResult(
    val latencyMs: Long,
)

interface SmbClient {
    suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult
}
