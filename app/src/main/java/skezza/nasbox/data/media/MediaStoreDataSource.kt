package skezza.nasbox.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val sizeBytes: Long?,
    val relativePath: String? = null,
)

data class FullDeviceScanResult(
    val items: List<MediaImageItem>,
    val inaccessibleRoots: List<String> = emptyList(),
)

data class FolderScanProgress(
    val discoveredFiles: Int,
    val visitedDirectories: Int,
    val currentPath: String? = null,
    val completed: Boolean = false,
)

interface MediaStoreDataSource {
    suspend fun listAlbums(): List<MediaAlbum>
    suspend fun listImagesForAlbum(bucketId: String, includeVideos: Boolean = false): List<MediaImageItem>
    suspend fun openImageStream(mediaId: String): InputStream?
    suspend fun openMediaStream(mediaId: String): InputStream? = openImageStream(mediaId)
    suspend fun listFilesForFolder(
        folderPathOrUri: String,
        onProgress: (suspend (FolderScanProgress) -> Unit)? = null,
        shouldContinue: (() -> Boolean)? = null,
    ): List<MediaImageItem> = emptyList()
    suspend fun scanFullDeviceSharedStorage(): FullDeviceScanResult = FullDeviceScanResult(emptyList())
    fun imageContentUri(mediaId: String): Uri
}

