package skezza.smbsync.domain.smb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbBrowseResult
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbConnectionResult

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

        override suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult =
            SmbBrowseResult(shareName = request.shareName, directoryPath = request.directoryPath, shares = emptyList(), directories = emptyList())
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
