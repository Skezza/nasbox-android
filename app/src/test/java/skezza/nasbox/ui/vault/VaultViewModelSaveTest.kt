package skezza.nasbox.ui.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import skezza.nasbox.MainDispatcherRule
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.discovery.DiscoveredSmbServer
import skezza.nasbox.data.discovery.SmbServerDiscoveryScanner
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbConnectionRequest
import skezza.nasbox.data.smb.SmbConnectionResult
import skezza.nasbox.data.smb.SmbShareRpcEnumerator
import skezza.nasbox.domain.discovery.DiscoverSmbServersUseCase
import skezza.nasbox.domain.smb.BrowseSmbDestinationUseCase
import skezza.nasbox.domain.smb.TestSmbConnectionUseCase
import skezza.nasbox.ui.vault.ServerEditorField

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelSaveTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun saveServer_persistsLastSuccessData() = runTest {
        val repository = RecordingServerRepository()
        val credentialStore = FakeCredentialStore()
        val smbClient = SuccessSmbClient(latencyMs = 123)
        val viewModel = buildViewModel(repository, credentialStore, smbClient, nowEpoch = 1_000L)

        viewModel.updateEditorField(ServerEditorField.NAME, " Work NAS ")
        viewModel.updateEditorField(ServerEditorField.HOST, "nas.home.local ")
        viewModel.updateEditorField(ServerEditorField.SHARE, "/share/ ")
        viewModel.updateEditorField(ServerEditorField.BASE_PATH, " backup ")
        viewModel.updateEditorField(ServerEditorField.DOMAIN, " WORKGROUP ")
        viewModel.updateEditorField(ServerEditorField.USERNAME, " admin ")
        viewModel.updateEditorField(ServerEditorField.PASSWORD, "secret")

        viewModel.saveServer {}
        advanceUntilIdle()

        val created = repository.createdServers.single()
        assertEquals("Work NAS", created.name)
        assertEquals("nas.home.local", created.host)
        assertEquals("share", created.shareName)
        assertEquals("backup", created.basePath)
        assertEquals("WORKGROUP", created.domain)
        assertEquals("admin", created.username)
        assertEquals("SUCCESS", created.lastTestStatus)
        assertEquals(1_000L, created.lastTestTimestampEpochMs)
        assertEquals(123L, created.lastTestLatencyMs)
        assertEquals("NONE", created.lastTestErrorCategory)
        assertNull(created.lastTestErrorMessage)
    }

    @Test
    fun saveServer_persistsFailureData() = runTest {
        val repository = RecordingServerRepository()
        val credentialStore = FakeCredentialStore()
        val smbClient = FailedSmbClient()
        val viewModel = buildViewModel(repository, credentialStore, smbClient, nowEpoch = 2_000L)

        viewModel.updateEditorField(ServerEditorField.NAME, " NAS ")
        viewModel.updateEditorField(ServerEditorField.HOST, "nas.local")
        viewModel.updateEditorField(ServerEditorField.SHARE, "share")
        viewModel.updateEditorField(ServerEditorField.BASE_PATH, "root")
        viewModel.updateEditorField(ServerEditorField.DOMAIN, "WORKGROUP")
        viewModel.updateEditorField(ServerEditorField.USERNAME, "user")
        viewModel.updateEditorField(ServerEditorField.PASSWORD, "password")

        viewModel.saveServer {}
        advanceUntilIdle()

        val created = repository.createdServers.single()
        assertEquals("FAILED", created.lastTestStatus)
        assertEquals(2_000L, created.lastTestTimestampEpochMs)
        assertEquals("REMOTE_PERMISSION_DENIED", created.lastTestErrorCategory)
        assertEquals("Remote permission denied.", created.lastTestErrorMessage)
        assertNull(created.lastTestLatencyMs)
    }

    private fun buildViewModel(
        repository: RecordingServerRepository,
        credentialStore: CredentialStore,
        smbClient: SmbClient,
        nowEpoch: Long,
    ): VaultViewModel {
        return VaultViewModel(
            serverRepository = repository,
            credentialStore = credentialStore,
            testSmbConnectionUseCase = TestSmbConnectionUseCase(
                serverRepository = repository,
                credentialStore = credentialStore,
                smbClient = smbClient,
            ),
            discoverSmbServersUseCase = DiscoverSmbServersUseCase(
                scanner = object : SmbServerDiscoveryScanner {
                    override fun discover(): Flow<List<DiscoveredSmbServer>> = flowOf(emptyList())
                },
            ),
            browseSmbDestinationUseCase = BrowseSmbDestinationUseCase(
                smbClient = smbClient,
                shareRpcEnumerator = emptyShareRpcEnumerator(),
            ),
            nowEpochMs = { nowEpoch },
        )
    }

    private class RecordingServerRepository : ServerRepository {
        val createdServers = mutableListOf<ServerEntity>()

        override fun observeServers(): Flow<List<ServerEntity>> = flowOf(emptyList())

        override suspend fun getServer(serverId: Long): ServerEntity? = null

        override suspend fun createServer(server: ServerEntity): Long {
            createdServers += server
            return createdServers.size.toLong()
        }

        override suspend fun updateServer(server: ServerEntity) = Unit

        override suspend fun deleteServer(serverId: Long) = Unit
    }

    private class FakeCredentialStore : CredentialStore {
        override suspend fun savePassword(alias: String, password: String) = Unit
        override suspend fun loadPassword(alias: String): String? = null
        override suspend fun deletePassword(alias: String) = Unit
    }

    private class SuccessSmbClient(private val latencyMs: Long) : SmbClient {
        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
            SmbConnectionResult(latencyMs = latencyMs)

        override suspend fun listShares(host: String, username: String, password: String): List<String> = emptyList()

        override suspend fun listDirectories(
            host: String,
            shareName: String,
            path: String,
            username: String,
            password: String,
        ): List<String> = emptyList()
    }

    private class FailedSmbClient : SmbClient {
        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult {
            throw IllegalStateException("Access denied")
        }

        override suspend fun listShares(host: String, username: String, password: String): List<String> = emptyList()

        override suspend fun listDirectories(
            host: String,
            shareName: String,
            path: String,
            username: String,
            password: String,
        ): List<String> = emptyList()
    }

    private fun emptyShareRpcEnumerator(): SmbShareRpcEnumerator = object : SmbShareRpcEnumerator {
        override suspend fun listSharesViaRpc(host: String, username: String, password: String, domain: String): List<String> =
            emptyList()
    }
}
