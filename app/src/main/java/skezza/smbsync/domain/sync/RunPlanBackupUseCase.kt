package skezza.smbsync.domain.sync

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skezza.smbsync.data.db.BackupRecordEntity
import skezza.smbsync.data.db.RunEntity
import skezza.smbsync.data.db.RunLogEntity
import skezza.smbsync.data.media.MediaImageItem
import skezza.smbsync.data.media.MediaStoreDataSource
import skezza.smbsync.data.repository.BackupRecordRepository
import skezza.smbsync.data.repository.PlanRepository
import skezza.smbsync.data.repository.RunLogRepository
import skezza.smbsync.data.repository.RunRepository
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.data.smb.SmbClient
import skezza.smbsync.data.smb.SmbConnectionRequest
import skezza.smbsync.data.smb.toSmbConnectionFailure

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

        if (plan.sourceType != SOURCE_TYPE_ALBUM) {
            return@withContext finalizeRun(
                runId = runId,
                planId = planId,
                startedAt = startedAt,
                scanned = 0,
                uploaded = 0,
                skipped = 0,
                failed = 1,
                summaryError = "Only album-based plans are supported in Phase 5.",
                status = STATUS_FAILED,
                log = ::log,
            )
        }

        val server = serverRepository.getServer(plan.serverId)
        if (server == null) {
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

        val items = mediaStoreDataSource.listImagesForAlbum(plan.sourceAlbum)
        var uploadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var summaryError: String? = null

        log(SEVERITY_INFO, "Scan complete", "${items.size} items discovered")

        for (item in items) {
            val record = backupRecordRepository.findByPlanAndMediaItem(planId, item.mediaId)
            if (record != null) {
                skippedCount += 1
                continue
            }

            val remotePath = PathRenderer.render(
                basePath = server.basePath,
                directoryTemplate = plan.directoryTemplate,
                filenamePattern = plan.filenamePattern,
                mediaItem = item,
                fallbackAlbumToken = plan.sourceAlbum,
            )

            val stream = mediaStoreDataSource.openImageStream(item.mediaId)
            if (stream == null) {
                failedCount += 1
                val message = "Unable to read local media item ${item.mediaId}."
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
                uploadedCount += 1
                backupRecordRepository.create(
                    BackupRecordEntity(
                        planId = planId,
                        mediaItemId = item.mediaId,
                        remotePath = remotePath,
                        uploadedAtEpochMs = nowEpochMs(),
                    ),
                )
            } else {
                failedCount += 1
                val throwable = uploadResult.exceptionOrNull()
                val mapped = throwable?.toSmbConnectionFailure()
                val message = mapped?.toUserMessage() ?: "Upload failed for item ${item.mediaId}."
                summaryError = summaryError ?: message
                log(SEVERITY_ERROR, message, throwable?.message)
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
            scanned = items.size,
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
        return RunExecutionResult(runId = runId, status = status, uploadedCount = uploaded, skippedCount = skipped, failedCount = failed)
    }

    companion object {
        private const val STATUS_RUNNING = "RUNNING"
        private const val STATUS_SUCCESS = "SUCCESS"
        private const val STATUS_PARTIAL = "PARTIAL"
        private const val STATUS_FAILED = "FAILED"
        private const val SOURCE_TYPE_ALBUM = "ALBUM"
        private const val SEVERITY_INFO = "INFO"
        private const val SEVERITY_ERROR = "ERROR"
    }
}

data class RunExecutionResult(
    val runId: Long,
    val status: String,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
)

internal object PathRenderer {
    private const val DEFAULT_TEMPLATE = "{year}/{month}/{day}"
    private const val DEFAULT_FILENAME_PATTERN = "{timestamp}_{mediaId}.{ext}"

    fun render(
        basePath: String,
        directoryTemplate: String,
        filenamePattern: String,
        mediaItem: MediaImageItem,
        fallbackAlbumToken: String,
    ): String {
        val date = Date(mediaItem.dateTakenEpochMs ?: 0L)
        val safeExt = extension(mediaItem)
        val values = mapOf(
            "year" to format(date, "yyyy"),
            "month" to format(date, "MM"),
            "day" to format(date, "dd"),
            "time" to format(date, "HHmmss"),
            "timestamp" to format(date, "yyyyMMdd_HHmmss"),
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
        return trimmed
            .replace(Regex("[<>:\\\\"/|?*]"), "_")
            .replace(Regex("[\\p{Cntrl}]"), "_")
    }
}

private fun skezza.smbsync.data.smb.SmbConnectionFailure.toUserMessage(): String = when (this) {
    is skezza.smbsync.data.smb.SmbConnectionFailure.HostUnreachable -> "Server is unreachable."
    is skezza.smbsync.data.smb.SmbConnectionFailure.AuthenticationFailed -> "Authentication failed. Verify username and password."
    is skezza.smbsync.data.smb.SmbConnectionFailure.ShareNotFound -> "SMB share not found."
    is skezza.smbsync.data.smb.SmbConnectionFailure.RemotePermissionDenied -> "Remote permissions denied upload access."
    is skezza.smbsync.data.smb.SmbConnectionFailure.Timeout -> "Connection timed out while uploading."
    is skezza.smbsync.data.smb.SmbConnectionFailure.NetworkInterruption -> "Network interrupted during upload."
    is skezza.smbsync.data.smb.SmbConnectionFailure.Unknown -> "Unexpected SMB error during upload."
}
