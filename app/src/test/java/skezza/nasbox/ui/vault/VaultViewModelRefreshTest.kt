package skezza.nasbox.ui.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelRefreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun refreshServerOnlineStatus_runsTestOnEachServer() = runTest {
        val serverA = baseServer(serverId = 1L, name = "Primary", alias = "alias-primary")
        val serverB = baseServer(serverId = 2L, name = "Secondary", alias = "alias-secondary")
        val repository = FakeServerRepository(listOf(serverA, serverB))
        val credentialStore = FakeCredentialStore(
            mapOf(
                "alias-primary" to "pw-primary",
                "alias-secondary" to "pw-secondary",
            ),
        )
        val smbClient = CountingSmbClient()
        val viewModel = VaultViewModel(
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
        )

        viewModel.refreshServerOnlineStatus()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), repository.updatedServerIds)
        assertEquals(2, smbClient.testCalls)
    }

    private fun baseServer(serverId: Long, name: String, alias: String) = ServerEntity(
        serverId = serverId,
        name = name,
        host = "$name.local",
        shareName = "share",
        basePath = "backup",
        domain = "",
        username = "user",
        credentialAlias = alias,
    )

    private class FakeServerRepository(
        private val servers: List<ServerEntity>,
    ) : ServerRepository {
        private val byId = servers.associateBy { it.serverId }.toMutableMap()
        val updatedServerIds = mutableListOf<Long>()

        override fun observeServers(): Flow<List<ServerEntity>> = flowOf(servers)

        override suspend fun getServer(serverId: Long): ServerEntity? = byId[serverId]

        override suspend fun createServer(server: ServerEntity): Long = server.serverId

        override suspend fun updateServer(server: ServerEntity) {
            updatedServerIds += server.serverId
            byId[server.serverId] = server
        }

        override suspend fun deleteServer(serverId: Long) {
            byId.remove(serverId)
        }
    }

    private class FakeCredentialStore(
        private val passwords: Map<String, String>,
    ) : CredentialStore {
        override suspend fun savePassword(alias: String, password: String) = Unit

        override suspend fun loadPassword(alias: String): String? = passwords[alias]

        override suspend fun deletePassword(alias: String) = Unit
    }

    private class CountingSmbClient : SmbClient {
        var testCalls = 0

        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult {
            testCalls += 1
            return SmbConnectionResult(latencyMs = 1)
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
