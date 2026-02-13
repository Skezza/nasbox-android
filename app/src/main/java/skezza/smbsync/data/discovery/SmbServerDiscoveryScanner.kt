package skezza.smbsync.data.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
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
                                host = reverseLookupName(ip) ?: resolveNetBiosHostname(ip) ?: ip,
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
                mdnsServiceTypes().flatMap { serviceType ->
                    jmDns.list(serviceType, 1200).flatMap { serviceInfo ->
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
            }
        }.getOrDefault(emptyList())
            .groupBy { it.ipAddress }
            .mapNotNull { (ip, entries) ->
                val preferred = entries.firstOrNull { it.host != ip } ?: entries.firstOrNull()
                preferred?.copy(ipAddress = ip)
            }
    }

    private fun mdnsServiceTypes(): List<String> = listOf(
        "_smb._tcp.local.",
        "_workstation._tcp.local.",
        "_device-info._tcp.local.",
    )

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


    private fun resolveNetBiosHostname(ip: String): String? {
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val request = ByteArray(50)
                val transactionId = (System.currentTimeMillis() and 0xFFFF).toInt()
                request[0] = (transactionId shr 8).toByte()
                request[1] = transactionId.toByte()
                request[2] = 0x00
                request[3] = 0x00
                request[4] = 0x00
                request[5] = 0x01
                request[6] = 0x00
                request[7] = 0x00
                request[8] = 0x00
                request[9] = 0x00
                request[10] = 0x00
                request[11] = 0x00
                request[12] = 0x20

                var idx = 13
                request[idx++] = 0x43
                request[idx++] = 0x4B
                repeat(15) {
                    request[idx++] = 0x41
                }
                request[idx++] = 0x41
                request[idx++] = 0x41
                request[idx++] = 0x00
                request[idx++] = 0x00
                request[idx++] = 0x21
                request[idx++] = 0x00
                request[idx] = 0x01

                val packet = DatagramPacket(request, request.size, InetAddress.getByName(ip), 137)
                socket.send(packet)

                val response = ByteArray(1024)
                val responsePacket = DatagramPacket(response, response.size)
                socket.receive(responsePacket)

                parseNetBiosResponse(responsePacket.data, responsePacket.length)
            }
        }.getOrNull()
    }

    private fun parseNetBiosResponse(data: ByteArray, length: Int): String? {
        if (length < 57) return null
        val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (answerCount <= 0) return null

        // Walk past question section (fixed-size for our query) and answer header.
        var offset = 12
        while (offset < length && data[offset].toInt() != 0) {
            offset += (data[offset].toInt() and 0xFF) + 1
        }
        offset += 5
        if (offset + 12 >= length) return null

        // Name pointer + type + class + ttl + rdlength
        offset += 10
        val rdLength = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        if (offset + rdLength > length || rdLength < 1) return null

        val nameCount = data[offset].toInt() and 0xFF
        offset += 1

        var best: String? = null
        repeat(nameCount) {
            if (offset + 18 > length) return@repeat
            val rawName = data.copyOfRange(offset, offset + 15)
                .toString(Charsets.US_ASCII)
                .trim()
            val suffix = data[offset + 15].toInt() and 0xFF
            if (rawName.isNotBlank()) {
                if (suffix == 0x20) {
                    best = rawName
                } else if (best == null && suffix == 0x00) {
                    best = rawName
                }
            }
            offset += 18
        }

        return best?.lowercase()?.let { if (it.endsWith(".local")) it else "$it.local" }
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
