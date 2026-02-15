package skezza.nasbox.data.smb

import android.util.Log
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbjRpcShareEnumerator : SmbShareRpcEnumerator {
    private data class RpcAuthCandidate(
        val mode: String,
        val context: AuthenticationContext,
    )

    override suspend fun listSharesViaRpc(
        host: String,
        username: String,
        password: String,
        domain: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val targetHost = normalizeHost(host)
        val trimmedUsername = username.trim()
        val trimmedDomain = domain.trim()

        SMBClient().use { smbClient ->
            smbClient.connect(targetHost).use { connection ->
                var lastError: Throwable? = null
                for (candidate in rpcAuthCandidates(trimmedUsername, password, trimmedDomain)) {
                    val attempt = runCatching {
                        connection.authenticate(candidate.context).use { session ->
                            val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                            val serverService = ServerService(transport)
                            runCatching {
                                serverService.getShares1().mapNotNull { normalizeShareName(it.netName) }
                            }.getOrElse {
                                // Some servers reject info level 1 but allow level 0 share enumeration.
                                serverService.getShares0().mapNotNull { normalizeShareName(it.netName) }
                            }
                        }
                    }
                    val shares = attempt.getOrNull()
                    if (shares != null) {
                        return@withContext shares
                    }
                    lastError = attempt.exceptionOrNull()
                    Log.w(
                        TAG,
                        "listSharesViaRpc authFailed host=$targetHost mode=${candidate.mode} domain=$trimmedDomain username=$trimmedUsername",
                        lastError,
                    )
                }
                throw lastError ?: IllegalStateException("RPC share enumeration failed without exception.")
            }
        }
    }

    private fun normalizeHost(host: String): String {
        val trimmed = host.trim().removePrefix("smb://").removePrefix("SMB://")
        return trimmed.split('/').firstOrNull()?.trim().orEmpty()
    }

    private fun normalizeShareName(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun rpcAuthCandidates(username: String, password: String, domain: String): List<RpcAuthCandidate> {
        val hasExplicitCredentials = username.isNotBlank() || password.isNotBlank()
        return if (hasExplicitCredentials) {
            listOf(
                RpcAuthCandidate(
                    mode = "user",
                    context = AuthenticationContext(username, password.toCharArray(), domain),
                ),
            )
        } else {
            listOf(
                RpcAuthCandidate(mode = "guest", context = AuthenticationContext.guest()),
                RpcAuthCandidate(mode = "anonymous", context = AuthenticationContext.anonymous()),
                RpcAuthCandidate(mode = "blank", context = AuthenticationContext("", CharArray(0), domain)),
            )
        }
    }

    companion object {
        private const val TAG = "NasBoxBrowse"
    }
}
