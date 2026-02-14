package skezza.smbsync.ui.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.discovery.DiscoveredSmbServer
import skezza.smbsync.data.discovery.SmbServerDiscoveryScanner
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbConnectionResult
import skezza.smbsync.data.smb.SmbShareRpcEnumerator
import skezza.smbsync.domain.discovery.DiscoverSmbServersUseCase
import skezza.smbsync.domain.smb.BrowseSmbDestinationUseCase
import skezza.smbsync.domain.smb.SmbBrowseResult
import skezza.smbsync.domain.smb.TestSmbConnectionUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelGuestTest {

    @Test
    fun openBrowseDestination_guestSuccessShowsShares() = runTest {
        val viewModel = buildViewModel(
            browseUseCase = BrowseSmbDestinationUseCase(
                smbClient = FakeSmbClient(shares = listOf("guest")),
                shareRpcEnumerator = FakeShareRpcEnumerator(),
            ),
        )

        viewModel.updateEditorField(ServerEditorField.HOST, "nas")
        viewModel.updateEditorField(ServerEditorField.BASE_PATH, "")

        viewModel.openBrowseDestination()
        advanceUntilIdle()

        assertTrue(viewModel.browseState.value.isVisible)
        assertEquals(listOf("guest"), viewModel.browseState.value.shares)
    }

    @Test
    fun openBrowseDestination_guestFailureShowsMessage() = runTest {
        val viewModel = buildViewModel(
            browseUseCase = BrowseSmbDestinationUseCase(
                smbClient = FakeSmbClient(error = IllegalStateException("boom")),
                shareRpcEnumerator = FakeShareRpcEnumerator(),
            ),
        )

        viewModel.updateEditorField(ServerEditorField.HOST, "nas")

        viewModel.openBrowseDestination()
        advanceUntilIdle()

        assertEquals("Guest access failed. Enter credentials to browse.", viewModel.message.value)
    }

    @Test
    fun discoverySelection_autoOpensBrowseDialog() = runTest {
        val viewModel = buildViewModel(
            browseUseCase = BrowseSmbDestinationUseCase(
                smbClient = FakeSmbClient(shares = listOf("guest")),
                shareRpcEnumerator = FakeShareRpcEnumerator(),
            ),
        )

        viewModel.setDiscoverySelection(DiscoveredSmbServer(host = "discovered.local", ipAddress = "192.168.1.42"))
        viewModel.loadServerForEdit(null)
        advanceUntilIdle()

        assertTrue(viewModel.browseState.value.isVisible)
        assertEquals(listOf("guest"), viewModel.browseState.value.shares)
    }

    private fun buildViewModel(browseUseCase: BrowseSmbDestinationUseCase): VaultViewModel {
        return VaultViewModel(
            serverRepository = FakeServerRepository(),
            credentialStore = FakeCredentialStore(),
            testSmbConnectionUseCase = TestSmbConnectionUseCase(
                serverRepository = FakeServerRepository(),
                credentialStore = FakeCredentialStore(),
                smbClient = FakeSmbClient(),
            ),
            discoverSmbServersUseCase = DiscoverSmbServersUseCase(
                scanner = object : SmbServerDiscoveryScanner {
                    override suspend fun discover(): List<DiscoveredSmbServer> = emptyList()
                },
            ),
            browseSmbDestinationUseCase = browseUseCase,
        )
    }

    private class FakeSmbClient(
        private val shares: List<String> = emptyList(),
        private val directories: List<String> = emptyList(),
        private val error: Throwable? = null,
    ) : SmbClient {
        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult = SmbConnectionResult(0)

        override suspend fun listShares(host: String, username: String, password: String): List<String> {
            error?.let { throw it }
            return shares
        }

        override suspend fun listDirectories(
            host: String,
            shareName: String,
            path: String,
            username: String,
            password: String,
        ): List<String> {
            error?.let { throw it }
            return directories
        }
    }

    private class FakeShareRpcEnumerator : SmbShareRpcEnumerator {
        override suspend fun listSharesViaRpc(host: String, username: String, password: String, domain: String): List<String> = emptyList()
    }

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
}
