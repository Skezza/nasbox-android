package skezza.nasbox.domain.smb

import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbConnectionFailure
import skezza.nasbox.data.smb.SmbShareRpcEnumerator
import skezza.nasbox.data.smb.toSmbConnectionFailure

class BrowseSmbDestinationUseCase(
    private val smbClient: SmbClient,
    private val shareRpcEnumerator: SmbShareRpcEnumerator,
) {
    suspend fun listShares(
        host: String,
        username: String,
        password: String,
        domain: String,
    ): SmbBrowseResult<List<String>> {
        val trimmedUsername = username.trim()
        val trimmedDomain = domain.trim()
        val parsedTarget = SmbTargetParser.parse(host, "")
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbBrowseResult.Failure(
                message = parsedTarget.message,
                recoveryHint = "Use host like quanta.local or smb://quanta.local/share.",
            )
        }
        val target = (parsedTarget as ParsedSmbTargetResult.Success).target

        val rpcResult = runCatching {
            shareRpcEnumerator.listSharesViaRpc(
                host = target.host,
                username = trimmedUsername,
                password = password,
                domain = trimmedDomain,
            )
        }
        val rpcShares = rpcResult.getOrNull().orEmpty()
        val rpcException = rpcResult.exceptionOrNull()
        val shouldFallback = rpcShares.isEmpty()
        val fallbackResult = if (shouldFallback) {
            runCatching {
                smbClient.listShares(
                    host = target.host,
                    username = trimmedUsername,
                    password = password,
                )
            }
        } else {
            null
        }
        val fallbackShares = fallbackResult?.getOrNull().orEmpty()
        if (shouldFallback) {
            val fallbackException = fallbackResult?.exceptionOrNull()
        }

        val mergedShares = (rpcShares + fallbackShares)
            .map { it.trim().trimEnd('$').trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        if (mergedShares.isNotEmpty()) {
            return SmbBrowseResult.Success(mergedShares)
        }

        val fallbackException = fallbackResult?.exceptionOrNull()
        return when {
            fallbackResult?.isFailure == true && fallbackException != null -> fallbackException.toBrowseFailure()
            rpcResult.isFailure && rpcException != null -> rpcException.toBrowseFailure()
            else -> SmbBrowseResult.Success(emptyList())
        }
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

    companion object {
        private const val TAG = "NasBoxBrowse"
    }
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
    val mapped = when (this) {
        is SmbConnectionFailure -> this
        else -> toSmbConnectionFailure()
    }
    return when (mapped) {
        is SmbConnectionFailure.HostUnreachable -> SmbBrowseResult.Failure(
            message = "Unable to reach the host.",
            recoveryHint = "Check host and Wi-Fi connectivity.",
            technicalDetail = message,
        )

        is SmbConnectionFailure.AuthenticationFailed -> SmbBrowseResult.Failure(
            message = "Authentication failed.",
            recoveryHint = "Verify username and password.",
            technicalDetail = message,
        )

        is SmbConnectionFailure.ShareNotFound -> SmbBrowseResult.Failure(
            message = "Share not found.",
            recoveryHint = "Choose a different share.",
            technicalDetail = message,
        )

        is SmbConnectionFailure.RemotePermissionDenied -> SmbBrowseResult.Failure(
            message = "Permission denied.",
            recoveryHint = "Ensure the account can browse this location.",
            technicalDetail = message,
        )

        is SmbConnectionFailure.Timeout,
        is SmbConnectionFailure.NetworkInterruption -> SmbBrowseResult.Failure(
            message = "Browse timed out or was interrupted.",
            recoveryHint = "Retry on a stable network.",
            technicalDetail = message,
        )

        is SmbConnectionFailure.Unknown -> SmbBrowseResult.Failure(
            message = "Unable to browse SMB destination.",
            recoveryHint = "Review settings and try again.",
            technicalDetail = message,
        )
    }
}
