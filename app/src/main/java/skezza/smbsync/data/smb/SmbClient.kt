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

data class SmbShareEntry(
    val name: String,
)

data class SmbDirectoryEntry(
    val name: String,
)

data class SmbBrowseRequest(
    val host: String,
    val shareName: String,
    val username: String,
    val password: String,
    val folderPath: String,
)

interface SmbClient {
    suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult
    suspend fun listShares(request: SmbConnectionRequest): List<SmbShareEntry>
    suspend fun listDirectories(request: SmbBrowseRequest): List<SmbDirectoryEntry>
}
