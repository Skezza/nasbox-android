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

enum class SmbBrowseLevel {
    SHARES,
    FOLDERS,
}

data class SmbBrowseEntry(
    val name: String,
    val path: String,
)

data class SmbBrowseRequest(
    val host: String,
    val shareName: String,
    val path: String,
    val username: String,
    val password: String,
)

data class SmbBrowseResult(
    val level: SmbBrowseLevel,
    val entries: List<SmbBrowseEntry>,
)

interface SmbClient {
    suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult
    suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult
}
