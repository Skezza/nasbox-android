package skezza.smbsync.data.media

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaAlbum(
    val bucketId: String,
    val displayName: String,
    val itemCount: Int,
    val latestItemDateTakenEpochMs: Long?,
)

data class MediaImageItem(
    val mediaId: String,
    val bucketId: String,
    val displayName: String?,
    val mimeType: String?,
    val dateTakenEpochMs: Long?,
)

interface MediaStoreDataSource {
    suspend fun listAlbums(): List<MediaAlbum>
    suspend fun listImagesForAlbum(bucketId: String): List<MediaImageItem>
}

class AndroidMediaStoreDataSource(
    context: Context,
) : MediaStoreDataSource {

    private val contentResolver: ContentResolver = context.contentResolver

    override suspend fun listAlbums(): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
        )

        val buckets = linkedMapOf<String, MutableAlbumAccumulator>()
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdIndex) ?: continue
                val bucketName = cursor.getString(bucketNameIndex).orEmpty().ifBlank { "Unknown album" }
                val dateTaken = cursor.getLong(dateTakenIndex).takeIf { it > 0L }
                val accumulator = buckets.getOrPut(bucketId) {
                    MutableAlbumAccumulator(
                        displayName = bucketName,
                        itemCount = 0,
                        latestItemDateTakenEpochMs = null,
                    )
                }
                accumulator.itemCount += 1
                if (dateTaken != null) {
                    accumulator.latestItemDateTakenEpochMs = maxOf(
                        accumulator.latestItemDateTakenEpochMs ?: dateTaken,
                        dateTaken,
                    )
                }
            }
        }

        buckets.map { (bucketId, value) ->
            MediaAlbum(
                bucketId = bucketId,
                displayName = value.displayName,
                itemCount = value.itemCount,
                latestItemDateTakenEpochMs = value.latestItemDateTakenEpochMs,
            )
        }.sortedWith(compareByDescending<MediaAlbum> { it.latestItemDateTakenEpochMs ?: 0L }.thenBy { it.displayName.lowercase() })
    }

    override suspend fun listImagesForAlbum(bucketId: String): List<MediaImageItem> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        buildList {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idIndex).toString()
                    val currentBucketId = cursor.getString(bucketIdIndex).orEmpty()
                    val dateTaken = cursor.getLong(dateTakenIndex).takeIf { it > 0L }
                    add(
                        MediaImageItem(
                            mediaId = mediaId,
                            bucketId = currentBucketId,
                            displayName = cursor.getString(displayNameIndex),
                            mimeType = cursor.getString(mimeTypeIndex),
                            dateTakenEpochMs = dateTaken,
                        ),
                    )
                }
            }
        }
    }
}

private data class MutableAlbumAccumulator(
    val displayName: String,
    var itemCount: Int,
    var latestItemDateTakenEpochMs: Long?,
)
