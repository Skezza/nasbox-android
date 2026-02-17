package skezza.nasbox.domain.sync

import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skezza.nasbox.data.db.BackupRecordEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.media.FolderScanProgress
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

    suspend operator fun invoke(
        planId: Long,
        triggerSource: String = RunTriggerSource.MANUAL,
        runId: Long? = null,
        executionMode: String? = null,
        continuationCursor: String? = null,
        progressListener: (suspend (RunProgressSnapshot) -> Unit)? = null,
    ): RunExecutionResult = withContext(Dispatchers.IO) {
        val normalizedTriggerSource = normalizeTriggerSource(triggerSource)
        val normalizedExecutionMode = normalizeExecutionMode(executionMode, normalizedTriggerSource)

        val existingRun = runId
            ?.takeIf { it > 0L }
            ?.let { persistedRunId -> runRepository.getRun(persistedRunId) }
            ?.takeIf { it.planId == planId }

        if (existingRun?.finishedAtEpochMs != null) {
            return@withContext existingRun.toExecutionResult(pausedForContinuation = false)
        }

        if (existingRun == null) {
            val activeRunForPlan = findActiveRunForPlan(planId)
            if (activeRunForPlan != null) {
                val pausedForContinuation = activeRunForPlan.phase.trim().uppercase(Locale.US) == RunPhase.WAITING_RETRY &&
                    !activeRunForPlan.continuationCursor.isNullOrBlank()
                progressListener?.invoke(activeRunForPlan.toProgressSnapshot())
                return@withContext activeRunForPlan.toExecutionResult(pausedForContinuation = pausedForContinuation)
            }
        }

        val activeRun = if (existingRun == null) {
            finalizePriorActiveRunsForPlan(planId)
            createNewRun(
                planId = planId,
                triggerSource = normalizedTriggerSource,
                executionMode = normalizedExecutionMode,
                continuationCursor = continuationCursor,
            )
        } else {
            existingRun
        }

        val runIdValue = activeRun.runId
        val startedAt = activeRun.startedAtEpochMs
        var scannedCount = activeRun.scannedCount
        var uploadedCount = activeRun.uploadedCount
        var skippedCount = activeRun.skippedCount
        var failedCount = activeRun.failedCount
        var summaryError: String? = activeRun.summaryError
        var resumeCount = activeRun.resumeCount
        var lastProgressAt = activeRun.lastProgressAtEpochMs.takeIf { it > 0L } ?: activeRun.startedAtEpochMs
        var currentCursor = continuationCursor ?: activeRun.continuationCursor
        var cancellationLogged = false

        suspend fun log(severity: String, message: String, detail: String? = null) {
            val formatted = "[runId=$runIdValue planId=$planId] $message"
            if (severity == SEVERITY_ERROR) {
                Log.e(LOG_TAG, formatted + detail?.let { " | $it" }.orEmpty())
            } else {
                Log.i(LOG_TAG, formatted + detail?.let { " | $it" }.orEmpty())
            }
            runLogRepository.createLog(
                RunLogEntity(
                    runId = runIdValue,
                    timestampEpochMs = nowEpochMs(),
                    severity = severity,
                    message = message,
                    detail = detail,
                ),
            )
        }

        fun metric(event: String, detail: String? = null) {
            val suffix = detail?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            Log.i(LOG_TAG, "metric=$event runId=$runIdValue planId=$planId$suffix")
        }

        suspend fun emitProgressSnapshot(
            status: String,
            phase: String,
            continuationCursorValue: String?,
        ) {
            progressListener?.invoke(
                RunProgressSnapshot(
                    runId = runIdValue,
                    planId = planId,
                    status = status,
                    phase = phase,
                    scannedCount = scannedCount,
                    uploadedCount = uploadedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    continuationCursor = continuationCursorValue,
                    resumeCount = resumeCount,
                ),
            )
        }

        suspend fun persistSnapshot(
            phase: String,
            continuationCursorValue: String?,
        ) {
            val heartbeatAt = nowEpochMs()
            val persistedStatus = readPersistedStatus(runIdValue)
            if (persistedStatus == RunStatus.CANCELED) {
                return
            }
            val persisted = if (persistedStatus == RunStatus.CANCEL_REQUESTED) {
                RunStatus.CANCEL_REQUESTED
            } else {
                RunStatus.RUNNING
            }
            runRepository.updateRun(
                RunEntity(
                    runId = runIdValue,
                    planId = planId,
                    status = persisted,
                    startedAtEpochMs = startedAt,
                    finishedAtEpochMs = null,
                    heartbeatAtEpochMs = heartbeatAt,
                    scannedCount = scannedCount,
                    uploadedCount = uploadedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    summaryError = summaryError,
                    triggerSource = normalizedTriggerSource,
                    executionMode = normalizedExecutionMode,
                    phase = phase,
                    continuationCursor = continuationCursorValue,
                    resumeCount = resumeCount,
                    lastProgressAtEpochMs = lastProgressAt,
                ),
            )
            emitProgressSnapshot(
                status = persisted,
                phase = phase,
                continuationCursorValue = continuationCursorValue,
            )
        }

        suspend fun finalizeCanceledIfRequested(): RunExecutionResult? {
            val persistedStatus = readPersistedStatus(runIdValue)
            if (persistedStatus == RunStatus.CANCELED) {
                if (!cancellationLogged) {
                    cancellationLogged = true
                    log(SEVERITY_INFO, "Run cancellation acknowledged")
                }
                emitProgressSnapshot(
                    status = RunStatus.CANCELED,
                    phase = RunPhase.TERMINAL,
                    continuationCursorValue = null,
                )
                return RunExecutionResult(
                    runId = runIdValue,
                    status = RunStatus.CANCELED,
                    uploadedCount = uploadedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    summaryError = summaryError ?: DEFAULT_CANCELED_SUMMARY,
                    phase = RunPhase.TERMINAL,
                    continuationCursor = null,
                    resumeCount = resumeCount,
                    lastProgressAtEpochMs = lastProgressAt,
                    pausedForContinuation = false,
                )
            }
            if (persistedStatus != RunStatus.CANCEL_REQUESTED) return null

            if (!cancellationLogged) {
                cancellationLogged = true
                log(SEVERITY_INFO, "Run cancellation acknowledged")
            }
            return finalizeRun(
                runId = runIdValue,
                planId = planId,
                startedAt = startedAt,
                scanned = scannedCount,
                uploaded = uploadedCount,
                skipped = skippedCount,
                failed = failedCount,
                summaryError = summaryError ?: DEFAULT_CANCELED_SUMMARY,
                status = RunStatus.CANCELED,
                triggerSource = normalizedTriggerSource,
                executionMode = normalizedExecutionMode,
                resumeCount = resumeCount,
                lastProgressAt = lastProgressAt,
                log = ::log,
                progressCallback = progressListener,
            )
        }

        suspend fun emitProgress(force: Boolean = false) {
            val processedCount = uploadedCount + skippedCount + failedCount
            if (!force && processedCount % PROGRESS_LOG_INTERVAL != 0 && processedCount != scannedCount) {
                return
            }
            val processedForDisplay = if (scannedCount > 0) minOf(scannedCount, processedCount) else processedCount
            val progressSuffix = if (scannedCount > 0) "$processedForDisplay/$scannedCount" else processedCount.toString()
            log(
                SEVERITY_INFO,
                "Run progress",
                "processed=$progressSuffix uploaded=$uploadedCount skipped=$skippedCount failed=$failedCount",
            )
        }

        val isNewLogicalRun = existingRun == null
        if (isNewLogicalRun) {
            log(SEVERITY_INFO, "Run started")
            metric("run_started", "executionMode=$normalizedExecutionMode trigger=$normalizedTriggerSource")
            emitProgressSnapshot(
                status = RunStatus.RUNNING,
                phase = RunPhase.RUNNING,
                continuationCursorValue = currentCursor,
            )
        } else {
            if (normalizedExecutionMode == RunExecutionMode.BACKGROUND) {
                resumeCount += 1
                log(SEVERITY_INFO, "Resumed attempt #$resumeCount")
                metric("chunk_resumed", "resumeCount=$resumeCount")
            }
            lastProgressAt = maxOf(lastProgressAt, nowEpochMs())
            persistSnapshot(
                phase = RunPhase.RUNNING,
                continuationCursorValue = currentCursor,
            )
        }

        finalizeCanceledIfRequested()?.let { return@withContext it }

        val plan = planRepository.getPlan(planId)
        if (plan == null) {
            log(SEVERITY_ERROR, "Run aborted: plan does not exist", "planId=$planId")
            metric("run_interrupted", "reason=plan_missing")
            return@withContext finalizeRun(
                runId = runIdValue,
                planId = planId,
                startedAt = startedAt,
                scanned = scannedCount,
                uploaded = uploadedCount,
                skipped = skippedCount,
                failed = failedCount + 1,
                summaryError = "Job no longer exists.",
                status = RunStatus.FAILED,
                triggerSource = normalizedTriggerSource,
                executionMode = normalizedExecutionMode,
                resumeCount = resumeCount,
                lastProgressAt = lastProgressAt,
                log = ::log,
                progressCallback = progressListener,
            )
        }

        if (!plan.enabled) {
            log(SEVERITY_ERROR, "Run aborted: plan is disabled", "planId=${plan.planId}")
            metric("run_interrupted", "reason=plan_disabled")
            return@withContext finalizeRun(
                runId = runIdValue,
                planId = planId,
                startedAt = startedAt,
                scanned = scannedCount,
                uploaded = uploadedCount,
                skipped = skippedCount,
                failed = failedCount + 1,
                summaryError = "Job is disabled.",
                status = RunStatus.FAILED,
                triggerSource = normalizedTriggerSource,
                executionMode = normalizedExecutionMode,
                resumeCount = resumeCount,
                lastProgressAt = lastProgressAt,
                log = ::log,
                progressCallback = progressListener,
            )
        }

        val sourceType = normalizeSourceType(plan.sourceType)
        if (sourceType == null) {
            log(SEVERITY_ERROR, "Run aborted: unsupported source type", "sourceType=${plan.sourceType}")
            metric("run_interrupted", "reason=unsupported_source")
            return@withContext finalizeRun(
                runId = runIdValue,
                planId = planId,
                startedAt = startedAt,
                scanned = scannedCount,
                uploaded = uploadedCount,
                skipped = skippedCount,
                failed = failedCount + 1,
                summaryError = "Unsupported source mode: ${plan.sourceType}.",
                status = RunStatus.FAILED,
                triggerSource = normalizedTriggerSource,
                executionMode = normalizedExecutionMode,
                resumeCount = resumeCount,
                lastProgressAt = lastProgressAt,
                log = ::log,
                progressCallback = progressListener,
            )
        }

        val server = serverRepository.getServer(plan.serverId)
        if (server == null) {
            log(SEVERITY_ERROR, "Run aborted: destination server missing", "serverId=${plan.serverId}")
            metric("run_interrupted", "reason=server_missing")
            return@withContext finalizeRun(
                runId = runIdValue,
                planId = planId,
                startedAt = startedAt,
                scanned = scannedCount,
                uploaded = uploadedCount,
                skipped = skippedCount,
                failed = failedCount + 1,
                summaryError = "Destination server not found.",
                status = RunStatus.FAILED,
                triggerSource = normalizedTriggerSource,
                executionMode = normalizedExecutionMode,
                resumeCount = resumeCount,
                lastProgressAt = lastProgressAt,
                log = ::log,
                progressCallback = progressListener,
            )
        }

        val password = credentialStore.loadPassword(server.credentialAlias)
        if (password == null) {
            log(
                SEVERITY_ERROR,
                "Run aborted: credentials unavailable",
                "serverId=${server.serverId} alias=${server.credentialAlias}",
            )
            metric("run_interrupted", "reason=credentials_missing")
            return@withContext finalizeRun(
                runId = runIdValue,
                planId = planId,
                startedAt = startedAt,
                scanned = scannedCount,
                uploaded = uploadedCount,
                skipped = skippedCount,
                failed = failedCount + 1,
                summaryError = "Server credentials unavailable. Re-save this server.",
                status = RunStatus.FAILED,
                triggerSource = normalizedTriggerSource,
                executionMode = normalizedExecutionMode,
                resumeCount = resumeCount,
                lastProgressAt = lastProgressAt,
                log = ::log,
                progressCallback = progressListener,
            )
        }

        val connection = SmbConnectionRequest(
            host = server.host,
            shareName = server.shareName,
            username = server.username,
            password = password,
        )
        finalizeCanceledIfRequested()?.let { return@withContext it }

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
                        items = mediaStoreDataSource.listImagesForAlbum(plan.sourceAlbum, plan.includeVideos),
                    )
                }.getOrElse { throwable ->
                    val message = "Unable to scan local media. Check photo permission and album availability."
                    log(SEVERITY_ERROR, message, throwable.message)
                    metric("run_interrupted", "reason=scan_album_failed")
                    return@withContext finalizeRun(
                        runId = runIdValue,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = scannedCount,
                        uploaded = uploadedCount,
                        skipped = skippedCount,
                        failed = failedCount + 1,
                        summaryError = message,
                        status = RunStatus.FAILED,
                        triggerSource = normalizedTriggerSource,
                        executionMode = normalizedExecutionMode,
                        resumeCount = resumeCount,
                        lastProgressAt = lastProgressAt,
                        log = ::log,
                progressCallback = progressListener,
                    )
                }
            }

            SOURCE_TYPE_FOLDER -> {
                if (plan.folderPath.isBlank()) {
                    val message = "Folder source path is missing. Re-save this plan."
                    log(SEVERITY_ERROR, "Run aborted: folder source path missing")
                    metric("run_interrupted", "reason=folder_path_missing")
                    return@withContext finalizeRun(
                        runId = runIdValue,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = scannedCount,
                        uploaded = uploadedCount,
                        skipped = skippedCount,
                        failed = failedCount + 1,
                        summaryError = message,
                        status = RunStatus.FAILED,
                        triggerSource = normalizedTriggerSource,
                        executionMode = normalizedExecutionMode,
                        resumeCount = resumeCount,
                        lastProgressAt = lastProgressAt,
                        log = ::log,
                progressCallback = progressListener,
                    )
                }

                log(SEVERITY_INFO, "Scanning folder source", "folderPath=${plan.folderPath}")
                var scanCancelRequested = false
                var lastScanProgressPersistAt = 0L
                var lastScanStatusCheckAt = 0L

                suspend fun onFolderScanProgress(progress: FolderScanProgress) {
                    scannedCount = maxOf(scannedCount, progress.discoveredFiles)
                    val now = nowEpochMs()
                    if (progress.completed || (now - lastScanProgressPersistAt) >= SCAN_PROGRESS_PERSIST_INTERVAL_MS) {
                        lastProgressAt = now
                        persistSnapshot(
                            phase = RunPhase.RUNNING,
                            continuationCursorValue = currentCursor,
                        )
                        lastScanProgressPersistAt = now
                    }
                    if (progress.completed || (now - lastScanStatusCheckAt) >= SCAN_CANCEL_CHECK_INTERVAL_MS) {
                        val status = readPersistedStatus(runIdValue)
                        if (status == RunStatus.CANCEL_REQUESTED || status == RunStatus.CANCELED) {
                            scanCancelRequested = true
                        }
                        lastScanStatusCheckAt = now
                    }
                }

                val folderItems = try {
                    mediaStoreDataSource.listFilesForFolder(
                        folderPathOrUri = plan.folderPath,
                        onProgress = ::onFolderScanProgress,
                        shouldContinue = { !scanCancelRequested },
                    )
                } catch (canceled: CancellationException) {
                    if (scanCancelRequested) {
                        finalizeCanceledIfRequested()?.let { return@withContext it }
                    }
                    throw canceled
                } catch (throwable: Throwable) {
                    val message = "Unable to scan selected folder source. Confirm access to the selected folder."
                    log(SEVERITY_ERROR, message, throwable.message)
                    metric("run_interrupted", "reason=scan_folder_failed")
                    return@withContext finalizeRun(
                        runId = runIdValue,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = scannedCount,
                        uploaded = uploadedCount,
                        skipped = skippedCount,
                        failed = failedCount + 1,
                        summaryError = message,
                        status = RunStatus.FAILED,
                        triggerSource = normalizedTriggerSource,
                        executionMode = normalizedExecutionMode,
                        resumeCount = resumeCount,
                        lastProgressAt = lastProgressAt,
                        log = ::log,
                        progressCallback = progressListener,
                    )
                }
                SourceScanResult(items = folderItems)
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
                    metric("run_interrupted", "reason=scan_full_device_failed")
                    return@withContext finalizeRun(
                        runId = runIdValue,
                        planId = planId,
                        startedAt = startedAt,
                        scanned = scannedCount,
                        uploaded = uploadedCount,
                        skipped = skippedCount,
                        failed = failedCount + 1,
                        summaryError = message,
                        status = RunStatus.FAILED,
                        triggerSource = normalizedTriggerSource,
                        executionMode = normalizedExecutionMode,
                        resumeCount = resumeCount,
                        lastProgressAt = lastProgressAt,
                        log = ::log,
                progressCallback = progressListener,
                    )
                }
            }

            else -> error("Unexpected source type: $sourceType")
        }

        val orderedItems = scanResult.items.sortedWith(
            compareBy<MediaImageItem> { it.relativePath.orEmpty().lowercase(Locale.US) }
                .thenBy { it.displayName.orEmpty().lowercase(Locale.US) }
                .thenBy { it.mediaId },
        )

        scannedCount = maxOf(scannedCount, orderedItems.size)
        val shouldApplyScanWarnings = failedCount == 0 &&
            uploadedCount == 0 &&
            skippedCount == 0 &&
            currentCursor.isNullOrBlank()

        if (shouldApplyScanWarnings) {
            scanResult.warnings.forEach { warning ->
                failedCount += 1
                summaryError = summaryError ?: warning
                log(SEVERITY_ERROR, warning)
            }
        }

        log(
            SEVERITY_INFO,
            "Scan complete",
            "source=$sourceType discovered=${orderedItems.size} warnings=${scanResult.warnings.size}",
        )
        lastProgressAt = nowEpochMs()
        persistSnapshot(
            phase = RunPhase.RUNNING,
            continuationCursorValue = currentCursor,
        )
        emitProgress(force = true)
        finalizeCanceledIfRequested()?.let { return@withContext it }

        val startIndex = parseContinuationCursor(currentCursor, orderedItems.size)
        if (startIndex > 0) {
            log(
                SEVERITY_INFO,
                "Continuation checkpoint restored",
                "cursor=$startIndex total=${orderedItems.size}",
            )
        }

        var index = startIndex
        var processedInChunk = 0
        val chunkStartedAt = nowEpochMs()

        while (index < orderedItems.size) {
            finalizeCanceledIfRequested()?.let { return@withContext it }

            if (shouldPauseChunk(
                    executionMode = normalizedExecutionMode,
                    chunkStartedAt = chunkStartedAt,
                    processedInChunk = processedInChunk,
                )
            ) {
                currentCursor = index.toString()
                persistSnapshot(
                    phase = RunPhase.WAITING_RETRY,
                    continuationCursorValue = currentCursor,
                )
                log(
                    SEVERITY_INFO,
                    "Chunk paused for system window",
                    "cursor=$currentCursor resumeCount=$resumeCount processedInChunk=$processedInChunk",
                )
                metric("chunk_paused", "cursor=$currentCursor")
                return@withContext RunExecutionResult(
                    runId = runIdValue,
                    status = RunStatus.RUNNING,
                    uploadedCount = uploadedCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    summaryError = summaryError,
                    phase = RunPhase.WAITING_RETRY,
                    continuationCursor = currentCursor,
                    resumeCount = resumeCount,
                    lastProgressAtEpochMs = lastProgressAt,
                    pausedForContinuation = true,
                )
            }

            val item = orderedItems[index]
            val record = backupRecordRepository.findByPlanAndMediaItem(planId, item.mediaId)
            if (record != null) {
                skippedCount += 1
                log(SEVERITY_INFO, "Skipped item", "mediaId=${item.mediaId} reason=already_backed_up")
                index += 1
                processedInChunk += 1
                currentCursor = if (index < orderedItems.size) index.toString() else null
                lastProgressAt = nowEpochMs()
                persistSnapshot(
                    phase = RunPhase.RUNNING,
                    continuationCursorValue = currentCursor,
                )
                emitProgress()
                continue
            }

            val pathResult = if (sourceType == SOURCE_TYPE_ALBUM) {
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
            val remotePath = pathResult.path
            if (pathResult.usedDefaultTokens.isNotEmpty()) {
                log(
                    SEVERITY_INFO,
                    "Template fallback used",
                    "tokens=${pathResult.usedDefaultTokens.sorted().joinToString(",")}",
                )
            }
            val maskedDestination = maskRemotePath(remotePath)
            log(
                SEVERITY_INFO,
                "Processing item",
                "mediaId=${item.mediaId} displayName=${item.displayName.orEmpty()} dest=$maskedDestination",
            )

            val stream = mediaStoreDataSource.openMediaStream(item.mediaId)
            if (stream == null) {
                failedCount += 1
                val message = "Unable to read source item ${item.mediaId}."
                summaryError = summaryError ?: message
                log(SEVERITY_ERROR, message)
                index += 1
                processedInChunk += 1
                currentCursor = if (index < orderedItems.size) index.toString() else null
                lastProgressAt = nowEpochMs()
                persistSnapshot(
                    phase = RunPhase.RUNNING,
                    continuationCursorValue = currentCursor,
                )
                emitProgress()
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
                    log(SEVERITY_INFO, "Uploaded item", "mediaId=${item.mediaId} dest=${maskRemotePath(remotePath)}")
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
            index += 1
            processedInChunk += 1
            currentCursor = if (index < orderedItems.size) index.toString() else null
            lastProgressAt = nowEpochMs()
            persistSnapshot(
                phase = RunPhase.RUNNING,
                continuationCursorValue = currentCursor,
            )
            emitProgress()
            finalizeCanceledIfRequested()?.let { return@withContext it }
        }

        finalizeCanceledIfRequested()?.let { return@withContext it }
        persistSnapshot(
            phase = RunPhase.FINISHING,
            continuationCursorValue = null,
        )

        val finalStatus = when {
            failedCount > 0 && uploadedCount == 0 && skippedCount == 0 -> RunStatus.FAILED
            failedCount > 0 -> RunStatus.PARTIAL
            else -> RunStatus.SUCCESS
        }

        metric("run_completed", "status=$finalStatus")
        finalizeRun(
            runId = runIdValue,
            planId = planId,
            startedAt = startedAt,
            scanned = scannedCount,
            uploaded = uploadedCount,
            skipped = skippedCount,
            failed = failedCount,
            summaryError = summaryError,
            status = finalStatus,
            triggerSource = normalizedTriggerSource,
            executionMode = normalizedExecutionMode,
            resumeCount = resumeCount,
            lastProgressAt = lastProgressAt,
            log = ::log,
                progressCallback = progressListener,
        )
    }

    private suspend fun createNewRun(
        planId: Long,
        triggerSource: String,
        executionMode: String,
        continuationCursor: String?,
    ): RunEntity {
        val startedAt = nowEpochMs()
        val runId = runRepository.createRun(
            RunEntity(
                planId = planId,
                status = RunStatus.RUNNING,
                startedAtEpochMs = startedAt,
                heartbeatAtEpochMs = startedAt,
                triggerSource = triggerSource,
                executionMode = executionMode,
                phase = RunPhase.RUNNING,
                continuationCursor = continuationCursor,
                resumeCount = 0,
                lastProgressAtEpochMs = startedAt,
            ),
        )
        return RunEntity(
            runId = runId,
            planId = planId,
            status = RunStatus.RUNNING,
            startedAtEpochMs = startedAt,
            heartbeatAtEpochMs = startedAt,
            triggerSource = triggerSource,
            executionMode = executionMode,
            phase = RunPhase.RUNNING,
            continuationCursor = continuationCursor,
            resumeCount = 0,
            lastProgressAtEpochMs = startedAt,
        )
    }

    private fun shouldPauseChunk(
        executionMode: String,
        chunkStartedAt: Long,
        processedInChunk: Int,
    ): Boolean {
        if (executionMode != RunExecutionMode.BACKGROUND) return false
        if (processedInChunk <= 0) return false
        if (processedInChunk >= MAX_ITEMS_PER_CHUNK_BACKGROUND) return true
        return nowEpochMs() - chunkStartedAt >= MAX_WALL_MS_BACKGROUND
    }

    private fun maskRemotePath(path: String): String {
        val normalized = path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return ""
        val lastSegments = normalized.takeLast(2)
        return if (lastSegments.size < normalized.size) {
            ".../${lastSegments.joinToString("/")}"
        } else {
            lastSegments.joinToString("/")
        }
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
        triggerSource: String,
        executionMode: String,
        resumeCount: Int,
        lastProgressAt: Long,
        log: suspend (String, String, String?) -> Unit,
        progressCallback: (suspend (RunProgressSnapshot) -> Unit)? = null,
    ): RunExecutionResult {
        val finishedAt = nowEpochMs()
        runRepository.updateRun(
            RunEntity(
                runId = runId,
                planId = planId,
                status = status,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = finishedAt,
                heartbeatAtEpochMs = finishedAt,
                scannedCount = scanned,
                uploadedCount = uploaded,
                skippedCount = skipped,
                failedCount = failed,
                summaryError = summaryError,
                triggerSource = triggerSource,
                executionMode = executionMode,
                phase = RunPhase.TERMINAL,
                continuationCursor = null,
                resumeCount = resumeCount,
                lastProgressAtEpochMs = maxOf(lastProgressAt, finishedAt),
            ),
        )
        log(SEVERITY_INFO, "Run finished", "status=$status uploaded=$uploaded skipped=$skipped failed=$failed")
        progressCallback?.invoke(
            RunProgressSnapshot(
                runId = runId,
                planId = planId,
                status = status,
                phase = RunPhase.TERMINAL,
                scannedCount = scanned,
                uploadedCount = uploaded,
                skippedCount = skipped,
                failedCount = failed,
                continuationCursor = null,
                resumeCount = resumeCount,
            ),
        )
        return RunExecutionResult(
            runId = runId,
            status = status,
            uploadedCount = uploaded,
            skippedCount = skipped,
            failedCount = failed,
            summaryError = summaryError,
            phase = RunPhase.TERMINAL,
            continuationCursor = null,
            resumeCount = resumeCount,
            lastProgressAtEpochMs = maxOf(lastProgressAt, finishedAt),
            pausedForContinuation = false,
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

    private fun normalizeTriggerSource(source: String): String {
        val normalized = source.trim().uppercase(Locale.US)
        return when (normalized) {
            RunTriggerSource.SCHEDULED -> RunTriggerSource.SCHEDULED
            else -> RunTriggerSource.MANUAL
        }
    }

    private fun normalizeExecutionMode(
        executionMode: String?,
        triggerSource: String,
    ): String {
        val normalized = executionMode?.trim()?.uppercase(Locale.US)
        return when (normalized) {
            RunExecutionMode.BACKGROUND -> RunExecutionMode.BACKGROUND
            RunExecutionMode.FOREGROUND -> RunExecutionMode.FOREGROUND
            else -> if (triggerSource == RunTriggerSource.SCHEDULED) {
                RunExecutionMode.BACKGROUND
            } else {
                RunExecutionMode.FOREGROUND
            }
        }
    }

    private fun parseContinuationCursor(
        cursor: String?,
        maxExclusive: Int,
    ): Int {
        val parsed = cursor?.trim()?.toIntOrNull() ?: 0
        return parsed.coerceIn(0, maxExclusive)
    }

    private suspend fun readPersistedStatus(runId: Long): String? =
        runRepository.getRun(runId)?.status?.trim()?.uppercase(Locale.US)

    private suspend fun findActiveRunForPlan(planId: Long): RunEntity? {
        return runRepository
            .latestRunsByStatuses(MAX_ACTIVE_RUN_LOOKBACK, ACTIVE_RUN_STATUSES)
            .asSequence()
            .firstOrNull { it.planId == planId && it.finishedAtEpochMs == null }
    }

    private suspend fun finalizePriorActiveRunsForPlan(planId: Long) {
        val now = nowEpochMs()
        val activeRunsForPlan = runRepository
            .latestRunsByStatuses(MAX_ACTIVE_RUN_LOOKBACK, ACTIVE_RUN_STATUSES)
            .asSequence()
            .filter { it.planId == planId && it.finishedAtEpochMs == null }
            .toList()
        if (activeRunsForPlan.isEmpty()) return

        activeRunsForPlan.forEach { existingRun ->
            val normalizedStatus = existingRun.status.trim().uppercase(Locale.US)
            val finalStatus = if (normalizedStatus == RunStatus.CANCEL_REQUESTED) {
                RunStatus.CANCELED
            } else {
                RunStatus.INTERRUPTED
            }
            val summary = existingRun.summaryError ?: if (finalStatus == RunStatus.CANCELED) {
                DEFAULT_CANCELED_SUMMARY
            } else {
                DEFAULT_INTERRUPTED_SUMMARY
            }
            runRepository.updateRun(
                existingRun.copy(
                    status = finalStatus,
                    finishedAtEpochMs = now,
                    heartbeatAtEpochMs = now,
                    summaryError = summary,
                    phase = RunPhase.TERMINAL,
                    continuationCursor = null,
                    lastProgressAtEpochMs = maxOf(existingRun.lastProgressAtEpochMs, now),
                ),
            )
            runLogRepository.createLog(
                RunLogEntity(
                    runId = existingRun.runId,
                    timestampEpochMs = now,
                    severity = if (finalStatus == RunStatus.CANCELED) SEVERITY_INFO else SEVERITY_ERROR,
                    message = if (finalStatus == RunStatus.CANCELED) {
                        "Run finalized as canceled"
                    } else {
                        "Run marked as interrupted"
                    },
                    detail = "A newer run attempt for this job started before this run completed.",
                ),
            )
        }
    }

    private data class SourceScanResult(
        val items: List<MediaImageItem>,
        val warnings: List<String> = emptyList(),
    )

    companion object {
        private const val SOURCE_TYPE_ALBUM = "ALBUM"
        private const val SOURCE_TYPE_FOLDER = "FOLDER"
        private const val SOURCE_TYPE_FULL_DEVICE = "FULL_DEVICE"
        private const val SEVERITY_INFO = "INFO"
        private const val SEVERITY_ERROR = "ERROR"
        private const val PROGRESS_LOG_INTERVAL = 10
        private const val LOG_TAG = "NasBoxRun"
        private const val DEFAULT_CANCELED_SUMMARY = "Run canceled by user."
        private const val DEFAULT_INTERRUPTED_SUMMARY = "Run interrupted before completion."
        private const val MAX_ACTIVE_RUN_LOOKBACK = 200
        private const val MAX_WALL_MS_BACKGROUND = 7L * 60L * 1000L
        private const val MAX_ITEMS_PER_CHUNK_BACKGROUND = 80
        private const val SCAN_PROGRESS_PERSIST_INTERVAL_MS = 1_000L
        private const val SCAN_CANCEL_CHECK_INTERVAL_MS = 750L
        private val ACTIVE_RUN_STATUSES = setOf(
            RunStatus.RUNNING,
            RunStatus.CANCEL_REQUESTED,
        )
    }
}

data class RunExecutionResult(
    val runId: Long,
    val status: String,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String? = null,
    val phase: String = RunPhase.TERMINAL,
    val continuationCursor: String? = null,
    val resumeCount: Int = 0,
    val lastProgressAtEpochMs: Long = 0L,
    val pausedForContinuation: Boolean = false,
)

data class RunProgressSnapshot(
    val runId: Long,
    val planId: Long,
    val status: String,
    val phase: String,
    val scannedCount: Int,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val continuationCursor: String? = null,
    val resumeCount: Int = 0,
)

private fun RunEntity.toExecutionResult(pausedForContinuation: Boolean): RunExecutionResult {
    return RunExecutionResult(
        runId = runId,
        status = status,
        uploadedCount = uploadedCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        summaryError = summaryError,
        phase = phase,
        continuationCursor = continuationCursor,
        resumeCount = resumeCount,
        lastProgressAtEpochMs = lastProgressAtEpochMs,
        pausedForContinuation = pausedForContinuation,
    )
}

private fun RunEntity.toProgressSnapshot(): RunProgressSnapshot {
    return RunProgressSnapshot(
        runId = runId,
        planId = planId,
        status = status,
        phase = phase,
        scannedCount = scannedCount,
        uploadedCount = uploadedCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        continuationCursor = continuationCursor,
        resumeCount = resumeCount,
    )
}

internal object PathRenderer {
    private const val DEFAULT_TEMPLATE = "{year}/{month}/{day}"
    private const val DEFAULT_FILENAME_PATTERN = "{timestamp}_{mediaId}.{ext}"
    private const val UNKNOWN_TOKEN = "unknown"
    private val ILLEGAL_PATH_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    fun render(
        basePath: String,
        directoryTemplate: String,
        filenamePattern: String,
        mediaItem: MediaImageItem,
        fallbackAlbumToken: String,
        useAlbumTemplating: Boolean,
    ): PathRenderResult {
        val normalizedBase = joinSegments(basePath)
        val usedDefaults = mutableSetOf<String>()

        if (!useAlbumTemplating) {
            val albumSegment = sanitizeWithFallback(fallbackAlbumToken, "album", usedDefaults)
            val filename = sanitizeSegment(
                mediaItem.displayName.orEmpty().ifBlank { "${mediaItem.mediaId}.${extension(mediaItem)}" },
            )
            val path = joinSegments(normalizedBase, albumSegment, filename)
            return PathRenderResult(path = path, usedDefaultTokens = usedDefaults)
        }

        val safeExt = extension(mediaItem)
        val hasDate = mediaItem.dateTakenEpochMs != null
        val date = Date(mediaItem.dateTakenEpochMs ?: 0L)
        val values = mutableMapOf<String, String>()
        values["year"] = fetchDateToken(hasDate) { format(date, "yyyy") }.also { if (it == UNKNOWN_TOKEN) usedDefaults += "year" }
        values["month"] = fetchDateToken(hasDate) { format(date, "MM") }.also { if (it == UNKNOWN_TOKEN) usedDefaults += "month" }
        values["day"] = fetchDateToken(hasDate) { format(date, "dd") }.also { if (it == UNKNOWN_TOKEN) usedDefaults += "day" }
        values["time"] = fetchDateToken(hasDate) { format(date, "HHmmss") }.also { if (it == UNKNOWN_TOKEN) usedDefaults += "time" }
        values["timestamp"] =
            fetchDateToken(hasDate) { format(date, "yyyyMMdd_HHmmss") }.also { if (it == UNKNOWN_TOKEN) usedDefaults += "timestamp" }
        val albumValue = sanitizeWithFallback(fallbackAlbumToken, "album", usedDefaults)
        values["album"] = albumValue
        values["mediaId"] = sanitizeSegment(mediaItem.mediaId)
        values["ext"] = sanitizeSegment(safeExt)
        val deviceValue = sanitizeWithFallback(Build.MODEL, "device", usedDefaults, fallback = "android")
        values["device"] = deviceValue

        val renderedDirectory = sanitizePath(renderTokens(directoryTemplate.ifBlank { DEFAULT_TEMPLATE }, values, usedDefaults))
        val renderedName = sanitizeSegment(renderTokens(filenamePattern.ifBlank { DEFAULT_FILENAME_PATTERN }, values, usedDefaults))
        val path = joinSegments(normalizedBase, renderedDirectory, renderedName)
        return PathRenderResult(path = path, usedDefaultTokens = usedDefaults)
    }

    fun renderPreservingSourcePath(
        basePath: String,
        mediaItem: MediaImageItem,
    ): PathRenderResult {
        val extension = extension(mediaItem)
        val fallbackNameStem = mediaItem.mediaId
            .substringAfterLast('/')
            .substringAfterLast(':')
            .substringBefore('?')
            .ifBlank { "item_${sanitizeSegment(mediaItem.mediaId).takeLast(12)}" }
        val fallbackName = "$fallbackNameStem.$extension"
        val filename = sanitizeSegment(mediaItem.displayName.orEmpty().ifBlank { fallbackName })
        val relativeDirectory = sanitizePath(mediaItem.relativePath.orEmpty())
        val path = joinSegments(basePath, relativeDirectory, filename)
        return PathRenderResult(path = path, usedDefaultTokens = emptySet())
    }

    private fun fetchDateToken(hasDate: Boolean, valueProvider: () -> String): String =
        if (hasDate) valueProvider() else UNKNOWN_TOKEN

    private fun sanitizeWithFallback(
        value: String?,
        key: String,
        usedDefaults: MutableSet<String>,
        fallback: String = UNKNOWN_TOKEN,
    ): String {
        val input = value?.trim().orEmpty()
        return if (input.isBlank()) {
            usedDefaults += key
            sanitizeSegment(fallback)
        } else {
            sanitizeSegment(input)
        }
    }

    private fun renderTokens(template: String, values: Map<String, String>, usedDefaults: MutableSet<String>): String {
        var output = template
        values.forEach { (key, value) ->
            output = output.replace("{$key}", value)
        }
        Regex("\\{([^}]+)\\}").findAll(output).forEach { match ->
            val token = match.groupValues[1]
            usedDefaults += token
        }
        output = output.replace(Regex("\\{[^}]+\\}"), UNKNOWN_TOKEN)
        return output
    }

    private fun joinSegments(vararg segments: String): String {
        return segments
            .asSequence()
            .flatMap { it.split('/', '\\').asSequence().map(String::trim) }
            .filter { it.isNotBlank() }
            .map { sanitizeSegment(it) }
            .joinToString("/")
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
        val trimmed = value.trim().ifBlank { UNKNOWN_TOKEN }
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

internal data class PathRenderResult(
    val path: String,
    val usedDefaultTokens: Set<String>,
)

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
    is skezza.nasbox.data.smb.SmbConnectionFailure.Unknown -> "Upload failed due to an unknown connection issue."
}
