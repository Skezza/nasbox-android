package skezza.nasbox.domain.sync

import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skezza.nasbox.data.db.BackupRecordEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.media.MediaImageItem
import skezza.nasbox.data.media.MediaStoreDataSource
import skezza.nasbox.data.repository.BackupRecordRepository
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbConnectionRequest
import skezza.nasbox.data.smb.toSmbConnectionFailure

class RunPlanBackupUseCase(
    private val planRepository: PlanRepository,
    private val serverRepository: ServerRepository,
    private val backupRecordRepository: BackupRecordRepository,
    private val runRepository: RunRepository,
    private val runLogRepository: RunLogRepository,
    private val credentialStore: CredentialStore,
    private val mediaStoreDataSource: MediaStoreDataSource,
    private val smbClient: SmbClient,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    suspend operator fun invoke(planId: Long): RunExecutionResult = withContext(Dispatchers.IO) {
        val startedAt = nowEpochMs()
        val runId = runRepository.createRun(
            RunEntity(
                planId = planId,
                status = STATUS_RUNNING,
                startedAtEpochMs = startedAt,
            ),
        )

        suspend fun log(severity: String, message: String, detail: String? = null) {
            val formatted = "[runId=$runId planId=$planId] $message"
            if (severity == SEVERITY_ERROR) {
                Log.e(LOG_TAG, formatted + detail?.let { " | $it" }.orEmpty())
            } else {
                Log.i(LOG_TAG, formatted + detail?.let { " | $it" }.orEmpty())
            }
            runLogRepository.createLog(
                RunLogEntity(
                    runId = runId,
                    timestampEpochMs = nowEpochMs(),
                    severity = severity,
                    message = message,
                    detail = detail,
                ),
            )
        }

        log(SEVERITY_INFO, "Run started")

        val plan = planRepository.getPlan(planId)
        if (plan == null) {
            log(SEVERITY_ERROR, "Run aborted: plan does not exist", "planId=$planId")
            return@withContext finalizeRun(
                runId = runId,
                planId = planId,
                startedAt = startedAt,
                scanned = 0,
                uploaded = 0,
                skipped = 0,
                failed = 1,
                summaryError = "Plan no longer exists.",
                status = STATUS_FAILED,
                log = ::log,
            )
        }

        if (!plan.enabled) {
            log(SEVERITY_ERROR, "Run aborted: plan is disabled", "planId=${plan.planId}")
            return@withContext finalizeRun(
                runId = runId,
                planId = planId,
                startedAt = startedAt,
                scanned = 0,
                uploaded = 0,
                skipped = 0,
                failed = 1,
                summaryError = "Plan is disabled.",
                status = STATUS_FAILED,
                log = ::log,
            )
        }

        val sourceType = normalizeSourceType(plan.sourceType)
        if (sourceType == null) {
            log(SEVERITY_ERROR, "Run aborted: unsupported source type", "sourceType=${plan.sourceType}")
            return@withContext finalizeRun(
                runId = runId,
                planId = planId,
                startedAt = startedAt,
                scanned = 0,
                uploaded = 0,
                skipped = 0,
                failed = 1,
                summaryError = "Unsupported source mode: ${plan.sourceType}.",
                status = STATUS_FAILED,
                log = ::log,
            )
        }

        val server = serverRepository.getServer(plan.serverId)
        if (server == null) {
            log(SEVERITY_ERROR, "Run aborted: destination server missing", "serverId=${plan.serverId}")
            return@withContext finalizeRun(
                runId = runId,
                planId = planId,
                startedAt = startedAt,
                scanned = 0,
                uploaded = 0,
                skipped = 0,
                failed = 1,
                summaryError = "Destination server not found.",
                status = STATUS_FAILED,
                log = ::log,
            )
        }

        val password = credentialStore.loadPassword(server.credentialAlias)
        if (password == null) {
            log(SEVERITY_ERROR, "Run aborted: credentials unavailable", "serverId=${server.serverId} alias=${server.credentialAlias}")
            return@withContext finalizeRun(
                runId = runId,
                planId = planId,
                startedAt = startedAt,
                scanned = 0,
                uploaded = 0,
                skipped = 0,
                failed = 1,
                summaryError = "Server credentials unavailable. Re-save this server.",
                status = STATUS_FAILED,
                log = ::log,
            )
        }

        val connection = SmbConnectionRequest(
            host = server.host,
            shareName = server.shareName,
            username = server.username,
            password = password,
        )

        log(
            SEVERITY_INFO,
            "Resolved destination",
            "host=${server.host} share=${server.shareName} basePath=${server.basePath} sourceType=$sourceType",
        )
        if (sourceType == SOURCE_TYPE_ALBUM && plan.useAlbumTemplating) {
            log(SEVERITY_INFO, "Template configuration", "dir=${plan.directoryTemplate} file=${plan.filenamePattern}")
        }

        val albumDisplayName = runCatching {
            mediaStoreDataSource.listAlbums().firstOrNull { it.bucketId == plan.sourceAlbum }?.displayName
        }.getOrNull().orEmpty().ifBlank { plan.sourceAlbum }

        val scanResult = when (sourceType) {
            SOURCE_TYPE_ALBUM -> {
                runCatching {
                    SourceScanResult(
                        items = mediaStoreDataSource.listImagesForAlbum(plan.sourceAlbum),
                    )
                }.getOrElse { throwable ->
                    val message = "Unable to scan local media. Check photo permission and album availability."
                    log(SEVERITY_ERROR, message, throwable.message)
                    return@withContext finalizeRun(
                        runId = runId,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = 0,
                        uploaded = 0,
                        skipped = 0,
                        failed = 1,
                        summaryError = message,
                        status = STATUS_FAILED,
                        log = ::log,
                    )
                }
            }

            SOURCE_TYPE_FOLDER -> {
                if (plan.folderPath.isBlank()) {
                    val message = "Folder source path is missing. Re-save this plan."
                    log(SEVERITY_ERROR, "Run aborted: folder source path missing")
                    return@withContext finalizeRun(
                        runId = runId,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = 0,
                        uploaded = 0,
                        skipped = 0,
                        failed = 1,
                        summaryError = message,
                        status = STATUS_FAILED,
                        log = ::log,
                    )
                }

                log(SEVERITY_INFO, "Scanning folder source", "folderPath=${plan.folderPath}")
                runCatching {
                    SourceScanResult(
                        items = mediaStoreDataSource.listFilesForFolder(plan.folderPath),
                    )
                }.getOrElse { throwable ->
                    val message = "Unable to scan selected folder source. Confirm access to the selected folder."
                    log(SEVERITY_ERROR, message, throwable.message)
                    return@withContext finalizeRun(
                        runId = runId,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = 0,
                        uploaded = 0,
                        skipped = 0,
                        failed = 1,
                        summaryError = message,
                        status = STATUS_FAILED,
                        log = ::log,
                    )
                }
            }

            SOURCE_TYPE_FULL_DEVICE -> {
                log(SEVERITY_INFO, "Scanning shared-storage roots for full-device backup")
                runCatching {
                    val fullDevice = mediaStoreDataSource.scanFullDeviceSharedStorage()
                    SourceScanResult(
                        items = fullDevice.items,
                        warnings = fullDevice.inaccessibleRoots.map { "Skipped inaccessible shared-storage location: $it" },
                    )
                }.getOrElse { throwable ->
                    val message = "Unable to scan shared storage for full-device backup."
                    log(SEVERITY_ERROR, message, throwable.message)
                    return@withContext finalizeRun(
                        runId = runId,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = 0,
                        uploaded = 0,
                        skipped = 0,
                        failed = 1,
                        summaryError = message,
                        status = STATUS_FAILED,
                        log = ::log,
                    )
                }
            }

            else -> error("Unexpected source type: $sourceType")
        }

        var uploadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var summaryError: String? = null

        scanResult.warnings.forEach { warning ->
            failedCount += 1
            summaryError = summaryError ?: warning
            log(SEVERITY_ERROR, warning)
        }

        log(
            SEVERITY_INFO,
            "Scan complete",
            "source=$sourceType discovered=${scanResult.items.size} warnings=${scanResult.warnings.size}",
        )

        for (item in scanResult.items) {
            val record = backupRecordRepository.findByPlanAndMediaItem(planId, item.mediaId)
            if (record != null) {
                skippedCount += 1
                log(SEVERITY_INFO, "Skipped previously uploaded item", "mediaItemId=${item.mediaId}")
                continue
            }

            val remotePath = if (sourceType == SOURCE_TYPE_ALBUM) {
                PathRenderer.render(
                    basePath = server.basePath,
                    directoryTemplate = plan.directoryTemplate,
                    filenamePattern = plan.filenamePattern,
                    mediaItem = item,
                    fallbackAlbumToken = albumDisplayName,
                    useAlbumTemplating = plan.useAlbumTemplating,
                )
            } else {
                PathRenderer.renderPreservingSourcePath(
                    basePath = server.basePath,
                    mediaItem = item,
                )
            }

            val stream = mediaStoreDataSource.openMediaStream(item.mediaId)
            if (stream == null) {
                failedCount += 1
                val message = "Unable to read source item ${item.mediaId}."
                summaryError = summaryError ?: message
                log(SEVERITY_ERROR, message)
                continue
            }

            val uploadResult = runCatching {
                stream.use { input ->
                    smbClient.uploadFile(
                        request = connection,
                        remotePath = remotePath,
                        contentLengthBytes = item.sizeBytes,
                        inputStream = input,
                    )
                }
            }

            if (uploadResult.isSuccess) {
                val recordResult = runCatching {
                    backupRecordRepository.create(
                        BackupRecordEntity(
                            planId = planId,
                            mediaItemId = item.mediaId,
                            remotePath = remotePath,
                            uploadedAtEpochMs = nowEpochMs(),
                        ),
                    )
                }
                if (recordResult.isSuccess) {
                    uploadedCount += 1
                    log(SEVERITY_INFO, "Uploaded item", "mediaItemId=${item.mediaId} remotePath=$remotePath")
                } else {
                    failedCount += 1
                    val recordError = recordResult.exceptionOrNull()
                    val message = "Uploaded item ${item.mediaId}, but failed to persist backup proof."
                    summaryError = summaryError ?: message
                    log(SEVERITY_ERROR, message, recordError?.message)
                }
            } else {
                failedCount += 1
                val throwable = uploadResult.exceptionOrNull()
                val mapped = throwable?.toSmbConnectionFailure()
                val message = mapped?.toUserMessage() ?: "Upload failed for item ${item.mediaId}."
                summaryError = summaryError ?: message
                log(SEVERITY_ERROR, message, throwable.toLogDetail())
            }
        }

        val finalStatus = when {
            failedCount > 0 && uploadedCount == 0 && skippedCount == 0 -> STATUS_FAILED
            failedCount > 0 -> STATUS_PARTIAL
            else -> STATUS_SUCCESS
        }

        finalizeRun(
            runId = runId,
            planId = planId,
            startedAt = startedAt,
            scanned = scanResult.items.size,
            uploaded = uploadedCount,
            skipped = skippedCount,
            failed = failedCount,
            summaryError = summaryError,
            status = finalStatus,
            log = ::log,
        )
    }

    private suspend fun finalizeRun(
        runId: Long,
        planId: Long,
        startedAt: Long,
        scanned: Int,
        uploaded: Int,
        skipped: Int,
        failed: Int,
        summaryError: String?,
        status: String,
        log: suspend (String, String, String?) -> Unit,
    ): RunExecutionResult {
        val finishedAt = nowEpochMs()
        runRepository.updateRun(
            RunEntity(
                runId = runId,
                planId = planId,
                status = status,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = finishedAt,
                scannedCount = scanned,
                uploadedCount = uploaded,
                skippedCount = skipped,
                failedCount = failed,
                summaryError = summaryError,
            ),
        )
        log(SEVERITY_INFO, "Run finished", "status=$status uploaded=$uploaded skipped=$skipped failed=$failed")
        return RunExecutionResult(
            runId = runId,
            status = status,
            uploadedCount = uploaded,
            skippedCount = skipped,
            failedCount = failed,
            summaryError = summaryError,
        )
    }

    private fun normalizeSourceType(rawSourceType: String): String? {
        val normalized = rawSourceType.trim().uppercase(Locale.US)
        return when (normalized) {
            SOURCE_TYPE_ALBUM -> SOURCE_TYPE_ALBUM
            SOURCE_TYPE_FOLDER -> SOURCE_TYPE_FOLDER
            SOURCE_TYPE_FULL_DEVICE -> SOURCE_TYPE_FULL_DEVICE
            else -> null
        }
    }

    private data class SourceScanResult(
        val items: List<MediaImageItem>,
        val warnings: List<String> = emptyList(),
    )

    companion object {
        private const val STATUS_RUNNING = "RUNNING"
        private const val STATUS_SUCCESS = "SUCCESS"
        private const val STATUS_PARTIAL = "PARTIAL"
        private const val STATUS_FAILED = "FAILED"
        private const val SOURCE_TYPE_ALBUM = "ALBUM"
        private const val SOURCE_TYPE_FOLDER = "FOLDER"
        private const val SOURCE_TYPE_FULL_DEVICE = "FULL_DEVICE"
        private const val SEVERITY_INFO = "INFO"
        private const val SEVERITY_ERROR = "ERROR"
        private const val LOG_TAG = "NasBoxRun"
    }
}

