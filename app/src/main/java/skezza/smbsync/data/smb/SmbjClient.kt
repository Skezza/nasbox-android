package skezza.smbsync.data.smb

import com.hierynomus.protocol.commons.buffer.Buffer
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbjClient : SmbClient {
    override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult = withContext(Dispatchers.IO) {
        val latency = measureTimeMillis {
            SMBClient().use { smbClient ->
                smbClient.connect(request.host).use { connection ->
                    val authContext = AuthenticationContext(request.username, request.password.toCharArray(), "")
                    connection.authenticate(authContext).use { session ->
                        if (request.shareName.isBlank()) {
                            // Root-level validation: auth + session establishment only.
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

    override suspend fun listShares(request: SmbConnectionRequest): List<String> = withContext(Dispatchers.IO) {
        SMBClient().use { smbClient ->
            smbClient.connect(request.host).use { connection ->
                val authContext = AuthenticationContext(request.username, request.password.toCharArray(), "")
                connection.authenticate(authContext).use { session ->
                    session.listShares()
                        .map { it.netName.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                }
            }
        }
    }

    override suspend fun listDirectories(request: SmbConnectionRequest, path: String): List<String> = withContext(Dispatchers.IO) {
        SMBClient().use { smbClient ->
            smbClient.connect(request.host).use { connection ->
                val authContext = AuthenticationContext(request.username, request.password.toCharArray(), "")
                connection.authenticate(authContext).use { session ->
                    val share = session.connectShare(request.shareName) as DiskShare
                    share.use {
                        share.list(path.normalizeSmbPath())
                            .asSequence()
                            .filter { it.fileName != "." && it.fileName != ".." }
                            .filter { it.fileAttributes.contains(FileAttributes.FILE_ATTRIBUTE_DIRECTORY) }
                            .map { it.fileName }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted()
                            .toList()
                    }
                }
            }
        }
    }
}

private fun String.normalizeSmbPath(): String {
    val normalized = trim().replace('\\', '/').trim('/')
    return normalized.ifBlank { "" }
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
