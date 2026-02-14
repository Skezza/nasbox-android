package skezza.smbsync.domain.smb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbConnectionResult

class BrowseSmbServerUseCaseTest {

    @Test
    fun listShares_returnsEntriesOnSuccess() = runTest {
        val useCase = BrowseSmbServerUseCase(
            smbClient = FakeSmbClient(shares = listOf("media", "backup")),
        )

        val result = useCase.listShares(
            host = "smb://nas.local",
            username = "demo",
            password = "pass",
        )

        assertTrue(result.success)
        assertEquals(listOf("media", "backup"), result.entries)
    }

    @Test
    fun listDirectories_requiresShare() = runTest {
        val useCase = BrowseSmbServerUseCase(smbClient = FakeSmbClient())

        val result = useCase.listDirectories(
            host = "nas.local",
            shareName = "",
            directoryPath = "",
            username = "demo",
            password = "pass",
        )

        assertFalse(result.success)
        assertTrue(result.message?.contains("Select a share") == true)
    }

    private class FakeSmbClient(
        private val shares: List<String> = emptyList(),
        private val folders: List<String> = emptyList(),
    ) : SmbClient {
        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
            SmbConnectionResult(latencyMs = 1)

        override suspend fun listShares(request: SmbConnectionRequest): List<String> = shares

        override suspend fun listDirectories(request: SmbConnectionRequest, path: String): List<String> = folders
    }
}
