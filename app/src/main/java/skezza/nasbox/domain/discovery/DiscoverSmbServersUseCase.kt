package skezza.nasbox.domain.discovery

import kotlinx.coroutines.flow.Flow
import skezza.nasbox.data.discovery.DiscoveredSmbServer
import skezza.nasbox.data.discovery.SmbServerDiscoveryScanner

class DiscoverSmbServersUseCase(
    private val scanner: SmbServerDiscoveryScanner,
) {
    operator fun invoke(): Flow<List<DiscoveredSmbServer>> = scanner.discover()
}
