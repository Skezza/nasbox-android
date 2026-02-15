package skezza.nasbox.work

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import skezza.nasbox.AppContainer
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.domain.sync.RunExecutionMode
import skezza.nasbox.domain.sync.RunPhase
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
        return try {
            setForeground(RunWorkerNotifications.createForegroundInfo(applicationContext, planId))
            val result = appContainer.runPlanBackupUseCase(
                planId = planId,
                triggerSource = triggerSource,
                executionMode = RunExecutionMode.FOREGROUND,
            )
            if (result.status in ISSUE_STATUSES) {
                RunWorkerNotifications.postIssueNotification(
                    context = applicationContext,
                    title = "Manual backup needs attention",
                    message = listOfNotNull(
                        "Plan #$planId finished ${result.status.lowercase()}",
                        result.summaryError,
                    ).joinToString(" - "),
                    notificationIdSeed = result.runId,
                    runId = result.runId,
                )
            }
            Result.success()
        } catch (error: Exception) {
            if (isForegroundStartNotAllowed(error)) {
                val summary = error.message?.takeIf { it.isNotBlank() } ?: DEFAULT_FGS_BLOCKED_SUMMARY
                Log.i(LOG_TAG, "metric=fgs_blocked planId=$planId trigger=$triggerSource")
                val runId = recordForegroundStartBlocked(
                    planId = planId,
                    summary = summary,
                )
                RunWorkerNotifications.postIssueNotification(
                    context = applicationContext,
                    title = "Manual backup interrupted",
                    message = "Android blocked background foreground-start. Open app to resume now.",
                    notificationIdSeed = runId ?: planId,
                    runId = runId,
                )
                return Result.success()
            }

            runCatching {
                appContainer.reconcileStaleActiveRunsUseCase(forceFinalizeActive = true)
            }
            Result.retry()
        }
    }

    private suspend fun recordForegroundStartBlocked(
        planId: Long,
        summary: String,
    ): Long? {
        return runCatching {
            val now = System.currentTimeMillis()
            val runId = appContainer.runRepository.createRun(
                RunEntity(
                    planId = planId,
                    status = RunStatus.INTERRUPTED,
                    startedAtEpochMs = now,
                    finishedAtEpochMs = now,
                    heartbeatAtEpochMs = now,
                    summaryError = summary,
                    triggerSource = RunTriggerSource.MANUAL,
                    executionMode = RunExecutionMode.FOREGROUND,
                    phase = RunPhase.TERMINAL,
                    lastProgressAtEpochMs = now,
                ),
            )
            appContainer.runLogRepository.createLog(
                RunLogEntity(
                    runId = runId,
                    timestampEpochMs = now,
                    severity = "ERROR",
                    message = "Foreground start blocked",
                    detail = summary,
                ),
            )
            runId
        }.getOrNull()
    }

    private fun isForegroundStartNotAllowed(error: Exception): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error is ForegroundServiceStartNotAllowedException
    }

    companion object {
        const val KEY_PLAN_ID = "run_plan_id"
        const val KEY_TRIGGER_SOURCE = "run_trigger_source"

        private const val DEFAULT_FGS_BLOCKED_SUMMARY = "Run interrupted because foreground execution was blocked."
        private const val LOG_TAG = "NasBoxRun"
        private val ISSUE_STATUSES = setOf(RunStatus.FAILED, RunStatus.PARTIAL, RunStatus.INTERRUPTED)
    }
}
