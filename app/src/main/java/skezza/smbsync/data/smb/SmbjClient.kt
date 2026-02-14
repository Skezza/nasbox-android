package skezza.smbsync.data.smb

import com.hierynomus.protocol.commons.buffer.Buffer
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
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

    override suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult = withContext(Dispatchers.IO) {
        SMBClient().use { smbClient ->
            smbClient.connect(request.host).use { connection ->
                val authContext = AuthenticationContext(request.username, request.password.toCharArray(), "")
                connection.authenticate(authContext).use { session ->
                    if (request.shareName.isBlank()) {
                        val shares = readAvailableShares(session).sorted()
                        return@withContext SmbBrowseResult(
                            shareName = "",
                            directoryPath = "",
                            shares = shares,
                            directories = emptyList(),
                        )
                    }

                    val normalizedPath = normalizeDirectoryPath(request.directoryPath)
                    val connectedShare = session.connectShare(request.shareName)
                    if (connectedShare !is DiskShare) {
                        connectedShare.close()
                        return@withContext SmbBrowseResult(
                            shareName = request.shareName,
                            directoryPath = normalizedPath,
                            shares = emptyList(),
                            directories = emptyList(),
                        )
                    }

                    connectedShare.use { diskShare ->
                        val directories = diskShare.list(normalizedPath)
                            .asSequence()
                            .map { it.fileName }
                            .filter { it != "." && it != ".." }
                            .filter { entryName ->
                                runCatching {
                                    diskShare.folderExists(joinPath(normalizedPath, entryName))
                                }.getOrDefault(false)
                            }
                            .sorted()
                            .toList()

                        SmbBrowseResult(
                            shareName = request.shareName,
                            directoryPath = normalizedPath,
                            shares = emptyList(),
                            directories = directories,
                        )
                    }
                }
            }
        }
    }
}

private fun normalizeDirectoryPath(path: String): String {
    return path.trim().replace('\\', '/').trim('/').takeIf { it.isNotBlank() } ?: ""
}

private fun joinPath(basePath: String, segment: String): String {
    val normalizedSegment = segment.trim().trim('/').trim('\\')
    if (basePath.isBlank()) {
        return normalizedSegment
    }
    if (normalizedSegment.isBlank()) {
        return basePath
    }
    return "$basePath/$normalizedSegment"
}


private fun readAvailableShares(session: Any): List<String> {
    return runCatching {
        val method = session::class.java.methods.firstOrNull {
            it.name == "listShares" && it.parameterCount == 0
        } ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        when (val raw = method.invoke(session)) {
            is Collection<*> -> raw.filterIsInstance<String>()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
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