class AndroidMediaStoreDataSource(
    context: Context,
) : MediaStoreDataSource {

    private val contentResolver: ContentResolver = context.contentResolver
    private val appContext = context.applicationContext

    override suspend fun listAlbums(): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
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

    override suspend fun listImagesForAlbum(bucketId: String, includeVideos: Boolean): List<MediaImageItem> = withContext(Dispatchers.IO) {
        val imageItems = queryAlbumMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, bucketId)
        if (!includeVideos) return@withContext imageItems
        imageItems + queryAlbumMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bucketId, useContentUriAsId = true)
    }

    override suspend fun openImageStream(mediaId: String): InputStream? = openMediaStream(mediaId)

    override suspend fun openMediaStream(mediaId: String): InputStream? = withContext(Dispatchers.IO) {
        when {
            mediaId.startsWith(CONTENT_URI_PREFIX) -> contentResolver.openInputStream(Uri.parse(mediaId))
            mediaId.startsWith(FILE_URI_PREFIX) -> {
                val parsed = Uri.parse(mediaId)
                val path = parsed.path?.takeIf { it.isNotBlank() } ?: return@withContext null
                val file = File(path)
                if (!file.exists() || !file.isFile || !file.canRead()) return@withContext null
                FileInputStream(file)
            }
            else -> {
                val id = mediaId.toLongOrNull() ?: return@withContext null
                contentResolver.openInputStream(imageContentUri(id.toString()))
            }
        }
    }

    override suspend fun listFilesForFolder(
        folderPathOrUri: String,
        onProgress: (suspend (FolderScanProgress) -> Unit)?,
        shouldContinue: (() -> Boolean)?,
    ): List<MediaImageItem> = withContext(Dispatchers.IO) {
        val source = folderPathOrUri.trim()
        if (source.isBlank()) return@withContext emptyList()
        val progressTracker = FolderScanProgressTracker(
            onProgress = onProgress,
            shouldContinue = shouldContinue,
        )
        if (source.startsWith(CONTENT_URI_PREFIX)) {
            return@withContext listDocumentTreeFiles(
                rootUri = Uri.parse(source),
                progressTracker = progressTracker,
            )
        }

        val root = File(source)
        if (!root.exists() || !root.isDirectory) {
            throw IllegalArgumentException("Folder source is not accessible: $source")
        }
        val result = collectLocalFiles(
            root = root,
            includeRootFolderInRelativePath = false,
            progressTracker = progressTracker,
        )
        progressTracker.complete(root.absolutePath)
        result.items
    }

    override suspend fun scanFullDeviceSharedStorage(): FullDeviceScanResult = withContext(Dispatchers.IO) {
        val allItems = mutableListOf<MediaImageItem>()
        val inaccessibleRoots = mutableListOf<String>()
        val roots = fullDeviceRoots()

        roots.forEach { (rootName, root) ->
            if (!root.exists() || !root.isDirectory) {
                inaccessibleRoots += "$rootName (missing)"
                return@forEach
            }
            if (!root.canRead()) {
                inaccessibleRoots += "$rootName (permission denied)"
                return@forEach
            }

            runCatching {
                val result = collectLocalFiles(root = root, includeRootFolderInRelativePath = true)
                allItems += result.items
                inaccessibleRoots += result.inaccessibleDirectories
            }.onFailure { throwable ->
                inaccessibleRoots += "$rootName (${throwable.message ?: "scan failure"})"
            }
        }

        FullDeviceScanResult(
            items = allItems.distinctBy { it.mediaId },
            inaccessibleRoots = inaccessibleRoots.distinct(),
        )
    }

    override fun imageContentUri(mediaId: String): Uri {
        val id = mediaId.toLongOrNull() ?: return MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private suspend fun listDocumentTreeFiles(
        rootUri: Uri,
        progressTracker: FolderScanProgressTracker,
    ): List<MediaImageItem> {
        val root = DocumentFile.fromTreeUri(appContext, rootUri) ?: DocumentFile.fromSingleUri(appContext, rootUri)
            ?: throw IllegalArgumentException("Unable to access folder URI.")

        val rootLabel = root.name.orEmpty().ifBlank { "Folder" }
        val items = mutableListOf<MediaImageItem>()
        if (root.isFile) {
            progressTracker.ensureContinue()
            items += root.toMediaImageItem(bucket = rootLabel, relativePath = "")
            progressTracker.onFile(root.uri.toString())
            progressTracker.complete(root.uri.toString())
            return items
        }

        collectDocumentChildren(
            directory = root,
            relativePath = "",
            bucket = rootLabel,
            output = items,
            progressTracker = progressTracker,
        )
        progressTracker.complete(root.uri.toString())
        return items
    }

    private suspend fun collectDocumentChildren(
        directory: DocumentFile,
        relativePath: String,
        bucket: String,
        output: MutableList<MediaImageItem>,
        progressTracker: FolderScanProgressTracker,
    ) {
        progressTracker.ensureContinue()
        progressTracker.onDirectory(
            currentPath = directory.uri.toString(),
        )
        val children = directory.listFiles()
            .sortedBy { it.name?.lowercase().orEmpty() }
        children.forEach { child ->
            progressTracker.ensureContinue()
            if (child.isDirectory) {
                val nextRelative = joinRelativePath(relativePath, child.name.orEmpty())
                collectDocumentChildren(
                    directory = child,
                    relativePath = nextRelative,
                    bucket = bucket,
                    output = output,
                    progressTracker = progressTracker,
                )
            } else if (child.isFile) {
                output += child.toMediaImageItem(
                    bucket = bucket,
                    relativePath = relativePath,
                )
                progressTracker.onFile(child.uri.toString())
            }
        }
    }

    private suspend fun collectLocalFiles(
        root: File,
        includeRootFolderInRelativePath: Boolean,
        progressTracker: FolderScanProgressTracker? = null,
    ): LocalFileScanResult {
        val output = mutableListOf<MediaImageItem>()
        val inaccessibleDirectories = mutableListOf<String>()
        val stack = ArrayDeque<Pair<File, String>>()
        stack += root to ""

        while (stack.isNotEmpty()) {
            progressTracker?.ensureContinue()
            val (directory, relativePath) = stack.removeLast()
            progressTracker?.onDirectory(directory.absolutePath)
            val children = directory.listFiles()
            if (children == null) {
                inaccessibleDirectories += directory.absolutePath
                continue
            }

            children.sortedBy { it.name.lowercase() }.forEach { child ->
                progressTracker?.ensureContinue()
                if (child.isDirectory) {
                    val nextRelative = joinRelativePath(relativePath, child.name)
                    stack += child to nextRelative
                } else if (child.isFile && child.canRead()) {
                    val relativeDirectory = if (includeRootFolderInRelativePath) {
                        joinRelativePath(root.name, relativePath)
                    } else {
                        relativePath
                    }
                    output += child.toMediaImageItem(relativeDirectory)
                    progressTracker?.onFile(child.absolutePath)
                }
            }
        }

        return LocalFileScanResult(
            items = output,
            inaccessibleDirectories = inaccessibleDirectories,
        )
    }

    @Suppress("DEPRECATION")
    private fun fullDeviceRoots(): List<Pair<String, File>> {
        val candidates = listOf(
            Environment.DIRECTORY_DCIM to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.DIRECTORY_PICTURES to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.DIRECTORY_MOVIES to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.DIRECTORY_DOWNLOADS to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.DIRECTORY_DOCUMENTS to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.DIRECTORY_MUSIC to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        )
        return candidates.distinctBy { (_, file) -> file.absolutePath }
    }

    private fun DocumentFile.toMediaImageItem(bucket: String, relativePath: String): MediaImageItem {
        return MediaImageItem(
            mediaId = uri.toString(),
            bucketId = bucket,
            displayName = name,
            mimeType = type,
            dateTakenEpochMs = lastModified().takeIf { it > 0L },
            sizeBytes = length().takeIf { it >= 0L },
            relativePath = relativePath.ifBlank { null },
        )
    }

    private fun File.toMediaImageItem(relativePath: String): MediaImageItem {
        return MediaImageItem(
            mediaId = Uri.fromFile(this).toString(),
            bucketId = parentFile?.name.orEmpty(),
            displayName = name,
            mimeType = URLConnection.guessContentTypeFromName(name),
            dateTakenEpochMs = lastModified().takeIf { it > 0L },
            sizeBytes = length().takeIf { it >= 0L },
            relativePath = relativePath.ifBlank { null },
        )
    }

    private fun queryAlbumMedia(uri: Uri, bucketId: String, useContentUriAsId: Boolean = false): List<MediaImageItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"

        return buildList {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val mediaId = if (useContentUriAsId) {
                        ContentUris.withAppendedId(uri, id).toString()
                    } else {
                        id.toString()
                    }
                    val currentBucketId = cursor.getString(bucketIdIndex).orEmpty()
                    val dateTaken = cursor.getLong(dateTakenIndex).takeIf { it > 0L }
                    add(
                        MediaImageItem(
                            mediaId = mediaId,
                            bucketId = currentBucketId,
                            displayName = cursor.getString(displayNameIndex),
                            mimeType = cursor.getString(mimeTypeIndex),
                            dateTakenEpochMs = dateTaken,
                            sizeBytes = cursor.getLong(sizeIndex).takeIf { it > 0L },
                        ),
                    )
                }
            }
        }
    }

    private fun joinRelativePath(left: String, right: String): String {
        val normalizedLeft = left.trim().trim('/')
        val normalizedRight = right.trim().trim('/')
        return listOf(normalizedLeft, normalizedRight)
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    private data class LocalFileScanResult(
        val items: List<MediaImageItem>,
        val inaccessibleDirectories: List<String>,
    )

    private class FolderScanProgressTracker(
        private val onProgress: (suspend (FolderScanProgress) -> Unit)?,
        private val shouldContinue: (() -> Boolean)?,
    ) {
        private var discoveredFiles = 0
        private var visitedDirectories = 0
        private var lastProgressAt = 0L

        suspend fun ensureContinue() {
            currentCoroutineContext().ensureActive()
            if (shouldContinue?.invoke() == false) {
                throw CancellationException("Folder scan canceled")
            }
        }

        suspend fun onDirectory(currentPath: String?) {
            visitedDirectories += 1
            maybeReport(currentPath = currentPath, force = false)
        }

        suspend fun onFile(currentPath: String?) {
            discoveredFiles += 1
            maybeReport(currentPath = currentPath, force = false)
        }

        suspend fun complete(currentPath: String?) {
            maybeReport(currentPath = currentPath, force = true)
        }

        private suspend fun maybeReport(
            currentPath: String?,
            force: Boolean,
        ) {
            val progressCallback = onProgress ?: return
            val now = System.currentTimeMillis()
            val fileStepReached = discoveredFiles > 0 && discoveredFiles % FILE_PROGRESS_STEP == 0
            val directoryStepReached = visitedDirectories > 0 && visitedDirectories % DIRECTORY_PROGRESS_STEP == 0
            val dueByTime = (now - lastProgressAt) >= PROGRESS_INTERVAL_MS
            if (!force && !fileStepReached && !directoryStepReached && !dueByTime) {
                return
            }
            progressCallback(
                FolderScanProgress(
                    discoveredFiles = discoveredFiles,
                    visitedDirectories = visitedDirectories,
                    currentPath = currentPath,
                    completed = force,
                ),
            )
            lastProgressAt = now
        }
    }

    private companion object {
        private const val CONTENT_URI_PREFIX = "content://"
        private const val FILE_URI_PREFIX = "file://"
        private const val PROGRESS_INTERVAL_MS = 750L
        private const val FILE_PROGRESS_STEP = 64
        private const val DIRECTORY_PROGRESS_STEP = 16
    }
}

private data class MutableAlbumAccumulator(
    val displayName: String,
    var itemCount: Int,
    var latestItemDateTakenEpochMs: Long?,
)
