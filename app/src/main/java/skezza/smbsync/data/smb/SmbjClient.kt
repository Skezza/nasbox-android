package skezza.smbsync.data.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.protocol.commons.buffer.Buffer
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbjClient : SmbClient {
    companion object {
        private const val LOG_TAG = "SMBSyncSmb"
    }

    override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult = withContext(Dispatchers.IO) {
        val latency = measureTimeMillis {
            SMBClient().use { smbClient ->
                smbClient.connect(request.host).use { connection ->
                    val authContext = AuthenticationContext(request.username, request.password.toCharArray(), "")
                    connection.authenticate(authContext).use { session ->
                        if (request.shareName.isBlank()) {
                            Unit
                        } else {
                            session.connectShare(request.shareName).close()
                        }
                    }
                }
            }
        }
        SmbConnectionResult(latencyMs = latency)
    }

    override suspend fun uploadFile(
        request: SmbConnectionRequest,
        remotePath: String,
        contentLengthBytes: Long?,
        inputStream: InputStream,
        onProgressBytes: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        Log.i(
            LOG_TAG,
            "uploadFile start host=${request.host} share=${request.shareName} path=$remotePath size=${contentLengthBytes ?: -1}",
        )

        runCatching {
            SMBClient().use { smbClient ->
                smbClient.connect(request.host).use { connection ->
                    val authContext = AuthenticationContext(request.username, request.password.toCharArray(), "")
                    connection.authenticate(authContext).use { session ->
                        val share = session.connectShare(request.shareName)
                        (share as? DiskShare)?.use { diskShare ->
                            val normalizedPath = remotePath.replace("\\", "/").trim('/')
                            ensureDirectories(diskShare, normalizedPath)
                            val file = diskShare.openFile(
                                normalizedPath.replace('/', '\\'),
                                setOf(AccessMask.GENERIC_WRITE),
                                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                                setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                            )
                            file.outputStream.use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var totalWritten = 0L
                                while (true) {
                                    val read = inputStream.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    totalWritten += read
                                    onProgressBytes(totalWritten)
                                }
                                output.flush()
                                Log.i(LOG_TAG, "uploadFile complete path=$remotePath bytes=$totalWritten")
                            }
                        } ?: error("Connected share is not a disk share")
                        share.close()
                    }
                }
            }
        }.onFailure {
            Log.e(
                LOG_TAG,
                "uploadFile failed host=${request.host} share=${request.shareName} path=$remotePath reason=${it.message}",
            )
            throw it
        }
    }

    private fun ensureDirectories(share: DiskShare, remotePath: String) {
        val parts = remotePath.split('/').filter { it.isNotBlank() }
        if (parts.size <= 1) return
        var current = ""
        for (segment in parts.dropLast(1)) {
            current = if (current.isBlank()) segment else "$current\\$segment"
            if (!share.folderExists(current)) {
                share.mkdir(current)
            }
        }
    }
}

fun Throwable.toSmbConnectionFailure(): SmbConnectionFailure = when (this) {
    is UnknownHostException -> SmbConnectionFailure.HostUnreachable(this)
    is SocketTimeoutException -> SmbConnectionFailure.Timeout(this)
    is Buffer.BufferException -> SmbConnectionFailure.NetworkInterruption(this)
    else -> {
        val message = message.orEmpty().lowercase()
        when {
            "logon failure" in message || "status_logon_failure" in message -> SmbConnectionFailure.AuthenticationFailed(this)
            "bad network name" in message || "status_bad_network_name" in message -> SmbConnectionFailure.ShareNotFound(this)
            "access denied" in message || "status_access_denied" in message -> SmbConnectionFailure.RemotePermissionDenied(this)
            "timeout" in message -> SmbConnectionFailure.Timeout(this)
            else -> SmbConnectionFailure.Unknown(this)
        }
    }
}

sealed class SmbConnectionFailure(
    val causeError: Throwable,
) {
    class HostUnreachable(causeError: Throwable) : SmbConnectionFailure(causeError)
    class AuthenticationFailed(causeError: Throwable) : SmbConnectionFailure(causeError)
    class ShareNotFound(causeError: Throwable) : SmbConnectionFailure(causeError)
    class RemotePermissionDenied(causeError: Throwable) : SmbConnectionFailure(causeError)
    class Timeout(causeError: Throwable) : SmbConnectionFailure(causeError)
    class NetworkInterruption(causeError: Throwable) : SmbConnectionFailure(causeError)
    class Unknown(causeError: Throwable) : SmbConnectionFailure(causeError)
}
