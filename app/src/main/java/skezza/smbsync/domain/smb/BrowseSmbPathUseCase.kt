package skezza.smbsync.domain.smb

import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbBrowseResult
import skezza.smbsync.data.smb.SmbClient

class BrowseSmbPathUseCase(
    private val smbClient: SmbClient,
) {
    suspend fun browse(
        host: String,
        shareName: String,
        path: String,
        username: String,
        password: String,
    ): SmbBrowseResult {
        val parsedTarget = SmbTargetParser.parse(host, shareName)
        if (parsedTarget is ParsedSmbTargetResult.Error) {
            throw IllegalArgumentException(parsedTarget.message)
        }

        val target = (parsedTarget as ParsedSmbTargetResult.Success).target
        return smbClient.browse(
            SmbBrowseRequest(
                host = target.host,
                shareName = target.shareName,
                path = path,
                username = username.trim(),
                password = password,
            ),
        )
    }
}
