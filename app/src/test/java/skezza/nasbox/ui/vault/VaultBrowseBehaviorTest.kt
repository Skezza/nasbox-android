package skezza.nasbox.ui.vault

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.discovery.DiscoveredSmbServer
import skezza.nasbox.data.discovery.SmbServerDiscoveryScanner
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbConnectionRequest
import skezza.nasbox.data.smb.SmbConnectionResult
import skezza.nasbox.domain.discovery.DiscoverSmbServersUseCase
import skezza.nasbox.domain.smb.BrowseSmbDestinationUseCase
import skezza.nasbox.domain.smb.TestSmbConnectionUseCase
import skezza.nasbox.data.smb.SmbShareRpcEnumerator

class VaultBrowseBehaviorTest {

    @Test
    fun openBrowseDestination_slashShareLoadsSharesInsteadOfDirectories() = runTest {
        val smbClient = TrackingSmbClient()
        val viewModel = newViewModel(smbClient)

        viewModel.updateEditorField(ServerEditorField.HOST, "192.168.1.122")
        viewModel.updateEditorField(ServerEditorField.USERNAME, "joe")
        viewModel.updateEditorField(ServerEditorField.PASSWORD, "secret")
        viewModel.updateEditorField(ServerEditorField.SHARE, "/")
        viewModel.updateEditorField(ServerEditorField.BASE_PATH, "/")

        viewModel.openBrowseDestination()
        advanceUntilIdle()

        assertEquals(1, smbClient.listSharesCalls)
        assertEquals(0, smbClient.listDirectoriesCalls)
    }

    @Test
    fun openBrowseDestination_namedShareLoadsDirectories() = runTest {
        val smbClient = TrackingSmbClient()
        val viewModel = newViewModel(smbClient)

        viewModel.updateEditorField(ServerEditorField.HOST, "192.168.1.122")
        viewModel.updateEditorField(ServerEditorField.USERNAME, "joe")
        viewModel.updateEditorField(ServerEditorField.PASSWORD, "secret")
        viewModel.updateEditorField(ServerEditorField.SHARE, "photos")
        viewModel.updateEditorField(ServerEditorField.BASE_PATH, "backup")

        viewModel.openBrowseDestination()
        advanceUntilIdle()

        assertEquals(0, smbClient.listSharesCalls)
        assertEquals(1, smbClient.listDirectoriesCalls)
    }

    private fun newViewModel(smbClient: TrackingSmbClient): VaultViewModel {
        return VaultViewModel(
            serverRepository = FakeServerRepository(),
            credentialStore = FakeCredentialStore(),
            testSmbConnectionUseCase = TestSmbConnectionUseCase(
                serverRepository = FakeServerRepository(),
                credentialStore = FakeCredentialStore(),
                smbClient = smbClient,
            ),
            discoverSmbServersUseCase = DiscoverSmbServersUseCase(
                scanner = object : SmbServerDiscoveryScanner {
                    override suspend fun discover(): List<DiscoveredSmbServer> = emptyList()
                },
            ),
            browseSmbDestinationUseCase = BrowseSmbDestinationUseCase(smbClient, emptyShareRpcEnumerator()),
        )
    }

    private fun emptyShareRpcEnumerator(): SmbShareRpcEnumerator = object : SmbShareRpcEnumerator {
        override suspend fun listSharesViaRpc(host: String, username: String, password: String, domain: String): List<String> = emptyList()
    }

    private class TrackingSmbClient : SmbClient {
        var listSharesCalls: Int = 0
        var listDirectoriesCalls: Int = 0

        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
            SmbConnectionResult(latencyMs = 1)

        override suspend fun listShares(host: String, username: String, password: String): List<String> {
            listSharesCalls += 1
            return listOf("photos")
        }

        override suspend fun listDirectories(
            host: String,
            shareName: String,
            path: String,
            username: String,
            password: String,
        ): List<String> {
            listDirectoriesCalls += 1
            return listOf("2025")
        }
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
