package skezza.smbsync.data.smb

import java.io.InputStream

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

    suspend fun listShares(
        host: String,
        username: String,
        password: String,
    ): List<String>

    suspend fun listDirectories(
        host: String,
        shareName: String,
        path: String,
        username: String,
        password: String,
    ): List<String>

    suspend fun uploadFile(
        request: SmbConnectionRequest,
        remotePath: String,
        contentLengthBytes: Long?,
        inputStream: InputStream,
        onProgressBytes: (Long) -> Unit = {},
    ) {
        throw UnsupportedOperationException("Upload is not implemented by this SMB client")
    }
}
