package skezza.smbsync.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class DiscoveredSmbServer(
    val host: String,
    val ipAddress: String,
)

interface SmbServerDiscoveryScanner {
    suspend fun discover(): List<DiscoveredSmbServer>
}

class AndroidSmbServerDiscoveryScanner(
    private val context: Context,
) : SmbServerDiscoveryScanner {

    override suspend fun discover(): List<DiscoveredSmbServer> = withContext(Dispatchers.IO) {
        val prefix = localSubnetPrefix() ?: return@withContext emptyList()
        val localIp = localIpAddress()
        val semaphore = Semaphore(32)

        coroutineScope {
            (1..254).map { hostIndex ->
                async {
                    val ip = "$prefix.$hostIndex"
                    if (ip == localIp) return@async null
                    semaphore.withPermit {
                        if (isSmbPortOpen(ip)) {
                            DiscoveredSmbServer(
                                host = reverseLookupName(ip) ?: ip,
                                ipAddress = ip,
                            )
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.ipAddress }.sortedBy { it.host }
        }
    }

    private fun localSubnetPrefix(): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val ip = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        val byte1 = ip and 0xFF
        val byte2 = ip shr 8 and 0xFF
        val byte3 = ip shr 16 and 0xFF
        return "$byte1.$byte2.$byte3"
    }

    private fun localIpAddress(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (networkInterface in interfaces) {
            for (inetAddress in Collections.list(networkInterface.inetAddresses)) {
                if (!inetAddress.isLoopbackAddress && inetAddress.hostAddress?.contains(':') == false) {
                    return inetAddress.hostAddress
                }
            }
        }
        return null
    }

    private fun isSmbPortOpen(ip: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 445), 120)
                true
            }
        }.getOrDefault(false)
    }

    private fun reverseLookupName(ip: String): String? {
        return runCatching {
            val lookup = java.net.InetAddress.getByName(ip)
            val name = lookup.hostName
            if (name == ip) null else name
        }.getOrNull()
    }
}
