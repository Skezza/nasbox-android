package skezza.smbsync.domain.smb

import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionFailure
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
                val mapped = throwable.toSmbConnectionFailure().toBrowseUiError()
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

private data class BrowseMappedSmbError(
    val message: String,
    val recoveryHint: String,
)

private fun SmbConnectionFailure.toBrowseUiError(): BrowseMappedSmbError = when (this) {
    is SmbConnectionFailure.HostUnreachable -> BrowseMappedSmbError(
        message = "Unable to reach the host.",
        recoveryHint = "Check host name/IP and network connectivity.",
    )

    is SmbConnectionFailure.AuthenticationFailed -> BrowseMappedSmbError(
        message = "Authentication failed.",
        recoveryHint = "Verify username and password.",
    )

    is SmbConnectionFailure.ShareNotFound -> BrowseMappedSmbError(
        message = "SMB share not found.",
        recoveryHint = "Check the configured share name.",
    )

    is SmbConnectionFailure.RemotePermissionDenied -> BrowseMappedSmbError(
        message = "Remote permission denied.",
        recoveryHint = "Ensure the account has permission to access the share.",
    )

    is SmbConnectionFailure.Timeout, is SmbConnectionFailure.NetworkInterruption -> BrowseMappedSmbError(
        message = "Connection timed out or was interrupted.",
        recoveryHint = "Retry on a stable network and validate SMB server availability.",
    )

    is SmbConnectionFailure.Unknown -> BrowseMappedSmbError(
        message = "Unable to browse SMB path.",
        recoveryHint = "Review server details and try again.",
    )
}
