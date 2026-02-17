package skezza.nasbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.domain.sync.RunPhase
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.ui.common.buildPlanDisplayInfoMap

class DashboardRunDetailViewModel(
    private val runId: Long,
    planRepository: PlanRepository,
    serverRepository: ServerRepository,
    runRepository: RunRepository,
    runLogRepository: RunLogRepository,
) : ViewModel() {

    val uiState: StateFlow<DashboardRunDetailUiState> = combine(
        planRepository.observePlans(),
        serverRepository.observeServers(),
        runRepository.observeRun(runId),
        runLogRepository.observeLogsForRunNewest(runId, RUN_LOG_LIMIT),
    ) { plans, servers, run, logsNewest ->
        val planInfoById = buildPlanDisplayInfoMap(plans, servers)
        val planName = run?.let { current ->
            planInfoById[current.planId]?.planName
        } ?: run?.let { "Job #${it.planId}" }
        val serverName = run?.let { planInfoById[it.planId]?.serverName }
        val mappedRun = run?.toSummary(planName.orEmpty(), serverName)
        val isActive = run?.phase
            ?.trim()
            ?.uppercase(Locale.US)
            ?.let { phase -> phase != RunPhase.TERMINAL }
            ?: false
        val logsAscending = logsNewest.sortedBy { it.timestampEpochMs }
        val fileActivities = buildFileActivities(logsAscending)
        val currentFile = if (isActive) {
            fileActivities.firstOrNull { it.status == DashboardRunFileStatus.PROCESSING }?.displayName
                ?: logsNewest.firstNotNullOfOrNull { it.toCurrentFileLabel() }
        } else {
            null
        }
        DashboardRunDetailUiState(
            run = mappedRun,
            currentFileLabel = currentFile,
            isActive = isActive,
            milestones = buildMilestones(mappedRun, logsAscending),
            lastAction = buildLastAction(logsNewest),
            fileActivities = fileActivities,
            rawLogs = logsNewest.map { it.toLogItem() },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DashboardRunDetailUiState(),
    )

    private fun RunEntity.toSummary(planName: String, serverName: String?): DashboardRunDetailSummary = DashboardRunDetailSummary(
        runId = runId,
        planName = planName,
        serverName = serverName,
        status = status,
        triggerSource = triggerSource,
        executionMode = executionMode,
        phase = phase,
        startedAtEpochMs = startedAtEpochMs,
        finishedAtEpochMs = finishedAtEpochMs,
        scannedCount = scannedCount,
        uploadedCount = uploadedCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        summaryError = summaryError,
    )

    private fun RunLogEntity.toLogItem(): DashboardRunDetailLogItem = DashboardRunDetailLogItem(
        logId = logId,
        timestampEpochMs = timestampEpochMs,
        severity = severity,
        message = message,
        detail = detail,
    )

    private fun buildMilestones(
        run: DashboardRunDetailSummary?,
        logsAscending: List<RunLogEntity>,
    ): List<DashboardRunDetailMilestone> {
        if (run == null) return emptyList()
        val milestones = mutableListOf<DashboardRunDetailMilestone>()
        milestones += DashboardRunDetailMilestone(
            label = "Started",
            timestampEpochMs = run.startedAtEpochMs,
        )

        val reachedProgress = mutableSetOf<Int>()
        logsAscending.forEach { log ->
            when (log.message) {
                MESSAGE_SCAN_COMPLETE -> milestones += DashboardRunDetailMilestone("Scan complete", log.timestampEpochMs)
                MESSAGE_STOP_REQUESTED -> milestones += DashboardRunDetailMilestone("Stop requested", log.timestampEpochMs)
                MESSAGE_CANCELLATION_ACKNOWLEDGED ->
                    milestones += DashboardRunDetailMilestone("Cancel acknowledged", log.timestampEpochMs)
                MESSAGE_INTERRUPTED ->
                    milestones += DashboardRunDetailMilestone("Interrupted", log.timestampEpochMs)
                MESSAGE_FINALIZED_CANCELED ->
                    milestones += DashboardRunDetailMilestone("Recovered as canceled", log.timestampEpochMs)
                MESSAGE_CHUNK_PAUSED ->
                    milestones += DashboardRunDetailMilestone("Chunk paused for system window", log.timestampEpochMs)
                MESSAGE_FINISHED -> {
                    val finishedLabel = statusLabelFromRunFinishedDetail(log.detail)
                    milestones += DashboardRunDetailMilestone(finishedLabel, log.timestampEpochMs)
                }
                MESSAGE_PROGRESS -> {
                    val ratio = parseProgressRatio(log.detail)
                    val checkpoints = listOf(25, 50, 75)
                    checkpoints.forEach { checkpoint ->
                        if (ratio >= checkpoint && checkpoint !in reachedProgress) {
                            reachedProgress += checkpoint
                            milestones += DashboardRunDetailMilestone("$checkpoint% processed", log.timestampEpochMs)
                        }
                    }
                }
                else -> {
                    if (log.message.startsWith(MESSAGE_RESUMED_ATTEMPT_PREFIX)) {
                        milestones += DashboardRunDetailMilestone(log.message, log.timestampEpochMs)
                    }
                }
            }
        }

        val hasFinish = milestones.any { it.label.startsWith("Finished", ignoreCase = true) }
        if (!hasFinish && run.finishedAtEpochMs != null) {
            milestones += DashboardRunDetailMilestone(
                label = "Finished (${runStatusLabel(run.status)})",
                timestampEpochMs = run.finishedAtEpochMs,
            )
        }
        return milestones
            .sortedBy { it.timestampEpochMs }
            .distinctBy { "${it.label}|${it.timestampEpochMs}" }
    }

    private fun buildLastAction(logsNewest: List<RunLogEntity>): DashboardRunDetailLastAction? {
        val candidate = logsNewest.firstOrNull { log ->
            log.message !in NOISY_MESSAGES
        } ?: return null
        val label = extractDisplayLabel(candidate).ifBlank {
            when (candidate.message) {
                MESSAGE_FINISHED -> statusLabelFromRunFinishedDetail(candidate.detail)
                else -> candidate.message
            }
        }
        return DashboardRunDetailLastAction(
            label = label,
            timestampEpochMs = candidate.timestampEpochMs,
        )
    }

    private fun buildFileActivities(logsAscending: List<RunLogEntity>): List<DashboardRunFileActivity> {
        val byMediaId = linkedMapOf<String, MutableFileActivity>()
        val displayByMediaId = mutableMapOf<String, String>()

        fun upsert(
            mediaId: String,
            displayName: String,
            timestampEpochMs: Long,
            status: DashboardRunFileStatus,
            detail: String?,
        ) {
            val normalizedDisplay = displayName.ifBlank {
                extractFilename(mediaId) ?: mediaId
            }
            displayByMediaId[mediaId] = normalizedDisplay
            val current = byMediaId[mediaId]
            if (current == null) {
                byMediaId[mediaId] = MutableFileActivity(
                    mediaId = mediaId,
                    displayName = normalizedDisplay,
                    status = status,
                    timestampEpochMs = timestampEpochMs,
                    detail = detail,
                )
            } else if (timestampEpochMs >= current.timestampEpochMs) {
                current.displayName = normalizedDisplay
                current.status = status
                current.timestampEpochMs = timestampEpochMs
                current.detail = detail
            }
        }

        logsAscending.forEach { log ->
            val mediaIdFromDetail = extractMediaId(log.detail)
            val mediaIdFromMessage = extractMediaIdFromMessage(log.message)
            val mediaId = mediaIdFromDetail ?: mediaIdFromMessage
            val displayName = resolveDisplayName(
                mediaId = mediaId,
                detail = log.detail,
                fallbackByMediaId = displayByMediaId,
            )
            when {
                log.message == MESSAGE_PROCESSING_ITEM && mediaId != null -> {
                    upsert(
                        mediaId = mediaId,
                        displayName = displayName,
                        timestampEpochMs = log.timestampEpochMs,
                        status = DashboardRunFileStatus.PROCESSING,
                        detail = extractReasonCode(log.detail),
                    )
                }

                log.message == MESSAGE_UPLOADED_ITEM && mediaId != null -> {
                    upsert(
                        mediaId = mediaId,
                        displayName = displayName,
                        timestampEpochMs = log.timestampEpochMs,
                        status = DashboardRunFileStatus.UPLOADED,
                        detail = extractReasonCode(log.detail),
                    )
                }

                log.message == MESSAGE_SKIPPED_ITEM && mediaId != null -> {
                    upsert(
                        mediaId = mediaId,
                        displayName = displayName,
                        timestampEpochMs = log.timestampEpochMs,
                        status = DashboardRunFileStatus.SKIPPED,
                        detail = extractReasonCode(log.detail) ?: formatReasonLabel(REASON_ALREADY_BACKED_UP),
                    )
                }

                log.severity.equals("ERROR", ignoreCase = true) && mediaId != null -> {
                    upsert(
                        mediaId = mediaId,
                        displayName = displayName,
                        timestampEpochMs = log.timestampEpochMs,
                        status = DashboardRunFileStatus.FAILED,
                        detail = deriveFailureReasonCode(log),
                    )
                }
            }
        }

        return byMediaId.values
            .map {
                DashboardRunFileActivity(
                    mediaId = it.mediaId,
                    displayName = it.displayName,
                    status = it.status,
                    timestampEpochMs = it.timestampEpochMs,
                    detail = it.detail,
                )
            }
            .sortedByDescending { it.timestampEpochMs }
    }

    private fun statusLabelFromRunFinishedDetail(detail: String?): String {
        val status = Regex("""status=([A-Z_]+)""")
            .find(detail.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?: return "Finished"
        return "Finished (${runStatusLabel(status)})"
    }

    private fun parseProgressRatio(detail: String?): Int {
        val text = detail.orEmpty()
        val fraction = Regex("""processed=(\d+)/(\d+)""").find(text)
        if (fraction != null) {
            val done = fraction.groupValues[1].toIntOrNull() ?: return 0
            val total = fraction.groupValues[2].toIntOrNull() ?: return 0
            if (total <= 0) return 0
            return ((done * 100f) / total.toFloat()).toInt()
        }
        return 0
    }

    private fun RunLogEntity.toCurrentFileLabel(): String? {
        if (message != MESSAGE_PROCESSING_ITEM) return null
        return resolveDisplayName(
            mediaId = extractMediaId(detail),
            detail = detail,
            fallbackByMediaId = emptyMap(),
        )
    }

    private fun extractDisplayLabel(log: RunLogEntity): String {
        val mediaId = extractMediaId(log.detail) ?: extractMediaIdFromMessage(log.message)
        return resolveDisplayName(
            mediaId = mediaId,
            detail = log.detail,
            fallbackByMediaId = emptyMap(),
        )
    }

    private fun resolveDisplayName(
        mediaId: String?,
        detail: String?,
        fallbackByMediaId: Map<String, String>,
    ): String {
        val remotePath = extractRemotePath(detail)
        val detailDisplayName = extractFilename(extractDisplayName(detail))
        if (!detailDisplayName.isNullOrBlank()) {
            return appendExtensionIfMissing(detailDisplayName, remotePath)
        }
        val remoteName = extractFilename(remotePath)
        if (!remoteName.isNullOrBlank()) return remoteName
        val fallbackName = mediaId?.let { fallbackByMediaId[it] }
        if (!fallbackName.isNullOrBlank()) return fallbackName
        val mediaIdFilename = extractFilename(mediaId)
        if (!mediaIdFilename.isNullOrBlank()) return mediaIdFilename
        return mediaId.orEmpty()
    }

    private fun appendExtensionIfMissing(baseName: String, remotePath: String?): String {
        if (hasExtension(baseName)) return baseName
        val extension = extractExtension(remotePath)
        if (extension.isNullOrBlank()) return baseName
        val suffix = ".${extension}"
        if (baseName.endsWith(suffix, ignoreCase = true)) return baseName
        return "$baseName$suffix"
    }

    private fun hasExtension(value: String): Boolean {
        val dotIndex = value.lastIndexOf('.')
        return dotIndex > 0 && dotIndex < value.length - 1
    }

    private fun extractExtension(value: String?): String? {
        val filename = extractFilename(value) ?: return null
        val dotIndex = filename.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex >= filename.length - 1) return null
        return filename.substring(dotIndex + 1)
    }

    private fun extractMediaId(detail: String?): String? =
        Regex("""mediaId=([^\s]+)""")
            .find(detail.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun extractDisplayName(detail: String?): String? {
        val source = detail.orEmpty()
        val afterDisplayName = source
            .substringAfter("displayName=", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?: return null
        val value = afterDisplayName
            .substringBefore(" remotePath=")
            .substringBefore(" dest=")
            .trim()
        return value.takeIf { it.isNotBlank() }
    }

    private fun extractRemotePath(detail: String?): String? {
        return REMOTE_PATH_REGEX.find(detail.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractReasonCode(detail: String?): String? {
        return parseReasonCode(detail)?.let { formatReasonLabel(it) }
    }

    private fun parseReasonCode(detail: String?): String? {
        val value = Regex("""reason=([^\s]+)""")
            .find(detail.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.trim('"', '\'')
            ?.trimEnd(',', '.', ';', ')', ']')
            ?.takeIf { it.isNotBlank() }
        return value
    }

    private fun deriveFailureReasonCode(log: RunLogEntity): String {
        parseReasonCode(log.detail)?.let { return formatReasonLabel(it) }
        val reasonCode = when {
            log.message.startsWith("Unable to read source item ", ignoreCase = true) -> REASON_SOURCE_UNREADABLE
            log.message.startsWith("Upload failed for item ", ignoreCase = true) -> REASON_UPLOAD_FAILED
            log.message.startsWith("Uploaded item ", ignoreCase = true) &&
                log.message.contains("failed to persist backup proof", ignoreCase = true) ->
                REASON_BACKUP_RECORD_PERSIST_FAILED
            else -> REASON_FAILED
        }
        return formatReasonLabel(reasonCode)
    }

    private fun formatReasonLabel(reasonCode: String): String {
        val normalized = reasonCode.trim().lowercase(Locale.US)
        return when (normalized) {
            REASON_ALREADY_BACKED_UP -> "Already backed up"
            REASON_UPLOAD_FAILED -> "Upload failed"
            REASON_SOURCE_UNREADABLE -> "Source unreadable"
            REASON_BACKUP_RECORD_PERSIST_FAILED -> "Backup record persist failed"
            REASON_FAILED -> "Failed"
            else -> humanizeReasonCode(normalized)
        }
    }

    private fun humanizeReasonCode(reasonCode: String): String {
        val words = reasonCode
            .replace('-', '_')
            .split('_')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return reasonCode
        val sentence = words.joinToString(" ")
        return sentence.replaceFirstChar { first ->
            if (first.isLowerCase()) {
                first.titlecase(Locale.US)
            } else {
                first.toString()
            }
        }
    }

    private fun extractFilename(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutQuery = normalized
            .substringBefore('?')
            .substringBefore('#')
        val normalizedPath = extractDocumentPathFromContentUri(withoutQuery)
            ?: decodeUriComponent(withoutQuery)
        val filename = normalizedPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')
            .trim()
        return filename.takeIf { it.isNotBlank() }
    }

    private fun extractDocumentPathFromContentUri(value: String): String? {
        if (!value.startsWith(CONTENT_URI_SCHEME, ignoreCase = true)) return null
        val encodedDocumentId = extractDocumentId(value, DOCUMENT_MARKER)
            ?: extractDocumentId(value, TREE_MARKER)
            ?: return null
        val decodedDocumentId = decodeUriComponent(encodedDocumentId)
        return decodedDocumentId.substringAfter(':', missingDelimiterValue = decodedDocumentId)
    }

    private fun extractDocumentId(value: String, marker: String): String? {
        val remainder = value.substringAfter(marker, missingDelimiterValue = "")
        if (remainder.isBlank()) return null
        return remainder
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .takeIf { it.isNotBlank() }
    }

    private fun decodeUriComponent(value: String): String {
        if (!value.contains('%')) return value
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun extractMediaIdFromMessage(message: String): String? {
        val patterns = listOf(
            Regex("""^Upload failed for item (.+)\.$"""),
            Regex("""^Unable to read source item (.+)\.$"""),
            Regex("""^Uploaded item (.+), but failed to persist backup proof\.$"""),
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(message)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private data class MutableFileActivity(
        val mediaId: String,
        var displayName: String,
        var status: DashboardRunFileStatus,
        var timestampEpochMs: Long,
        var detail: String?,
    )

    companion object {
        private const val RUN_LOG_LIMIT = 300
        private val NOISY_MESSAGES = setOf("Run progress")
        private val REMOTE_PATH_REGEX = Regex("""(?:remotePath|dest)=([^\s]+)""")
        private const val CONTENT_URI_SCHEME = "content://"
        private const val DOCUMENT_MARKER = "/document/"
        private const val TREE_MARKER = "/tree/"

        private const val MESSAGE_PROCESSING_ITEM = "Processing item"
        private const val MESSAGE_UPLOADED_ITEM = "Uploaded item"
        private const val MESSAGE_SKIPPED_ITEM = "Skipped item"
        private const val MESSAGE_PROGRESS = "Run progress"
        private const val MESSAGE_SCAN_COMPLETE = "Scan complete"
        private const val MESSAGE_STOP_REQUESTED = "Stop requested"
        private const val MESSAGE_CANCELLATION_ACKNOWLEDGED = "Run cancellation acknowledged"
        private const val MESSAGE_INTERRUPTED = "Run marked as interrupted"
        private const val MESSAGE_FINALIZED_CANCELED = "Run finalized as canceled"
        private const val MESSAGE_CHUNK_PAUSED = "Chunk paused for system window"
        private const val MESSAGE_RESUMED_ATTEMPT_PREFIX = "Resumed attempt #"
        private const val MESSAGE_FINISHED = "Run finished"

        private const val REASON_ALREADY_BACKED_UP = "already_backed_up"
        private const val REASON_UPLOAD_FAILED = "upload_failed"
        private const val REASON_SOURCE_UNREADABLE = "source_unreadable"
        private const val REASON_BACKUP_RECORD_PERSIST_FAILED = "backup_record_persist_failed"
        private const val REASON_FAILED = "failed"

        fun factory(
            runId: Long,
            planRepository: PlanRepository,
            serverRepository: ServerRepository,
            runRepository: RunRepository,
            runLogRepository: RunLogRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(DashboardRunDetailViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return DashboardRunDetailViewModel(
                        runId = runId,
                        planRepository = planRepository,
                        serverRepository = serverRepository,
                        runRepository = runRepository,
                        runLogRepository = runLogRepository,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
        }
    }
}

data class DashboardRunDetailUiState(
    val run: DashboardRunDetailSummary? = null,
    val currentFileLabel: String? = null,
    val isActive: Boolean = false,
    val milestones: List<DashboardRunDetailMilestone> = emptyList(),
    val lastAction: DashboardRunDetailLastAction? = null,
    val fileActivities: List<DashboardRunFileActivity> = emptyList(),
    val rawLogs: List<DashboardRunDetailLogItem> = emptyList(),
)

data class DashboardRunDetailSummary(
    val runId: Long,
    val planName: String,
    val serverName: String?,
    val status: String,
    val triggerSource: String,
    val executionMode: String,
    val phase: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val scannedCount: Int,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String?,
)

data class DashboardRunDetailMilestone(
    val label: String,
    val timestampEpochMs: Long,
)

data class DashboardRunDetailLastAction(
    val label: String,
    val timestampEpochMs: Long,
)

data class DashboardRunFileActivity(
    val mediaId: String,
    val displayName: String,
    val status: DashboardRunFileStatus,
    val timestampEpochMs: Long,
    val detail: String?,
)

enum class DashboardRunFileStatus {
    PROCESSING,
    UPLOADED,
    SKIPPED,
    FAILED,
}

data class DashboardRunDetailLogItem(
    val logId: Long,
    val timestampEpochMs: Long,
    val severity: String,
    val message: String,
    val detail: String?,
)

internal fun runStatusLabel(status: String): String = when (status.uppercase(Locale.US)) {
    RunStatus.SUCCESS -> "Success"
    RunStatus.PARTIAL -> "Partial"
    RunStatus.FAILED -> "Failed"
    RunStatus.RUNNING -> "Running"
    RunStatus.CANCEL_REQUESTED -> "Cancel requested"
    RunStatus.CANCELED -> "Canceled"
    RunStatus.INTERRUPTED -> "Interrupted"
    else -> status
}
