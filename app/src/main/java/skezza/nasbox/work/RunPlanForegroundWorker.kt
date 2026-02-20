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
import skezza.nasbox.domain.sync.RunExecutionMode
import skezza.nasbox.domain.sync.RunPhase
import skezza.nasbox.domain.sync.RunProgressSnapshot
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.domain.sync.RunTriggerSource

class RunPlanForegroundWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val appContainer = AppContainer(appContext)

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(KEY_PLAN_ID, -1L)
        if (planId <= 0L) return Result.failure()

        val triggerSource = inputData.getString(KEY_TRIGGER_SOURCE) ?: RunTriggerSource.MANUAL
        val plan = appContainer.planRepository.getPlan(planId)
        val progressNotificationsEnabled = plan?.progressNotificationEnabled ?: true
        val planName = plan?.name?.trim()?.takeIf { it.isNotBlank() }

        return try {
            if (progressNotificationsEnabled) {
                setForeground(
                    RunWorkerNotifications.createForegroundInfo(
                        context = applicationContext,
                        planId = planId,
                        planName = planName,
                    ),
                )
            }
            val executionMode = if (progressNotificationsEnabled) {
                RunExecutionMode.FOREGROUND
            } else {
                RunExecutionMode.BACKGROUND
            }
            runManualBackup(
                planId = planId,
                triggerSource = triggerSource,
                executionMode = executionMode,
                progressNotificationsEnabled = progressNotificationsEnabled,
                planName = planName,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (isForegroundStartNotAllowed(error)) {
                Log.i(LOG_TAG, "metric=fgs_blocked planId=$planId trigger=$triggerSource")
                return runManualWithoutForeground(planId, triggerSource, planName)
            }
            Log.e(LOG_TAG, "Manual worker failure planId=$planId trigger=$triggerSource", error)
            Result.retry()
        }
    }

    private suspend fun runManualBackup(
        planId: Long,
        triggerSource: String,
        executionMode: String,
        progressNotificationsEnabled: Boolean,
        planName: String?,
    ): Result {
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

        return coroutineScope {
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
                    executionMode = executionMode,
                    progressListener = ::onProgress,
                )
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
                        title = "Manual backup needs attention",
                        message = listOfNotNull(
                            "Job #$planId finished ${result.status.lowercase()}",
                            result.summaryError,
                        ).joinToString(" - "),
                        notificationIdSeed = result.runId,
                        runId = result.runId,
                    )
                }
                Result.success()
            } finally {
                keepAliveJob?.cancel()
            }
        }
    }

    private suspend fun runManualWithoutForeground(
        planId: Long,
        triggerSource: String,
        planName: String?,
    ): Result {
        return runCatching {
            runManualBackup(
                planId = planId,
                triggerSource = triggerSource,
                executionMode = RunExecutionMode.BACKGROUND,
                progressNotificationsEnabled = false,
                planName = planName,
            )
        }.getOrElse {
            if (it is CancellationException) {
                throw it
            }
            Log.e(LOG_TAG, "Manual worker fallback failure planId=$planId trigger=$triggerSource", it)
            Result.retry()
        }
    }

    private fun isForegroundStartNotAllowed(error: Throwable): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error is ForegroundServiceStartNotAllowedException
    }

    companion object {
        const val KEY_PLAN_ID = "run_plan_id"
        const val KEY_TRIGGER_SOURCE = "run_trigger_source"

        private const val LOG_TAG = "NasBoxRun"
        private const val PROGRESS_NOTIFICATION_THROTTLE_MS = 1_000L
        private const val NOTIFICATION_KEEPALIVE_MS = 2_500L
        private val ISSUE_STATUSES = setOf(RunStatus.FAILED, RunStatus.PARTIAL, RunStatus.INTERRUPTED)
    }
}
