package skezza.nasbox.domain.media

import skezza.nasbox.data.media.MediaAlbum
import skezza.nasbox.data.media.MediaImageItem
import skezza.nasbox.data.media.MediaStoreDataSource

class ListMediaAlbumsUseCase(
    private val mediaStoreDataSource: MediaStoreDataSource,
) {
    suspend operator fun invoke(): List<MediaAlbum> = mediaStoreDataSource.listAlbums()
}

class ListAlbumImagesUseCase(
    private val mediaStoreDataSource: MediaStoreDataSource,
) {
    suspend operator fun invoke(bucketId: String): List<MediaImageItem> = mediaStoreDataSource.listImagesForAlbum(bucketId)
}

fun List<MediaAlbum>.firstCameraAlbumOrNull(): MediaAlbum? {
    return firstOrNull { album ->
        val normalized = album.displayName.lowercase()
        normalized == "camera" || normalized.contains("camera") || normalized == "dcim"
    }
}