data class RunExecutionResult(
    val runId: Long,
    val status: String,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String? = null,
)

internal object PathRenderer {
    private const val DEFAULT_TEMPLATE = "{year}/{month}/{day}"
    private const val DEFAULT_FILENAME_PATTERN = "{timestamp}_{mediaId}.{ext}"
    private val ILLEGAL_PATH_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    fun render(
        basePath: String,
        directoryTemplate: String,
        filenamePattern: String,
        mediaItem: MediaImageItem,
        fallbackAlbumToken: String,
        useAlbumTemplating: Boolean,
    ): String {
        if (!useAlbumTemplating) {
            val filename = sanitizeSegment(mediaItem.displayName.orEmpty().ifBlank { "${mediaItem.mediaId}.${extension(mediaItem)}" })
            val pathSegments = listOf(basePath, sanitizeSegment(fallbackAlbumToken), filename).filter { it.isNotBlank() }
            return pathSegments.joinToString("/") { it.trim('/') }
        }

        val safeExt = extension(mediaItem)
        val hasDate = mediaItem.dateTakenEpochMs != null
        val date = Date(mediaItem.dateTakenEpochMs ?: 0L)
        val values = mapOf(
            "year" to if (hasDate) format(date, "yyyy") else "unknown",
            "month" to if (hasDate) format(date, "MM") else "unknown",
            "day" to if (hasDate) format(date, "dd") else "unknown",
            "time" to if (hasDate) format(date, "HHmmss") else "unknown",
            "timestamp" to if (hasDate) format(date, "yyyyMMdd_HHmmss") else "unknown",
            "album" to sanitizeSegment(fallbackAlbumToken),
            "mediaId" to sanitizeSegment(mediaItem.mediaId),
            "ext" to sanitizeSegment(safeExt),
            "device" to sanitizeSegment(Build.MODEL ?: "android"),
        )

        val renderedDirectory = sanitizePath(renderTokens(directoryTemplate.ifBlank { DEFAULT_TEMPLATE }, values))
        val renderedName = sanitizeSegment(renderTokens(filenamePattern.ifBlank { DEFAULT_FILENAME_PATTERN }, values))
        val pathSegments = listOf(basePath, renderedDirectory, renderedName).filter { it.isNotBlank() }
        return pathSegments.joinToString("/") { it.trim('/') }
    }

