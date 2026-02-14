package skezza.smbsync.domain.smb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbConnectionResult
import skezza.smbsync.data.smb.SmbShareRpcEnumerator

class BrowseSmbDestinationUseCaseTest {

    @Test
    fun listShares_returnsSortedShares() = runTest {
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = FakeSmbClient(shares = listOf("zeta", "alpha")),
            shareRpcEnumerator = FakeShareRpcEnumerator(),
        )

        val result = useCase.listShares("smb://nas.local/photos", "user", "pw", "")

        assertTrue(result is SmbBrowseResult.Success)
        assertEquals(listOf("alpha", "zeta"), (result as SmbBrowseResult.Success).data)
    }

    @Test
    fun listDirectories_requiresShare() = runTest {
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = FakeSmbClient(),
            shareRpcEnumerator = FakeShareRpcEnumerator(),
        )

        val result = useCase.listDirectories("nas.local", "", "", "user", "pw")

        assertTrue(result is SmbBrowseResult.Failure)
        assertEquals("Select a share before browsing folders.", (result as SmbBrowseResult.Failure).message)
    }

    @Test
    fun normalizePath_trimsAndNormalizesSeparators() {
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = FakeSmbClient(),
            shareRpcEnumerator = FakeShareRpcEnumerator(),
        )

        assertEquals("photos/2025", useCase.normalizePath("\\photos//2025/"))
    }

    @Test
    fun listShares_fallbackSharesReturned() = runTest {
        val smbClient = FakeSmbClient(shares = listOf("backup"))
        val rpcEnumerator = FakeShareRpcEnumerator(shares = emptyList())
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = smbClient,
            shareRpcEnumerator = rpcEnumerator,
        )

        val result = useCase.listShares("nas.local", "user", "pw", "")

        assertTrue(result is SmbBrowseResult.Success)
        assertEquals(listOf("backup"), (result as SmbBrowseResult.Success).data)
        assertEquals(1, rpcEnumerator.rpcCalls)
        assertEquals(1, smbClient.listSharesCalls)
    }

    @Test
    fun listShares_usesRpcResultWithoutFallback() = runTest {
        val smbClient = FakeSmbClient(exception = IllegalStateException("fallback should not run"))
        val rpcEnumerator = FakeShareRpcEnumerator(shares = listOf("media", "archive"))
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = smbClient,
            shareRpcEnumerator = rpcEnumerator,
        )

        val result = useCase.listShares("nas.local", "user", "pw", "")

        assertTrue(result is SmbBrowseResult.Success)
        assertEquals(listOf("archive", "media"), (result as SmbBrowseResult.Success).data)
        assertEquals(1, rpcEnumerator.rpcCalls)
        assertEquals(0, smbClient.listSharesCalls)
    }

    @Test
    fun listShares_rpcFailureFallsBackToListShares() = runTest {
        val smbClient = FakeSmbClient(shares = listOf("backup"))
        val rpcEnumerator = FakeShareRpcEnumerator(exception = IllegalStateException("rpc boom"))
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = smbClient,
            shareRpcEnumerator = rpcEnumerator,
        )

        val result = useCase.listShares("nas.local", "user", "pw", "")

        assertTrue(result is SmbBrowseResult.Success)
        assertEquals(listOf("backup"), (result as SmbBrowseResult.Success).data)
        assertEquals(1, rpcEnumerator.rpcCalls)
        assertEquals(1, smbClient.listSharesCalls)
    }

    @Test
    fun listShares_failureWhenBothSourcesFail() = runTest {
        val useCase = BrowseSmbDestinationUseCase(
            smbClient = FakeSmbClient(exception = IllegalStateException("boom")),
            shareRpcEnumerator = FakeShareRpcEnumerator(exception = IllegalStateException("boom fallback")),
        )

        val result = useCase.listShares("nas.local", "user", "pw", "")

        assertTrue(result is SmbBrowseResult.Failure)
        val failure = result as SmbBrowseResult.Failure
        assertEquals("Unable to browse SMB destination.", failure.message)
        assertFalse(failure.technicalDetail.isNullOrBlank())
    }

    private class FakeSmbClient(
        private val shares: List<String> = emptyList(),
        private val directories: List<String> = emptyList(),
        private val exception: Throwable? = null,
    ) : SmbClient {
        var listSharesCalls: Int = 0

        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
            SmbConnectionResult(latencyMs = 1)

        override suspend fun listShares(host: String, username: String, password: String): List<String> {
            listSharesCalls += 1
            exception?.let { throw it }
            return shares
        }

        override suspend fun listDirectories(
            host: String,
            shareName: String,
            path: String,
            username: String,
            password: String,
        ): List<String> = directories
    }

    private class FakeShareRpcEnumerator(
        private val shares: List<String> = emptyList(),
        private val exception: Throwable? = null,
    ) : SmbShareRpcEnumerator {
        var rpcCalls: Int = 0

        override suspend fun listSharesViaRpc(host: String, username: String, password: String, domain: String): List<String> {
            rpcCalls += 1
            exception?.let { throw it }
            return shares
        }
    }
}
