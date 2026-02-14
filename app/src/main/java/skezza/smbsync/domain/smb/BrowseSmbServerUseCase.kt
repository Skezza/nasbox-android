package skezza.smbsync.domain.smb

import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.toSmbConnectionFailure

class BrowseSmbServerUseCase(
    private val smbClient: SmbClient,
) {
    suspend fun listShares(
        host: String,
        username: String,
        password: String,
    ): SmbBrowseUiResult {
        val parsedTarget = SmbTargetParser.parse(host, "")
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbBrowseUiResult.failure(
                message = parsedTarget.message,
                recoveryHint = "Use host like quanta.local or smb://quanta.local.",
            )
        }

        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        return runCatching {
            smbClient.listShares(
                SmbConnectionRequest(
                    host = target.host,
                    shareName = "",
                    username = username.trim(),
                    password = password,
                ),
            )
        }.fold(
            onSuccess = { SmbBrowseUiResult.success(it) },
            onFailure = { throwable ->
                val mapped = throwable.toSmbConnectionFailure().toBrowseUiError()
                SmbBrowseUiResult.failure(
                    message = mapped.message,
                    recoveryHint = mapped.recoveryHint,
                    technicalDetail = throwable.message,
                )
            },
        )
    }

    suspend fun listDirectories(
        host: String,
        shareName: String,
        directoryPath: String,
        username: String,
        password: String,
    ): SmbBrowseUiResult {
        val parsedTarget = SmbTargetParser.parse(host, shareName)
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbBrowseUiResult.failure(
                message = parsedTarget.message,
                recoveryHint = "Use host like quanta.local and pick a valid share.",
            )
        }

        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        if (target.shareName.isBlank()) {
            return SmbBrowseUiResult.failure(
                message = "Select a share before browsing folders.",
                recoveryHint = "Pick a share from the list first.",
            )
        }

        return runCatching {
            smbClient.listDirectories(
                request = SmbConnectionRequest(
                    host = target.host,
                    shareName = target.shareName,
                    username = username.trim(),
                    password = password,
                ),
                path = directoryPath,
            )
        }.fold(
            onSuccess = { SmbBrowseUiResult.success(it) },
            onFailure = { throwable ->
                val mapped = throwable.toSmbConnectionFailure().toBrowseUiError()
                SmbBrowseUiResult.failure(
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
    val entries: List<String>,
    val message: String? = null,
    val recoveryHint: String? = null,
    val technicalDetail: String? = null,
) {
    companion object {
        fun success(entries: List<String>): SmbBrowseUiResult = SmbBrowseUiResult(
            success = true,
            entries = entries,
        )

        fun failure(
            message: String,
            recoveryHint: String? = null,
            technicalDetail: String? = null,
        ): SmbBrowseUiResult = SmbBrowseUiResult(
            success = false,
            entries = emptyList(),
            message = message,
            recoveryHint = recoveryHint,
            technicalDetail = technicalDetail,
        )
    }
}

private data class MappedSmbBrowseError(
    val message: String,
    val recoveryHint: String,
)

private fun skezza.smbsync.data.smb.SmbConnectionFailure.toBrowseUiError(): MappedSmbBrowseError = when (this) {
    is skezza.smbsync.data.smb.SmbConnectionFailure.HostUnreachable -> MappedSmbBrowseError(
        message = "Unable to reach the host.",
        recoveryHint = "Check host/IP and make sure you're on the same network.",
    )

    is skezza.smbsync.data.smb.SmbConnectionFailure.AuthenticationFailed -> MappedSmbBrowseError(
        message = "Authentication failed.",
        recoveryHint = "Verify username and password before browsing.",
    )

    is skezza.smbsync.data.smb.SmbConnectionFailure.ShareNotFound -> MappedSmbBrowseError(
        message = "Share not found.",
        recoveryHint = "Choose a different share and retry.",
    )

    is skezza.smbsync.data.smb.SmbConnectionFailure.RemotePermissionDenied -> MappedSmbBrowseError(
        message = "Permission denied.",
        recoveryHint = "The account cannot browse this location.",
    )

    is skezza.smbsync.data.smb.SmbConnectionFailure.Timeout,
    is skezza.smbsync.data.smb.SmbConnectionFailure.NetworkInterruption -> MappedSmbBrowseError(
        message = "Browsing timed out or was interrupted.",
        recoveryHint = "Retry on a stable connection.",
    )

    is skezza.smbsync.data.smb.SmbConnectionFailure.Unknown -> MappedSmbBrowseError(
        message = "Unable to browse SMB resources.",
        recoveryHint = "Review connection details and try again.",
    )
}
