package skezza.nasbox.domain.smb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbConnectionRequest
import skezza.nasbox.data.smb.SmbConnectionResult

class TestSmbConnectionUseCaseTest {

    @Test
    fun testPersistedServer_successUpdatesMetadata() = runTest {
        val repo = FakeServerRepository(
            ServerEntity(
                serverId = 7,
                name = "NAS",
                host = "host",
                shareName = "share",
                basePath = "base",
                domain = "",
                username = "user",
                credentialAlias = "alias",
            ),
        )
        val useCase = TestSmbConnectionUseCase(
            serverRepository = repo,
            credentialStore = FakeCredentialStore(password = "secret"),
            smbClient = FakeSmbClient(result = SmbConnectionResult(latencyMs = 42)),
            nowEpochMs = { 123L },
        )

        val result = useCase.testPersistedServer(7)

        assertTrue(result.success)
        val updated = repo.server
        assertEquals("SUCCESS", updated?.lastTestStatus)
        assertTrue(result.message.contains("host/share"))
        assertEquals(42L, updated?.lastTestLatencyMs)
        assertEquals(123L, updated?.lastTestTimestampEpochMs)
    }

    @Test
    fun testPersistedServer_authFailureMapsCategoryAndStoresFailure() = runTest {
        val repo = FakeServerRepository(
            ServerEntity(
                serverId = 7,
                name = "NAS",
                host = "smb://host/share",
                shareName = "",
                basePath = "base",
                domain = "",
                username = "user",
                credentialAlias = "alias",
            ),
        )
        val useCase = TestSmbConnectionUseCase(
            serverRepository = repo,
            credentialStore = FakeCredentialStore(password = "secret"),
            smbClient = FakeSmbClient(error = IllegalStateException("STATUS_LOGON_FAILURE")),
            nowEpochMs = { 123L },
        )

        val result = useCase.testPersistedServer(7)

        assertFalse(result.success)
        assertEquals(SmbErrorCategory.AUTHENTICATION_FAILED, result.category)
        assertEquals("FAILED", repo.server?.lastTestStatus)
        assertEquals("AUTHENTICATION_FAILED", repo.server?.lastTestErrorCategory)
    }

    private class FakeSmbClient(
        private val result: SmbConnectionResult? = null,
        private val error: Throwable? = null,
    ) : SmbClient {
        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult {
            error?.let { throw it }
            return checkNotNull(result)
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

    private class FakeServerRepository(
        var server: ServerEntity?,
    ) : ServerRepository {
        override fun observeServers(): Flow<List<ServerEntity>> = flowOf(listOfNotNull(server))

        override suspend fun getServer(serverId: Long): ServerEntity? = server?.takeIf { it.serverId == serverId }

        override suspend fun createServer(server: ServerEntity): Long = 1L

        override suspend fun updateServer(server: ServerEntity) {
            this.server = server
        }

        override suspend fun deleteServer(serverId: Long) = Unit
    }

    private class FakeCredentialStore(
        private val password: String?,
    ) : CredentialStore {
        override suspend fun savePassword(alias: String, password: String) = Unit
        override suspend fun loadPassword(alias: String): String? = password
        override suspend fun deletePassword(alias: String) = Unit
    }
}
