package skezza.smbsync.domain.smb

import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.toSmbConnectionFailure

class BrowseSmbPathUseCase(
    private val smbClient: SmbClient,
) {
    suspend operator fun invoke(
        host: String,
        shareName: String,
        directoryPath: String,
        username: String,
        password: String,
    ): SmbBrowseUiResult {
        val parsedTarget = SmbTargetParser.parse(host, shareName)
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbBrowseUiResult(
                success = false,
                message = parsedTarget.message,
                recoveryHint = "Use host like quanta.local (or smb://quanta.local/share) and retry.",
            )
        }

        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        return runCatching {
            smbClient.browse(
                SmbBrowseRequest(
                    host = target.host,
                    shareName = target.shareName,
                    directoryPath = directoryPath,
                    username = username.trim(),
                    password = password,
                ),
            )
        }.fold(
            onSuccess = {
                SmbBrowseUiResult(
                    success = true,
                    shareName = it.shareName,
                    currentPath = it.directoryPath,
                    shares = it.shares,
                    directories = it.directories,
                )
            },
            onFailure = { throwable ->
                val mapped = throwable.toSmbConnectionFailure().toUiError()
                SmbBrowseUiResult(
                    success = false,
                    message = mapped.message,
                    recoveryHint = mapped.recoveryHint,
                    technicalDetail = throwable.message,
                )
            },
        )
    }
}

data class SmbBrowseUiResult(
    val success: Boolean,
    val shareName: String = "",
    val currentPath: String = "",
    val shares: List<String> = emptyList(),
    val directories: List<String> = emptyList(),
    val message: String? = null,
    val recoveryHint: String? = null,
    val technicalDetail: String? = null,
)
