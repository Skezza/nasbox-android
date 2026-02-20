package skezza.nasbox.work

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import skezza.nasbox.AppContainer
import skezza.nasbox.domain.sync.RunPhase
import skezza.nasbox.domain.sync.RunExecutionMode
import skezza.nasbox.domain.sync.RunProgressSnapshot
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.domain.sync.RunTriggerSource

class RunPlanBackgroundWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val appContainer = AppContainer(appContext)

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(KEY_PLAN_ID, -1L)
        if (planId <= 0L) return Result.failure()

        val triggerSource = inputData.getString(KEY_TRIGGER_SOURCE) ?: RunTriggerSource.SCHEDULED
        val continuationRunId = inputData.getLong(KEY_RUN_ID, -1L).takeIf { it > 0L }
        val continuationCursor = inputData.getString(KEY_CONTINUATION_CURSOR)
        val plan = appContainer.planRepository.getPlan(planId)
        val progressNotificationsEnabled = plan?.progressNotificationEnabled ?: true
        val planName = plan?.name?.trim()?.takeIf { it.isNotBlank() }

        return runCatching {
            var notificationsEnabled = progressNotificationsEnabled
            var lastNotificationAt = 0L
            var lastNotificationStatus: String? = null
            var lastNotificationPhase: String? = null
            var lastNotificationProcessedCount = -1
            var latestSnapshot = RunProgressSnapshot(
                runId = 0L,
                planId = planId,
                status = RunStatus.RUNNING,
                phase = RunPhase.RUNNING,
                scannedCount = 0,
                uploadedCount = 0,
                skippedCount = 0,
                failedCount = 0,
            )

            if (notificationsEnabled) {
                runCatching {
                    setForeground(
                        RunWorkerNotifications.createForegroundInfo(
                            context = applicationContext,
                            planId = planId,
                            planName = planName,
                        ),
                    )
                }.onFailure { error ->
                    if (isForegroundStartNotAllowed(error)) {
                        Log.i(LOG_TAG, "metric=fgs_blocked planId=$planId trigger=$triggerSource")
                        notificationsEnabled = false
                    }
                }
            }

            coroutineScope {
                val keepAliveJob = if (notificationsEnabled) {
                    launch {
                        while (isActive) {
                            delay(NOTIFICATION_KEEPALIVE_MS)
                            if (!notificationsEnabled) continue
                            runCatching {
                                setForeground(
                                    RunWorkerNotifications.createForegroundInfo(
                                        context = applicationContext,
                                        snapshot = latestSnapshot,
                                        planName = planName,
                                    ),
                                )
                            }.onFailure { error ->
                                if (isForegroundStartNotAllowed(error)) {
                                    Log.i(LOG_TAG, "metric=fgs_blocked planId=$planId trigger=$triggerSource")
                                    notificationsEnabled = false
                                }
                            }
                        }
                    }
                } else {
                    null
                }

                try {
                    suspend fun onProgress(snapshot: RunProgressSnapshot) {
                        latestSnapshot = snapshot
                        if (!notificationsEnabled) return
                        val now = System.currentTimeMillis()
                        val processedCount = snapshot.uploadedCount + snapshot.skippedCount + snapshot.failedCount
                        val phaseOrStatusChanged = snapshot.status != lastNotificationStatus || snapshot.phase != lastNotificationPhase
                        val processedChanged = processedCount != lastNotificationProcessedCount
                        val shouldUpdate = lastNotificationAt == 0L ||
                            phaseOrStatusChanged ||
                            (processedChanged && (now - lastNotificationAt) >= PROGRESS_NOTIFICATION_THROTTLE_MS)
                        if (!shouldUpdate) {
                            return
                        }
                        runCatching {
                            setForeground(
                                RunWorkerNotifications.createForegroundInfo(
                                    context = applicationContext,
                                    snapshot = snapshot,
                                    planName = planName,
                                ),
                            )
                        }.onSuccess {
                            lastNotificationAt = now
                            lastNotificationStatus = snapshot.status
                            lastNotificationPhase = snapshot.phase
                            lastNotificationProcessedCount = processedCount
                        }.onFailure { error ->
                            if (isForegroundStartNotAllowed(error)) {
                                Log.i(LOG_TAG, "metric=fgs_blocked planId=$planId trigger=$triggerSource")
                                notificationsEnabled = false
                            }
                        }
                    }

                    val result = appContainer.runPlanBackupUseCase(
                        planId = planId,
                        triggerSource = triggerSource,
                        runId = continuationRunId,
                        executionMode = RunExecutionMode.BACKGROUND,
                        continuationCursor = continuationCursor,
                        progressListener = ::onProgress,
                    )
                    if (result.pausedForContinuation && result.continuationCursor != null) {
                        appContainer.enqueuePlanRunUseCase.enqueueScheduledContinuation(
                            planId = planId,
                            runId = result.runId,
                            continuationCursor = result.continuationCursor,
                        )
                        maybeNotifyBackgroundStall(planId = planId, runId = result.runId, result = result)
                        return@coroutineScope
                    }

                    keepAliveJob?.cancel()
                    if (result.status == RunStatus.SUCCESS) {
                        RunWorkerNotifications.postCompletionNotification(
                            context = applicationContext,
                            planId = planId,
                            runId = result.runId,
                            planName = planName,
                            uploadedCount = result.uploadedCount,
                            skippedCount = result.skippedCount,
                            failedCount = result.failedCount,
                        )
                    }
                    if (result.status in ISSUE_STATUSES) {
                        RunWorkerNotifications.postIssueNotification(
                            context = applicationContext,
                            title = "Scheduled backup needs attention",
                            message = listOfNotNull(
                                "Job #$planId finished ${result.status.lowercase()}",
                                result.summaryError,
                            ).joinToString(" - "),
                            notificationIdSeed = result.runId,
                            runId = result.runId,
                        )
                    }
                } finally {
                    keepAliveJob?.cancel()
                }
            }
            Result.success()
        }.getOrElse {
            if (it is CancellationException) {
                throw it
            }
            Result.retry()
        }
    }

    private fun maybeNotifyBackgroundStall(
        planId: Long,
        runId: Long,
        result: skezza.nasbox.domain.sync.RunExecutionResult,
    ) {
        val hasStalled = result.resumeCount >= STALL_RESUME_THRESHOLD &&
            (System.currentTimeMillis() - result.lastProgressAtEpochMs) >= STALL_WINDOW_MS
        if (!hasStalled) return

        RunWorkerNotifications.postIssueNotification(
            context = applicationContext,
            title = "Scheduled backup waiting",
            message = "Job #$planId is still waiting for system background windows. Open app to resume now.",
            notificationIdSeed = runId,
            runId = runId,
        )
    }

    private fun isForegroundStartNotAllowed(error: Throwable): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error is ForegroundServiceStartNotAllowedException
    }

    companion object {
        const val KEY_PLAN_ID = "run_plan_id"
        const val KEY_TRIGGER_SOURCE = "run_trigger_source"
        const val KEY_RUN_ID = "run_id"
        const val KEY_CONTINUATION_CURSOR = "continuation_cursor"

        private const val LOG_TAG = "NasBoxRun"
        private const val PROGRESS_NOTIFICATION_THROTTLE_MS = 1_000L
        private const val NOTIFICATION_KEEPALIVE_MS = 2_500L
        private const val STALL_RESUME_THRESHOLD = 4
        private const val STALL_WINDOW_MS = 30L * 60L * 1000L
        private val ISSUE_STATUSES = setOf(RunStatus.FAILED, RunStatus.PARTIAL, RunStatus.INTERRUPTED)
    }
}
