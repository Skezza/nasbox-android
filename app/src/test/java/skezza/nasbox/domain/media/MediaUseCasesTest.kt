package skezza.nasbox.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import skezza.nasbox.data.media.MediaAlbum

class MediaUseCasesTest {

    @Test
    fun firstCameraAlbumOrNull_prefersCameraNamedAlbum() {
        val albums = listOf(
            MediaAlbum(bucketId = "1", displayName = "Screenshots", itemCount = 5, latestItemDateTakenEpochMs = 10),
            MediaAlbum(bucketId = "2", displayName = "Camera", itemCount = 50, latestItemDateTakenEpochMs = 20),
        )

        val result = albums.firstCameraAlbumOrNull()

        assertEquals("2", result?.bucketId)
    }

    @Test
    fun firstCameraAlbumOrNull_returnsNullWhenNotPresent() {
        val result = listOf(
            MediaAlbum(bucketId = "1", displayName = "Screenshots", itemCount = 5, latestItemDateTakenEpochMs = 10),
        ).firstCameraAlbumOrNull()

        assertNull(result)
    }
}
