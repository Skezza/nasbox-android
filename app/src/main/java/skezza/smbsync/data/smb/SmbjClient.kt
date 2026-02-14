package skezza.smbsync.data.smb

import android.util.Log
import com.hierynomus.protocol.commons.buffer.Buffer
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

    override suspend fun listShares(
        host: String,
        username: String,
        password: String,
    ): List<String> = withContext(Dispatchers.IO) {
        SMBClient().use { smbClient ->
            smbClient.connect(host).use { connection ->
                val authContext = AuthenticationContext(username, password.toCharArray(), "")
                connection.authenticate(authContext).use { session ->
                    val rawShares = readShareNamesReflectively(session)
                    val normalizedShares = rawShares
                        .map { it.trim().trimEnd('$') }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                    Log.d(TAG, "listShares host=$host rawCount=${rawShares.size} normalizedCount=${normalizedShares.size} values=$normalizedShares")
                    normalizedShares
                }
            }
        }
    }

    override suspend fun listDirectories(
        host: String,
        shareName: String,
        path: String,
        username: String,
        password: String,
    ): List<String> = withContext(Dispatchers.IO) {
        SMBClient().use { smbClient ->
            smbClient.connect(host).use { connection ->
                val authContext = AuthenticationContext(username, password.toCharArray(), "")
                connection.authenticate(authContext).use { session ->
                    val share = session.connectShare(shareName)
                    try {
                        val diskShare = share as? DiskShare
                            ?: throw IllegalStateException("Share is not a disk share.")
                        val queryPath = path.trim().replace('/', '\\').trim('\\')
                        val directories = diskShare.list(queryPath)
                            .map { it.fileName }
                            .filter { it != "." && it != ".." && it.isNotBlank() }
                        Log.d(TAG, "listDirectories host=$host share=$shareName path=$queryPath count=${directories.size} directories=$directories")
                        directories
                    } finally {
                        share.close()
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readShareNamesReflectively(session: Any): List<String> {
        return runCatching {
            val method = session.javaClass.methods.firstOrNull { it.name == "listShares" && it.parameterCount == 0 }
            val result = method?.invoke(session)
            when (result) {
                is Collection<*> -> result.mapNotNull { item ->
                    when (item) {
                        is String -> item
                        null -> null
                        else -> {
                            val netName = item.javaClass.methods.firstOrNull { m -> m.name == "getNetName" && m.parameterCount == 0 }
                                ?.invoke(item) as? String
                            netName ?: item.toString()
                        }
                    }
                }
                else -> emptyList()
            }
        }.onFailure {
            Log.w(TAG, "readShareNamesReflectively failed sessionClass=${session.javaClass.name}", it)
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "SMBSyncBrowse"
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
