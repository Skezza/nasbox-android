package skezza.nasbox.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import javax.jmdns.JmDNS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class DiscoveredSmbServer(
    val host: String,
    val ipAddress: String,
)

interface SmbServerDiscoveryScanner {
    fun discover(): Flow<List<DiscoveredSmbServer>>
}

class AndroidSmbServerDiscoveryScanner(
    private val context: Context,
) : SmbServerDiscoveryScanner {

    override fun discover(): Flow<List<DiscoveredSmbServer>> = channelFlow {
        val mergedByIp = linkedMapOf<String, DiscoveredSmbServer>()
        val pendingIpOnlyByIp = mutableMapOf<String, DiscoveredSmbServer>()
        val pendingIpOnlyEmitJobs = mutableMapOf<String, Job>()
        val stateLock = Mutex()
        var lastEmitted: List<DiscoveredSmbServer>? = null

        suspend fun emitSnapshotIfChanged(force: Boolean = false) {
            val snapshotToEmit = stateLock.withLock {
                val snapshot = mergedByIp.values.sortedBy { it.host }
                if (!force && snapshot == lastEmitted) {
                    null
                } else {
                    lastEmitted = snapshot
                    snapshot
                }
            } ?: return
            send(snapshotToEmit)
        }

        suspend fun emitPendingIpOnly(ipAddress: String) {
            val changed = stateLock.withLock {
                pendingIpOnlyEmitJobs.remove(ipAddress)
                val pending = pendingIpOnlyByIp.remove(ipAddress) ?: return@withLock false
                mergeEntry(mergedByIp, pending)
            }
            if (changed) {
                emitSnapshotIfChanged()
            }
        }

        val localIpv4 = localIPv4Address()
        val localIp = localIpv4?.hostAddress
        val subnetTargets = subnetTargets(localIpv4)
        val mdnsBindings = localMdnsBindingAddresses()
        merge(
            scanTargets(subnetTargets, localIp),
            discoverViaMdns(mdnsBindings),
            probeCommonLocalHostnamesFlow(),
        ).collect { candidate ->
            val isIpOnly = candidate.host.equals(candidate.ipAddress, ignoreCase = true)
            if (isIpOnly) {
                val queued = stateLock.withLock {
                    if (mergedByIp.containsKey(candidate.ipAddress)) {
                        false
                    } else {
                        pendingIpOnlyByIp[candidate.ipAddress] = candidate
                        pendingIpOnlyEmitJobs.remove(candidate.ipAddress)?.cancel()
                        pendingIpOnlyEmitJobs[candidate.ipAddress] = launch {
                            delay(IP_ONLY_HOSTNAME_GRACE_MS)
                            emitPendingIpOnly(candidate.ipAddress)
                        }
                        true
                    }
                }
                if (!queued) return@collect
                return@collect
            }

            val changed = stateLock.withLock {
                pendingIpOnlyEmitJobs.remove(candidate.ipAddress)?.cancel()
                pendingIpOnlyByIp.remove(candidate.ipAddress)
                mergeEntry(mergedByIp, candidate)
            }
            if (changed) {
                emitSnapshotIfChanged()
            }
        }

        val jobsToCancel = stateLock.withLock {
            pendingIpOnlyEmitJobs.values.toList().also { pendingIpOnlyEmitJobs.clear() }
        }
        jobsToCancel.forEach { it.cancel() }
        val mergedPending = stateLock.withLock {
            var changed = false
            pendingIpOnlyByIp.values.forEach { candidate ->
                if (mergeEntry(mergedByIp, candidate)) changed = true
            }
            pendingIpOnlyByIp.clear()
            changed
        }
        if (mergedPending) {
            emitSnapshotIfChanged()
        }

        // Always emit final snapshot, including empty results.
        emitSnapshotIfChanged(force = true)
    }.flowOn(Dispatchers.IO)

    private fun mergeEntry(
        mergedByIp: MutableMap<String, DiscoveredSmbServer>,
        candidate: DiscoveredSmbServer,
    ): Boolean {
        val existing = mergedByIp[candidate.ipAddress]
        if (existing == null) {
            mergedByIp[candidate.ipAddress] = candidate
            return true
        }
        // Keep first-seen result for a stable row; avoid late host upgrades for the same IP.
        return false
    }

    private fun scanTargets(
        targets: List<String>,
        localIp: String?,
    ): Flow<DiscoveredSmbServer> = channelFlow {
        if (targets.isEmpty()) return@channelFlow
        val semaphore = Semaphore(16)
        targets.forEach { ip ->
            launch {
                if (ip == localIp) return@launch
                semaphore.withPermit {
                    if (isSmbPortOpen(ip)) {
                        send(
                            DiscoveredSmbServer(
                                host = reverseLookupName(ip) ?: resolveNetBiosHostname(ip) ?: ip,
                                ipAddress = ip,
                            ),
                        )
                    }
                }
            }
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

    private fun localMdnsBindingAddresses(): List<InetAddress> {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val bindings = mutableListOf<InetAddress>()
        interfaces.forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            val addresses = Collections.list(networkInterface.inetAddresses)
                .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            val ipv4 = addresses.firstOrNull { it is Inet4Address }
            val ipv6 = addresses.firstOrNull { it is Inet6Address }
            if (ipv4 != null) bindings += ipv4
            if (ipv6 != null) bindings += ipv6
        }
        return bindings.distinctBy { address ->
            address.hostAddress?.substringBefore('%')?.trim()?.removePrefix("/").orEmpty()
        }
    }

    private fun discoverViaMdns(bindings: List<InetAddress>): Flow<DiscoveredSmbServer> = flow {
        if (bindings.isEmpty()) return@flow
        withMulticastLock {
            runCatching {
                val discoveredByIp = linkedMapOf<String, DiscoveredSmbServer>()
                bindings.forEach { binding ->
                    runCatching {
                        JmDNS.create(binding).use { jmDns ->
                            coroutineScope {
                                val serviceInfoSets = mdnsServiceTypes().map { serviceType ->
                                    async {
                                        runCatching { jmDns.list(serviceType, 1600) }.getOrDefault(emptyArray())
                                    }
                                }.awaitAll()
                                serviceInfoSets.forEach { serviceInfos ->
                                    serviceInfos.forEach { serviceInfo ->
                                        val serviceIps = linkedSetOf<String>()
                                        serviceInfo.hostAddresses.orEmpty().forEach { ip ->
                                            val normalized = ip.trim().removePrefix("/")
                                            if (normalized.isNotBlank()) {
                                                serviceIps += normalized
                                            }
                                        }
                                        serviceInfo.inetAddresses.orEmpty().forEach { address ->
                                            val normalized = address.hostAddress?.trim()?.removePrefix("/").orEmpty()
                                            if (normalized.isNotBlank()) {
                                                serviceIps += normalized
                                            }
                                        }
                                        serviceIps.forEach { ip ->
                                            val normalizedIp = ip.trim().removePrefix("/")
                                            if (normalizedIp.isBlank()) return@forEach
                                            val resolvedName = serviceInfo.name.ifBlank { normalizedIp }
                                            val candidate = DiscoveredSmbServer(
                                                host = if (resolvedName.endsWith(".local", ignoreCase = true)) resolvedName else "$resolvedName.local",
                                                ipAddress = normalizedIp,
                                            )
                                            if (mergeEntry(discoveredByIp, candidate)) {
                                                emit(discoveredByIp.getValue(normalizedIp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun probeCommonLocalHostnamesFlow(): Flow<DiscoveredSmbServer> = flow {
        probeCommonLocalHostnames().forEach { emit(it) }
    }

    private suspend fun <T> withMulticastLock(block: suspend () -> T): T {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifiManager?.createMulticastLock("nasbox-mdns-lock")
        lock?.setReferenceCounted(false)
        lock?.acquire()
        return try {
            block()
        } finally {
            runCatching { lock?.release() }
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
                val resolved = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
                resolved.mapNotNull { address ->
                    val ip = address.hostAddress?.trim()?.removePrefix("/").orEmpty()
                    if (ip.isBlank()) {
                        null
                    } else if (isSmbPortOpen(ip)) {
                        DiscoveredSmbServer(host = host, ipAddress = ip)
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().flatten().distinctBy { it.ipAddress }
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

    companion object {
        private const val IP_ONLY_HOSTNAME_GRACE_MS = 1_800L
    }
}
