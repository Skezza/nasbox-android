package skezza.nasbox.data.smb

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

data class UploadFileResult(
    val remoteSizeBytes: Long,
    val checksumAlgorithm: String? = null,
    val checksumValue: String? = null,
    val checksumVerifiedAtEpochMs: Long? = null,
)

data class RemoteVerifyResult(
    val remoteSizeBytes: Long,
    val checksumAlgorithm: String,
    val checksumValue: String,
    val verifiedAtEpochMs: Long,
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
        verifyChecksum: Boolean = false,
        onProgressBytes: (Long) -> Unit = {},
    ): UploadFileResult {
        uploadFile(
            request = request,
            remotePath = remotePath,
            contentLengthBytes = contentLengthBytes,
            inputStream = inputStream,
            onProgressBytes = onProgressBytes,
        )
        return UploadFileResult(remoteSizeBytes = contentLengthBytes ?: -1L)
    }

    suspend fun uploadFile(
        request: SmbConnectionRequest,
        remotePath: String,
        contentLengthBytes: Long?,
        inputStream: InputStream,
        onProgressBytes: (Long) -> Unit = {},
    ) {
        throw UnsupportedOperationException("Upload is not implemented by this SMB client")
    }

    suspend fun verifyRemoteFile(
        request: SmbConnectionRequest,
        remotePath: String,
        expectedSizeBytes: Long,
        expectedChecksumAlgorithm: String,
        expectedChecksumValue: String,
    ): RemoteVerifyResult {
        throw UnsupportedOperationException("Remote verification is not implemented by this SMB client")
    }
}
