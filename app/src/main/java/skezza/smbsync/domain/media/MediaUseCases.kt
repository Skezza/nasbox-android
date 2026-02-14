package skezza.smbsync.domain.media

import skezza.smbsync.data.media.MediaAlbum
import skezza.smbsync.data.media.MediaImageItem
import skezza.smbsync.data.media.MediaStoreDataSource

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
