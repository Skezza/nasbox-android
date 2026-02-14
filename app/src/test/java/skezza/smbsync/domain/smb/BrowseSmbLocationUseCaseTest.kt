package skezza.smbsync.domain.smb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.smb.SmbBrowseEntry
import skezza.smbsync.data.smb.SmbBrowseLevel
import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbBrowseResult
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbConnectionResult

class BrowseSmbLocationUseCaseTest {

    @Test
    fun browse_successParsesTargetAndReturnsEntries() = runTest {
        val fakeClient = FakeSmbClient(
            browseResult = SmbBrowseResult(
                level = SmbBrowseLevel.FOLDERS,
                entries = listOf(SmbBrowseEntry(name = "photos", path = "photos")),
            ),
        )
        val useCase = BrowseSmbLocationUseCase(fakeClient)

        val result = useCase(
            host = "smb://nas.local/media",
            shareName = "",
            currentPath = "",
            username = "user",
            password = "secret",
        )

        assertTrue(result.success)
        assertEquals("nas.local", fakeClient.lastRequest?.host)
        assertEquals("media", fakeClient.lastRequest?.shareName)
        assertEquals("photos", result.entries.first().name)
    }

    @Test
    fun browse_invalidHostReturnsFailure() = runTest {
        val useCase = BrowseSmbLocationUseCase(FakeSmbClient())

        val result = useCase(
            host = "",
            shareName = "",
            currentPath = "",
            username = "user",
            password = "secret",
        )

        assertFalse(result.success)
        assertTrue(result.message.orEmpty().contains("Host is required"))
    }

    private class FakeSmbClient(
        private val browseResult: SmbBrowseResult = SmbBrowseResult(SmbBrowseLevel.SHARES, emptyList()),
    ) : SmbClient {
        var lastRequest: SmbBrowseRequest? = null

        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult {
            return SmbConnectionResult(latencyMs = 1)
        }

        override suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult {
            lastRequest = request
            return browseResult
        }
    }
}
