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

data class SmbBrowseRequest(
    val host: String,
    val shareName: String,
    val directoryPath: String,
    val username: String,
    val password: String,
)

data class SmbBrowseResult(
    val shareName: String,
    val directoryPath: String,
    val shares: List<String>,
    val directories: List<String>,
)

interface SmbClient {
    suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult

    suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult
}
