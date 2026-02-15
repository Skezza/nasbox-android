package skezza.nasbox.domain.smb

import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbConnectionFailure
import skezza.nasbox.data.smb.SmbConnectionRequest
import skezza.nasbox.data.smb.toSmbConnectionFailure

class TestSmbConnectionUseCase(
    private val serverRepository: ServerRepository,
    private val credentialStore: CredentialStore,
    private val smbClient: SmbClient,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun testPersistedServer(serverId: Long): SmbConnectionUiResult {
        val server = serverRepository.getServer(serverId)
            ?: return SmbConnectionUiResult.failure(
                category = SmbErrorCategory.UNKNOWN,
                message = "Server not found.",
                recoveryHint = "Reopen the server list and try again.",
            )

        val password = credentialStore.loadPassword(server.credentialAlias)
            ?: return SmbConnectionUiResult.failure(
                category = SmbErrorCategory.AUTHENTICATION_FAILED,
                message = "Missing stored password.",
                recoveryHint = "Edit the server and save credentials again.",
            )

        val result = test(
            host = server.host,
            shareName = server.shareName,
            username = server.username,
            password = password,
        )

        serverRepository.updateServer(
            server.copy(
                lastTestStatus = if (result.success) "SUCCESS" else "FAILED",
                lastTestTimestampEpochMs = nowEpochMs(),
                lastTestLatencyMs = result.latencyMs,
                lastTestErrorCategory = result.category.name,
                lastTestErrorMessage = if (result.success) null else result.message,
            ),
        )

        return result
    }

    suspend fun testDraftServer(
        host: String,
        shareName: String,
        username: String,
        password: String,
    ): SmbConnectionUiResult = test(host, shareName, username, password)

    private suspend fun test(
        host: String,
        shareName: String,
        username: String,
        password: String,
    ): SmbConnectionUiResult {
        val parsedTarget = SmbTargetParser.parse(host, shareName)
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbConnectionUiResult.failure(
                category = SmbErrorCategory.UNKNOWN,
                message = parsedTarget.message,
                recoveryHint = "Use host like quanta.local (or smb://quanta.local/share) and verify share.",
            )
        }

        val target = (parsedTarget as ParsedSmbTargetResult.Success).target

        return runCatching {
            smbClient.testConnection(
                SmbConnectionRequest(
                    host = target.host,
                    shareName = target.shareName,
                    username = username.trim(),
                    password = password,
                ),
            )
        }.fold(
            onSuccess = {
                SmbConnectionUiResult.success(
                    latencyMs = it.latencyMs,
                    endpoint = if (target.shareName.isBlank()) target.host else "${target.host}/${target.shareName}",
                )
            },
            onFailure = { throwable ->
                val mapped = throwable.toSmbConnectionFailure().toUiError()
                SmbConnectionUiResult.failure(
                    category = mapped.category,
                    message = mapped.message,
                    recoveryHint = mapped.recoveryHint,
                    technicalDetail = throwable.message,
                )
            },
        )
    }
}

enum class SmbErrorCategory {
    NONE,
    HOST_UNREACHABLE,
    AUTHENTICATION_FAILED,
    SHARE_NOT_FOUND,
    REMOTE_PERMISSION_DENIED,
    TIMEOUT_OR_INTERRUPTED,
    UNKNOWN,
}

data class SmbConnectionUiResult(
    val success: Boolean,
    val category: SmbErrorCategory,
    val message: String,
    val recoveryHint: String? = null,
    val technicalDetail: String? = null,
    val latencyMs: Long? = null,
) {
    companion object {
        fun success(latencyMs: Long, endpoint: String): SmbConnectionUiResult = SmbConnectionUiResult(
            success = true,
            category = SmbErrorCategory.NONE,
            message = "Connection succeeded to $endpoint (${latencyMs}ms).",
            latencyMs = latencyMs,
        )

        fun failure(
            category: SmbErrorCategory,
            message: String,
            recoveryHint: String? = null,
            technicalDetail: String? = null,
        ): SmbConnectionUiResult = SmbConnectionUiResult(
            success = false,
            category = category,
            message = message,
            recoveryHint = recoveryHint,
            technicalDetail = technicalDetail,
        )
    }
}

private data class MappedSmbError(
    val category: SmbErrorCategory,
    val message: String,
    val recoveryHint: String,
)

private fun SmbConnectionFailure.toUiError(): MappedSmbError = when (this) {
    is SmbConnectionFailure.HostUnreachable -> MappedSmbError(
        category = SmbErrorCategory.HOST_UNREACHABLE,
        message = "Unable to reach the host.",
        recoveryHint = "Check host name/IP and network connectivity.",
    )

    is SmbConnectionFailure.AuthenticationFailed -> MappedSmbError(
        category = SmbErrorCategory.AUTHENTICATION_FAILED,
        message = "Authentication failed.",
        recoveryHint = "Verify username and password.",
    )

    is SmbConnectionFailure.ShareNotFound -> MappedSmbError(
        category = SmbErrorCategory.SHARE_NOT_FOUND,
        message = "SMB share not found.",
        recoveryHint = "Check the configured share name.",
    )

    is SmbConnectionFailure.RemotePermissionDenied -> MappedSmbError(
        category = SmbErrorCategory.REMOTE_PERMISSION_DENIED,
        message = "Remote permission denied.",
        recoveryHint = "Ensure the account has permission to access the share.",
    )

    is SmbConnectionFailure.Timeout, is SmbConnectionFailure.NetworkInterruption -> MappedSmbError(
        category = SmbErrorCategory.TIMEOUT_OR_INTERRUPTED,
        message = "Connection timed out or was interrupted.",
        recoveryHint = "Retry on a stable network and validate SMB server availability.",
    )

    is SmbConnectionFailure.Unknown -> MappedSmbError(
        category = SmbErrorCategory.UNKNOWN,
        message = "Connection test failed.",
        recoveryHint = "Review server details and try again.",
    )
}
