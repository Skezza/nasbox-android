package skezza.smbsync.domain.discovery

import skezza.smbsync.data.discovery.DiscoveredSmbServer
import skezza.smbsync.data.discovery.SmbServerDiscoveryScanner

class DiscoverSmbServersUseCase(
    private val scanner: SmbServerDiscoveryScanner,
) {
    suspend operator fun invoke(): List<DiscoveredSmbServer> = scanner.discover()
}
