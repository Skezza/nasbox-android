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
    val path: String,
    val username: String,
    val password: String,
)

data class SmbBrowseEntry(
    val name: String,
    val isDirectory: Boolean,
)

data class SmbBrowseResult(
    val currentPath: String,
    val shares: List<String> = emptyList(),
    val entries: List<SmbBrowseEntry> = emptyList(),
)

interface SmbClient {
    suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult
    suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult
}
