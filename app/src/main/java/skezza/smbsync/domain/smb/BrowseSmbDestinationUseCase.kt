package skezza.smbsync.domain.smb

import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.toSmbConnectionFailure

class BrowseSmbDestinationUseCase(
    private val smbClient: SmbClient,
) {
    suspend fun listShares(
        host: String,
        username: String,
        password: String,
    ): SmbBrowseResult<List<String>> {
        val parsedTarget = SmbTargetParser.parse(host, "")
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbBrowseResult.Failure(
                message = parsedTarget.message,
                recoveryHint = "Use host like quanta.local or smb://quanta.local/share.",
            )
        }
        val target = (parsedTarget as ParsedSmbTargetResult.Success).target

        return runCatching {
            smbClient.listShares(
                host = target.host,
                username = username.trim(),
                password = password,
            )
        }.fold(
            onSuccess = { SmbBrowseResult.Success(it.sorted()) },
            onFailure = { it.toBrowseFailure() },
        )
    }

    suspend fun listDirectories(
        host: String,
        shareName: String,
        path: String,
        username: String,
        password: String,
    ): SmbBrowseResult<List<String>> {
        val parsedTarget = SmbTargetParser.parse(host, shareName)
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbBrowseResult.Failure(
                message = parsedTarget.message,
                recoveryHint = "Verify host and share name.",
            )
        }
        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        if (target.shareName.isBlank()) {
            return SmbBrowseResult.Failure(
                message = "Select a share before browsing folders.",
                recoveryHint = "Choose a share from the list first.",
            )
        }

        return runCatching {
            smbClient.listDirectories(
                host = target.host,
                shareName = target.shareName,
                path = normalizePath(path),
                username = username.trim(),
                password = password,
            )
        }.fold(
            onSuccess = { SmbBrowseResult.Success(it.sorted()) },
            onFailure = { it.toBrowseFailure() },
        )
    }

    fun normalizePath(path: String): String = path.trim().replace('\\', '/').trim('/').replace("//", "/")
}

sealed class SmbBrowseResult<out T> {
    data class Success<T>(val data: T) : SmbBrowseResult<T>()
    data class Failure(
        val message: String,
        val recoveryHint: String? = null,
        val technicalDetail: String? = null,
    ) : SmbBrowseResult<Nothing>()
}

private fun Throwable.toBrowseFailure(): SmbBrowseResult.Failure {
    val mapped = toSmbConnectionFailure()
    return when (mapped) {
        is skezza.smbsync.data.smb.SmbConnectionFailure.HostUnreachable -> SmbBrowseResult.Failure(
            message = "Unable to reach the host.",
            recoveryHint = "Check host and Wi-Fi connectivity.",
            technicalDetail = message,
        )

        is skezza.smbsync.data.smb.SmbConnectionFailure.AuthenticationFailed -> SmbBrowseResult.Failure(
            message = "Authentication failed.",
            recoveryHint = "Verify username and password.",
            technicalDetail = message,
        )

        is skezza.smbsync.data.smb.SmbConnectionFailure.ShareNotFound -> SmbBrowseResult.Failure(
            message = "Share not found.",
            recoveryHint = "Choose a different share.",
            technicalDetail = message,
        )

        is skezza.smbsync.data.smb.SmbConnectionFailure.RemotePermissionDenied -> SmbBrowseResult.Failure(
            message = "Permission denied.",
            recoveryHint = "Ensure the account can browse this location.",
            technicalDetail = message,
        )

        is skezza.smbsync.data.smb.SmbConnectionFailure.Timeout,
        is skezza.smbsync.data.smb.SmbConnectionFailure.NetworkInterruption -> SmbBrowseResult.Failure(
            message = "Browse timed out or was interrupted.",
            recoveryHint = "Retry on a stable network.",
            technicalDetail = message,
        )

        is skezza.smbsync.data.smb.SmbConnectionFailure.Unknown -> SmbBrowseResult.Failure(
            message = "Unable to browse SMB destination.",
            recoveryHint = "Review settings and try again.",
            technicalDetail = message,
        )
    }
}
