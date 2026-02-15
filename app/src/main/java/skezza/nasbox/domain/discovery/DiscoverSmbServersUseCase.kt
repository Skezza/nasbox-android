package skezza.nasbox.domain.discovery

import skezza.nasbox.data.discovery.DiscoveredSmbServer
import skezza.nasbox.data.discovery.SmbServerDiscoveryScanner

class DiscoverSmbServersUseCase(
    private val scanner: SmbServerDiscoveryScanner,
) {
    suspend operator fun invoke(): List<DiscoveredSmbServer> = scanner.discover()
}
