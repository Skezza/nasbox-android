package skezza.smbsync.domain.smb

import skezza.smbsync.data.smb.SmbBrowseLevel
import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.toSmbConnectionFailure

data class SmbBrowserUiEntry(
    val name: String,
    val path: String,
)

data class SmbBrowserUiResult(
    val success: Boolean,
    val host: String,
    val shareName: String,
    val currentPath: String,
    val level: SmbBrowseLevel,
    val entries: List<SmbBrowserUiEntry>,
    val message: String? = null,
) {
    companion object {
        fun failure(message: String): SmbBrowserUiResult = SmbBrowserUiResult(
            success = false,
            host = "",
            shareName = "",
            currentPath = "",
            level = SmbBrowseLevel.SHARES,
            entries = emptyList(),
            message = message,
        )
    }
}

class BrowseSmbLocationUseCase(
    private val smbClient: SmbClient,
) {
    suspend operator fun invoke(
        host: String,
        shareName: String,
        currentPath: String,
        username: String,
        password: String,
    ): SmbBrowserUiResult {
        val parsedTarget = SmbTargetParser.parse(host, shareName)
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            return SmbBrowserUiResult.failure(parsedTarget.message)
        }

        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        return runCatching {
            smbClient.browse(
                SmbBrowseRequest(
                    host = target.host,
                    shareName = target.shareName,
                    path = currentPath,
                    username = username.trim(),
                    password = password,
                ),
            )
        }.fold(
            onSuccess = { result ->
                SmbBrowserUiResult(
                    success = true,
                    host = target.host,
                    shareName = target.shareName,
                    currentPath = normalizePath(currentPath),
                    level = result.level,
                    entries = result.entries.map {
                        SmbBrowserUiEntry(name = it.name, path = it.path)
                    },
                    message = null,
                )
            },
            onFailure = {
                val mapped = it.toSmbConnectionFailure().toUiError()
                SmbBrowserUiResult.failure("${mapped.message} ${mapped.recoveryHint}")
            },
        )
    }

    private fun normalizePath(path: String): String = path.trim().trim('/').trim('\\')
}
