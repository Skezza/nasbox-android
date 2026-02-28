package skezza.nasbox.data.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.buffer.Buffer
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbjClient : SmbClient {
    private data class BrowseAuthCandidate(
        val mode: String,
        val context: AuthenticationContext,
    )

    companion object {
        private const val LOG_TAG = "NasBoxSmb"
        private const val BROWSE_TAG = "NasBoxBrowse"
    }

    override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult = withContext(Dispatchers.IO) {
        val latency = SMBClient().use { smbClient ->
            smbClient.connect(request.host).use { connection ->
                var lastError: Throwable? = null
                for (candidate in browseAuthCandidates(request.username, request.password)) {
                    val attempt = runCatching {
                        measureTimeMillis {
                            connection.authenticate(candidate.context).use { session ->
                                if (request.shareName.isBlank()) {
                                    Unit
                                } else {
                                    session.connectShare(request.shareName).close()
                                }
                            }
                        }
                    }
                    val measuredLatency = attempt.getOrNull()
                    if (measuredLatency != null) {
                        return@use measuredLatency
                    }
                    lastError = attempt.exceptionOrNull()
                    Log.w(
                        BROWSE_TAG,
                        "testConnection authFailed host=${request.host} share=${request.shareName} mode=${candidate.mode} username=${request.username.trim()}",
                        lastError,
                    )
                }
                throw lastError ?: IllegalStateException("SMB test connection failed without exception.")
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
                var lastError: Throwable? = null
                for (candidate in browseAuthCandidates(username, password)) {
                    val attempt = runCatching {
                        connection.authenticate(candidate.context).use { session ->
                            val rawShares = readShareNamesReflectively(session)
                            rawShares
                                .map { it.trim().trimEnd('$') }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .sorted()
                        }
                    }
                    val shares = attempt.getOrNull()
                    if (shares != null) {
                        return@withContext shares
                    }
                    lastError = attempt.exceptionOrNull()
                    Log.w(
                        BROWSE_TAG,
                        "listShares authFailed host=$host mode=${candidate.mode} username=${username.trim()}",
                        lastError,
                    )
                }
                throw lastError ?: IllegalStateException("SMB share listing failed without exception.")
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
                var lastError: Throwable? = null
                for (candidate in browseAuthCandidates(username, password)) {
                    val attempt = runCatching {
                        connection.authenticate(candidate.context).use { session ->
                            val share = session.connectShare(shareName)
                            try {
                                val diskShare = share as? DiskShare
                                    ?: throw IllegalStateException("Share is not a disk share.")
                                val queryPath = path.trim().replace('/', '\\').trim('\\')
                                val directories = diskShare.list(queryPath)
                                    .filter { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L }
                                    .map { it.fileName }
                                    .filter { it != "." && it != ".." && it.isNotBlank() }
                                directories
                            } finally {
                                share.close()
                            }
                        }
                    }
                    val directories = attempt.getOrNull()
                    if (directories != null) {
                        return@withContext directories
                    }
                    lastError = attempt.exceptionOrNull()
                    Log.w(
                        BROWSE_TAG,
                        "listDirectories authFailed host=$host share=$shareName mode=${candidate.mode} username=${username.trim()}",
                        lastError,
                    )
                }
                throw lastError ?: IllegalStateException("SMB directory listing failed without exception.")
            }
        }
    }

    private fun browseAuthCandidates(username: String, password: String): List<BrowseAuthCandidate> {
        val trimmedUsername = username.trim()
        val hasExplicitCredentials = trimmedUsername.isNotBlank() || password.isNotBlank()
        return if (hasExplicitCredentials) {
            listOf(
                BrowseAuthCandidate(
                    mode = "user",
                    context = AuthenticationContext(trimmedUsername, password.toCharArray(), ""),
                ),
            )
        } else {
            listOf(
                BrowseAuthCandidate(mode = "guest", context = AuthenticationContext.guest()),
                BrowseAuthCandidate(mode = "anonymous", context = AuthenticationContext.anonymous()),
                BrowseAuthCandidate(mode = "blank", context = AuthenticationContext("", CharArray(0), "")),
            )
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
            Log.w(BROWSE_TAG, "readShareNamesReflectively failed sessionClass=${session.javaClass.name}", it)
        }.getOrDefault(emptyList())
    }

    override suspend fun uploadFile(
        request: SmbConnectionRequest,
        remotePath: String,
        contentLengthBytes: Long?,
        inputStream: InputStream,
        onProgressBytes: (Long) -> Unit,
    ): Unit {
        withContext(Dispatchers.IO) {
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
                                val finalSmbPath = normalizedPath.replace('/', '\\')
                                val tempSmbPath = temporaryUploadPath(finalSmbPath)
                                var renamedToFinalPath = false
                                diskShare.openFile(
                                    tempSmbPath,
                                    setOf(AccessMask.GENERIC_WRITE),
                                    setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                                    setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                                ).use { file ->
                                    try {
                                        val digest = MessageDigest.getInstance("MD5")
                                        var totalWritten = 0L
                                        file.outputStream.use { output ->
                                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                            while (true) {
                                                val read = inputStream.read(buffer)
                                                if (read <= 0) break
                                                output.write(buffer, 0, read)
                                                totalWritten += read
                                                digest.update(buffer, 0, read)
                                                onProgressBytes(totalWritten)
                                            }
                                            output.flush()
                                            if (contentLengthBytes != null && totalWritten != contentLengthBytes) {
                                                throw IOException(
                                                    "Upload truncated for $remotePath: wrote $totalWritten of $contentLengthBytes bytes.",
                                                )
                                            }
                                            Log.i(LOG_TAG, "uploadFile staged path=$remotePath bytes=$totalWritten")
                                        }
                                        val localMd5 = digest.digest().toHexString()
                                        verifyStagedUpload(
                                            diskShare = diskShare,
                                            tempSmbPath = tempSmbPath,
                                            expectedSizeBytes = totalWritten,
                                            expectedMd5 = localMd5,
                                            remotePath = remotePath,
                                        )
                                        file.rename(finalSmbPath, true)
                                        renamedToFinalPath = true
                                        Log.i(LOG_TAG, "uploadFile complete path=$remotePath")
                                    } catch (throwable: Throwable) {
                                        if (!renamedToFinalPath) {
                                            runCatching { diskShare.rm(tempSmbPath) }
                                                .onFailure {
                                                    Log.w(
                                                        LOG_TAG,
                                                        "uploadFile temp cleanup failed path=$tempSmbPath reason=${it.message}",
                                                    )
                                                }
                                        }
                                        throw throwable
                                    }
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

    private fun temporaryUploadPath(remotePath: String): String {
        val lastSeparatorIndex = remotePath.lastIndexOf('\\')
        val directory = if (lastSeparatorIndex >= 0) remotePath.substring(0, lastSeparatorIndex + 1) else ""
        val filename = if (lastSeparatorIndex >= 0) remotePath.substring(lastSeparatorIndex + 1) else remotePath
        return "$directory.$filename.nasbox-uploading"
    }

    private fun verifyStagedUpload(
        diskShare: DiskShare,
        tempSmbPath: String,
        expectedSizeBytes: Long,
        expectedMd5: String,
        remotePath: String,
    ) {
        val remoteMd5 = diskShare.openFile(
            tempSmbPath,
            setOf(AccessMask.GENERIC_READ),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { stagedFile ->
            val remoteSizeBytes = stagedFile.getFileInformation(FileStandardInformation::class.java).endOfFile
            if (remoteSizeBytes != expectedSizeBytes) {
                throw IOException(
                    "Remote size mismatch for $remotePath: expected $expectedSizeBytes bytes but found $remoteSizeBytes bytes.",
                )
            }
            stagedFile.inputStream.use { input ->
                md5(input)
            }
        }
        if (remoteMd5 != expectedMd5) {
            throw IOException("Remote checksum mismatch for $remotePath after upload verification.")
        }
        Log.i(LOG_TAG, "uploadFile verified path=$remotePath bytes=$expectedSizeBytes")
    }

    private fun md5(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
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
