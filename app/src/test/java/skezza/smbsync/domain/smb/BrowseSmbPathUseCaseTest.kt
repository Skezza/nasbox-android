package skezza.smbsync.domain.smb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.smb.SmbBrowseRequest
import skezza.smbsync.data.smb.SmbBrowseResult
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.SmbConnectionResult

class BrowseSmbPathUseCaseTest {

    @Test
    fun invoke_withShareAndPath_returnsDirectories() = runTest {
        val useCase = BrowseSmbPathUseCase(
            smbClient = FakeSmbClient(
                browseResult = SmbBrowseResult(
                    shareName = "photos",
                    directoryPath = "2025/trips",
                    shares = emptyList(),
                    directories = listOf("beach", "mountains"),
                ),
            ),
        )

        val result = useCase(
            host = "quanta.local",
            shareName = "photos",
            directoryPath = "2025/trips",
            username = "user",
            password = "pw",
        )

        assertTrue(result.success)
        assertEquals("photos", result.shareName)
        assertEquals(listOf("beach", "mountains"), result.directories)
    }

    @Test
    fun invoke_withInvalidHost_returnsFailure() = runTest {
        val useCase = BrowseSmbPathUseCase(FakeSmbClient())

        val result = useCase(
            host = "",
            shareName = "",
            directoryPath = "",
            username = "user",
            password = "pw",
        )

        assertFalse(result.success)
        assertEquals("Host is required.", result.message)
    }

    private class FakeSmbClient(
        private val browseResult: SmbBrowseResult = SmbBrowseResult(
            shareName = "",
            directoryPath = "",
            shares = listOf("media", "photos"),
            directories = emptyList(),
        ),
    ) : SmbClient {
        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
            SmbConnectionResult(1)

        override suspend fun browse(request: SmbBrowseRequest): SmbBrowseResult = browseResult
    }
}
