package skezza.smbsync

import android.content.Context
import skezza.smbsync.data.db.DatabaseProvider
import skezza.smbsync.data.discovery.AndroidSmbServerDiscoveryScanner
import skezza.smbsync.data.discovery.SmbServerDiscoveryScanner
import skezza.smbsync.data.repository.DefaultServerRepository
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.AndroidKeystoreCredentialStore
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbjClient
import skezza.smbsync.domain.discovery.DiscoverSmbServersUseCase
import skezza.smbsync.domain.smb.BrowseSmbPathUseCase
import skezza.smbsync.domain.smb.TestSmbConnectionUseCase

class AppContainer(context: Context) {
    private val database = DatabaseProvider.get(context)

    val serverRepository: ServerRepository = DefaultServerRepository(database.serverDao())
    val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(context)
    private val smbClient: SmbClient = SmbjClient()
    private val smbServerDiscoveryScanner: SmbServerDiscoveryScanner = AndroidSmbServerDiscoveryScanner(context)

    val testSmbConnectionUseCase: TestSmbConnectionUseCase = TestSmbConnectionUseCase(
        serverRepository = serverRepository,
        credentialStore = credentialStore,
        smbClient = smbClient,
    )

    val discoverSmbServersUseCase: DiscoverSmbServersUseCase = DiscoverSmbServersUseCase(
        scanner = smbServerDiscoveryScanner,
    )

    val browseSmbPathUseCase: BrowseSmbPathUseCase = BrowseSmbPathUseCase(
        smbClient = smbClient,
    )
}
