package skezza.smbsync.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.MutableCreationExtras
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbBrowseResult
import skezza.smbsync.data.smb.SmbConnectionResult
import skezza.smbsync.data.discovery.SmbServerDiscoveryScanner
import skezza.smbsync.domain.discovery.DiscoverSmbServersUseCase
import skezza.smbsync.domain.smb.BrowseSmbPathUseCase
import skezza.smbsync.domain.smb.TestSmbConnectionUseCase

class VaultViewModelFactoryTest {

    @Test
    fun factoryCreateWithClassAndExtras_returnsVaultViewModel() {
        val factory = VaultViewModel.factory(FakeServerRepository(), FakeCredentialStore(), fakeUseCase(), fakeDiscoveryUseCase(), fakeBrowseUseCase())

        val vmFromClass = factory.create(VaultViewModel::class.java)
        val vmFromExtras = factory.create(VaultViewModel::class.java, MutableCreationExtras())

        assertTrue(vmFromClass is VaultViewModel)
        assertTrue(vmFromExtras is VaultViewModel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun factoryRejectsUnknownViewModelClass() {
        val factory = VaultViewModel.factory(FakeServerRepository(), FakeCredentialStore(), fakeUseCase(), fakeDiscoveryUseCase(), fakeBrowseUseCase())
        factory.create(UnknownViewModel::class.java)
    }



    private fun fakeDiscoveryUseCase(): DiscoverSmbServersUseCase = DiscoverSmbServersUseCase(
        scanner = object : SmbServerDiscoveryScanner {
            override suspend fun discover() = emptyList<skezza.smbsync.data.discovery.DiscoveredSmbServer>()
        },
    )

    private fun fakeUseCase(): TestSmbConnectionUseCase = TestSmbConnectionUseCase(
        serverRepository = FakeServerRepository(),
        credentialStore = FakeCredentialStore(),
        smbClient = object : SmbClient {
            override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
                SmbConnectionResult(latencyMs = 1)

            override suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult =
                SmbBrowseResult(currentPath = "")
        },
    )

    private fun fakeBrowseUseCase(): BrowseSmbPathUseCase = BrowseSmbPathUseCase(
        smbClient = object : SmbClient {
            override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
                SmbConnectionResult(latencyMs = 1)

            override suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult =
                SmbBrowseResult(currentPath = "")
        },
    )

    private class FakeServerRepository : ServerRepository {
        override fun observeServers(): Flow<List<ServerEntity>> = flowOf(emptyList())
        override suspend fun getServer(serverId: Long): ServerEntity? = null
        override suspend fun createServer(server: ServerEntity): Long = 1L
        override suspend fun updateServer(server: ServerEntity) = Unit
        override suspend fun deleteServer(serverId: Long) = Unit
    }

    private class FakeCredentialStore : CredentialStore {
        override suspend fun savePassword(alias: String, password: String) = Unit
        override suspend fun loadPassword(alias: String): String? = null
        override suspend fun deletePassword(alias: String) = Unit
    }

    private class UnknownViewModel : ViewModel()
}
