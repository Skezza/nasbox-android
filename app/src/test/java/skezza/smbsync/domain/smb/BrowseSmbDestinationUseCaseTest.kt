package skezza.smbsync.domain.smb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbConnectionResult

class BrowseSmbDestinationUseCaseTest {

    @Test
    fun listShares_returnsSortedShares() = runTest {
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = FakeSmbClient(shares = listOf("zeta", "alpha")),
        )

        val result = useCase.listShares("smb://nas.local/photos", "user", "pw")

        assertTrue(result is SmbBrowseResult.Success)
        assertEquals(listOf("alpha", "zeta"), (result as SmbBrowseResult.Success).data)
    }

    @Test
    fun listDirectories_requiresShare() = runTest {
        val useCase = BrowseSmbDestinationUseCase(FakeSmbClient())

        val result = useCase.listDirectories("nas.local", "", "", "user", "pw")

        assertTrue(result is SmbBrowseResult.Failure)
        assertEquals("Select a share before browsing folders.", (result as SmbBrowseResult.Failure).message)
    }

    @Test
    fun normalizePath_trimsAndNormalizesSeparators() {
        val useCase = BrowseSmbDestinationUseCase(FakeSmbClient())

        assertEquals("photos/2025", useCase.normalizePath("\\photos//2025/"))
    }

    private class FakeSmbClient(
        private val shares: List<String> = emptyList(),
        private val directories: List<String> = emptyList(),
    ) : SmbClient {
        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
            SmbConnectionResult(latencyMs = 1)

        override suspend fun listShares(host: String, username: String, password: String): List<String> = shares

        override suspend fun listDirectories(
            host: String,
            shareName: String,
            path: String,
            username: String,
            password: String,
        ): List<String> = directories
    }
}
