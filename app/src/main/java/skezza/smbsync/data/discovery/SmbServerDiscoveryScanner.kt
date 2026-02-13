package skezza.smbsync.data.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import javax.jmdns.JmDNS
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

class AndroidSmbServerDiscoveryScanner : SmbServerDiscoveryScanner {

    override suspend fun discover(): List<DiscoveredSmbServer> = withContext(Dispatchers.IO) {
        val local = localIPv4Address()
        val subnetTargets = subnetTargets(local)
        val reachableByIp = scanTargets(subnetTargets)
        val mdnsDiscovered = discoverViaMdns(local)

        val merged = mergeDiscoveryResults(reachableByIp, mdnsDiscovered)
        if (merged.isNotEmpty()) {
            return@withContext merged
        }

        probeCommonLocalHostnames()
    }

    private fun mergeDiscoveryResults(
        ipResults: List<DiscoveredSmbServer>,
        mdnsResults: List<DiscoveredSmbServer>,
    ): List<DiscoveredSmbServer> {
        if (ipResults.isEmpty()) return mdnsResults.sortedBy { it.host }

        val mdnsByIp = mdnsResults.associateBy { it.ipAddress }
        return ipResults.map { server ->
            val mdns = mdnsByIp[server.ipAddress]
            if (mdns != null && (server.host == server.ipAddress || server.host.endsWith(".local", ignoreCase = true))) {
                server.copy(host = mdns.host)
            } else {
                server
            }
        }.distinctBy { it.ipAddress }
            .sortedBy { it.host }
    }

    private suspend fun scanTargets(targets: List<String>): List<DiscoveredSmbServer> {
        if (targets.isEmpty()) return emptyList()
        val localIp = localIPv4Address()?.hostAddress
        val semaphore = Semaphore(16)

        return coroutineScope {
            targets.map { ip ->
                async {
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

    private fun subnetTargets(local: Inet4Address?): List<String> {
        local ?: return emptyList()
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val ifaceAddress = interfaces
            .flatMap { networkInterface -> networkInterface.interfaceAddresses.orEmpty() }
            .firstOrNull { it.address == local }

        val prefixLength = ifaceAddress?.networkPrefixLength?.toInt() ?: 24
        val normalizedPrefixLength = prefixLength.coerceIn(16, 30)

        val effectivePrefixLength = maxOf(normalizedPrefixLength, 24)
        val mask = if (effectivePrefixLength == 32) -1 else -1 shl (32 - effectivePrefixLength)

        val localInt = ipv4ToInt(local)
        val networkInt = localInt and mask
        val hostCount = (1 shl (32 - effectivePrefixLength)) - 2
        if (hostCount <= 0) return emptyList()

        return (1..hostCount).map { offset ->
            intToIpv4(networkInt + offset)
        }
    }

    private fun localIPv4Address(): Inet4Address? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            for (inetAddress in Collections.list(networkInterface.inetAddresses)) {
                if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                    return inetAddress
                }
            }
        }
        return null
    }

    private fun discoverViaMdns(local: Inet4Address?): List<DiscoveredSmbServer> {
        local ?: return emptyList()
        return runCatching {
            JmDNS.create(local).use { jmDns ->
                jmDns.list("_smb._tcp.local.", 1200)
                    .flatMap { serviceInfo ->
                        serviceInfo.hostAddresses.orEmpty().mapNotNull { ip ->
                            val normalizedIp = ip.trim().removePrefix("/")
                            if (normalizedIp.isBlank()) {
                                null
                            } else {
                                val resolvedName = serviceInfo.name.ifBlank { normalizedIp }
                                DiscoveredSmbServer(
                                    host = if (resolvedName.endsWith(".local", ignoreCase = true)) resolvedName else "$resolvedName.local",
                                    ipAddress = normalizedIp,
                                )
                            }
                        }
                    }
            }
        }.getOrDefault(emptyList())
            .distinctBy { it.ipAddress }
    }

    private suspend fun probeCommonLocalHostnames(): List<DiscoveredSmbServer> = coroutineScope {
        commonHostnameCandidates().map { host ->
            async {
                if (!isSmbPortOpen(host)) return@async null
                val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull() ?: host
                DiscoveredSmbServer(host = host, ipAddress = ip)
            }
        }.awaitAll().filterNotNull().distinctBy { it.host }
    }

    private fun commonHostnameCandidates(): List<String> {
        return listOf(
            "samba.local",
            "nas.local",
            "fileserver.local",
            "storage.local",
            "homeserver.local",
            "quanta.local",
        )
    }

    private fun isSmbPortOpen(hostOrIp: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostOrIp, 445), 350)
                true
            }
        }.getOrDefault(false)
    }

    private fun reverseLookupName(ip: String): String? {
        return runCatching {
            val lookup = InetAddress.getByName(ip)
            val name = lookup.hostName
            if (name == ip) null else name
        }.getOrNull()
    }

    private fun ipv4ToInt(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    private fun intToIpv4(value: Int): String {
        val b1 = value ushr 24 and 0xFF
        val b2 = value ushr 16 and 0xFF
        val b3 = value ushr 8 and 0xFF
        val b4 = value and 0xFF
        return "$b1.$b2.$b3.$b4"
    }
}