    fun renderPreservingSourcePath(
        basePath: String,
        mediaItem: MediaImageItem,
    ): String {
        val extension = extension(mediaItem)
        val fallbackNameStem = mediaItem.mediaId
            .substringAfterLast('/')
            .substringAfterLast(':')
            .substringBefore('?')
            .ifBlank { "item_${sanitizeSegment(mediaItem.mediaId).takeLast(12)}" }
        val fallbackName = "$fallbackNameStem.$extension"
        val filename = sanitizeSegment(mediaItem.displayName.orEmpty().ifBlank { fallbackName })
        val relativeDirectory = sanitizePath(mediaItem.relativePath.orEmpty())
        return listOf(basePath, relativeDirectory, filename)
            .filter { it.isNotBlank() }
            .joinToString("/") { it.trim('/') }
    }

    private fun renderTokens(template: String, values: Map<String, String>): String {
        var output = template
        values.forEach { (key, value) ->
            output = output.replace("{$key}", value)
        }
        return output
    }

    private fun extension(item: MediaImageItem): String {
        val fromName = item.displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName.isNotBlank()) return fromName
        return item.mimeType?.substringAfter('/', "bin")?.lowercase().orEmpty().ifBlank { "bin" }
    }

    private fun sanitizePath(path: String): String {
        return path.split('/', '\\').filter { it.isNotBlank() }.joinToString("/") { sanitizeSegment(it) }
    }

    private fun format(date: Date, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(date)

    internal fun sanitizeSegment(value: String): String {
        val trimmed = value.trim().ifBlank { "unknown" }
        return buildString(trimmed.length) {
            trimmed.forEach { ch ->
                append(
                    when {
                        ch.code < 32 -> '_'
                        ch in ILLEGAL_PATH_CHARS -> '_'
                        else -> ch
                    },
                )
            }
        }
    }
}

private fun Throwable?.toLogDetail(): String? {
    if (this == null) return null
    return "${this::class.simpleName}: ${this.message.orEmpty()}".trim()
}

private fun skezza.nasbox.data.smb.SmbConnectionFailure.toUserMessage(): String = when (this) {
    is skezza.nasbox.data.smb.SmbConnectionFailure.HostUnreachable -> "Server is unreachable."
    is skezza.nasbox.data.smb.SmbConnectionFailure.AuthenticationFailed -> "Authentication failed. Verify username and password."
    is skezza.nasbox.data.smb.SmbConnectionFailure.ShareNotFound -> "SMB share not found."
    is skezza.nasbox.data.smb.SmbConnectionFailure.RemotePermissionDenied -> "Remote permissions denied upload access."
    is skezza.nasbox.data.smb.SmbConnectionFailure.Timeout -> "Connection timed out while uploading."
    is skezza.nasbox.data.smb.SmbConnectionFailure.NetworkInterruption -> "Network interrupted during upload."
    is skezza.nasbox.data.smb.SmbConnectionFailure.Unknown -> "Unexpected SMB error during upload."
}
