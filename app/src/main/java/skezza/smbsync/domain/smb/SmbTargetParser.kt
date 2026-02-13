package skezza.smbsync.domain.smb

data class ParsedSmbTarget(
    val host: String,
    val shareName: String,
    val wasProtocolProvided: Boolean,
)

sealed class ParsedSmbTargetResult {
    data class Success(val target: ParsedSmbTarget) : ParsedSmbTargetResult()
    data class Error(val message: String) : ParsedSmbTargetResult()
}

object SmbTargetParser {
    fun parse(hostInput: String, shareInput: String): ParsedSmbTargetResult {
        val rawHost = hostInput.trim().replace('\\', '/')
        val rawShare = shareInput.trim().trim('/').trim('\\')

        val withoutProtocol = rawHost.removePrefix("smb://").removePrefix("SMB://")
        val wasProtocolProvided = withoutProtocol != rawHost

        val hostSegments = withoutProtocol.split('/').filter { it.isNotBlank() }
        if (hostSegments.isEmpty()) {
            return ParsedSmbTargetResult.Error("Host is required.")
        }

        val parsedHost = hostSegments.first()
        val shareFromHost = hostSegments.getOrNull(1)
        val resolvedShare = when {
            rawShare == "/" -> ""
            rawShare.isNotEmpty() -> rawShare
            !shareFromHost.isNullOrBlank() -> shareFromHost
            else -> ""
        }

        return ParsedSmbTargetResult.Success(
            ParsedSmbTarget(
                host = parsedHost,
                shareName = resolvedShare,
                wasProtocolProvided = wasProtocolProvided,
            ),
        )
    }
}
