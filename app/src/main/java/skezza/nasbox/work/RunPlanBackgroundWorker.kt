package skezza.nasbox.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import skezza.nasbox.AppContainer
import skezza.nasbox.domain.sync.RunExecutionMode
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

        return runCatching {
            val result = appContainer.runPlanBackupUseCase(
                planId = planId,
                triggerSource = triggerSource,
                runId = continuationRunId,
                executionMode = RunExecutionMode.BACKGROUND,
                continuationCursor = continuationCursor,
            )
            if (result.pausedForContinuation && result.continuationCursor != null) {
                appContainer.enqueuePlanRunUseCase.enqueueScheduledContinuation(
                    planId = planId,
                    runId = result.runId,
                    continuationCursor = result.continuationCursor,
                )
                maybeNotifyBackgroundStall(planId = planId, runId = result.runId, result = result)
                return@runCatching Result.success()
            }

            if (result.status in ISSUE_STATUSES) {
                RunWorkerNotifications.postIssueNotification(
                    context = applicationContext,
                    title = "Scheduled backup needs attention",
                    message = listOfNotNull(
                        "Plan #$planId finished ${result.status.lowercase()}",
                        result.summaryError,
                    ).joinToString(" - "),
                    notificationIdSeed = result.runId,
                    runId = result.runId,
                )
            }
            Result.success()
        }.getOrElse {
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
            message = "Plan #$planId is still waiting for system background windows. Open app to resume now.",
            notificationIdSeed = runId,
            runId = runId,
        )
    }

    companion object {
        const val KEY_PLAN_ID = "run_plan_id"
        const val KEY_TRIGGER_SOURCE = "run_trigger_source"
        const val KEY_RUN_ID = "run_id"
        const val KEY_CONTINUATION_CURSOR = "continuation_cursor"

        private const val STALL_RESUME_THRESHOLD = 4
        private const val STALL_WINDOW_MS = 30L * 60L * 1000L
        private val ISSUE_STATUSES = setOf(RunStatus.FAILED, RunStatus.PARTIAL, RunStatus.INTERRUPTED)
    }
}
