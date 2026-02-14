package skezza.smbsync.domain.smb

import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest

class BrowseSmbPathUseCase(
    private val smbClient: SmbClient,
) {
    suspend fun listShares(
        host: String,
        username: String,
        password: String,
    ): List<String> {
        val parsedTarget = SmbTargetParser.parse(host, shareInput = "")
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            throw IllegalArgumentException(parsedTarget.message)
        }
        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        return smbClient.listShares(
            SmbConnectionRequest(
                host = target.host,
                shareName = "",
                username = username.trim(),
                password = password,
            ),
        ).map { it.name }
    }

    suspend fun listDirectories(
        host: String,
        shareName: String,
        username: String,
        password: String,
        folderPath: String,
    ): List<String> {
        val parsedTarget = SmbTargetParser.parse(host, shareName)
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            throw IllegalArgumentException(parsedTarget.message)
        }

        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        if (target.shareName.isBlank()) {
            throw IllegalArgumentException("Choose a share before browsing folders.")
        }

        return smbClient.listDirectories(
            SmbBrowseRequest(
                host = target.host,
                shareName = target.shareName,
                username = username.trim(),
                password = password,
                folderPath = folderPath,
            ),
        ).map { it.name }
    }
}
