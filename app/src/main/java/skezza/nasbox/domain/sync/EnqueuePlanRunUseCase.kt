package skezza.nasbox.domain.sync

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import skezza.nasbox.work.RunPlanBackgroundWorker
import skezza.nasbox.work.RunPlanForegroundWorker

class EnqueuePlanRunUseCase(
    private val workManager: WorkManager,
) {

    suspend operator fun invoke(
        planId: Long,
        triggerSource: String = RunTriggerSource.MANUAL,
    ) {
        val normalizedTrigger = normalizeTriggerSource(triggerSource)
        if (normalizedTrigger == RunTriggerSource.SCHEDULED) {
            enqueueScheduled(
                planId = planId,
                runId = null,
                continuationCursor = null,
            )
        } else {
            enqueueManual(planId)
        }
    }

    suspend fun enqueueScheduledContinuation(
        planId: Long,
        runId: Long,
        continuationCursor: String,
    ) {
        enqueueScheduled(
            planId = planId,
            runId = runId,
            continuationCursor = continuationCursor,
        )
    }

    private fun enqueueManual(planId: Long) {
        val request = OneTimeWorkRequestBuilder<RunPlanForegroundWorker>()
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putLong(RunPlanForegroundWorker.KEY_PLAN_ID, planId)
                    .putString(RunPlanForegroundWorker.KEY_TRIGGER_SOURCE, RunTriggerSource.MANUAL)
                    .build(),
            )
            .addTag(TAG_RUN_WORK)
            .addTag(TAG_MANUAL_WORK)
            .addTag(TAG_PLAN_PREFIX + planId)
            .build()
        workManager.enqueueUniqueWork(
            manualQueueName(planId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private fun enqueueScheduled(
        planId: Long,
        runId: Long?,
        continuationCursor: String?,
    ) {
        val inputData = Data.Builder()
            .putLong(RunPlanBackgroundWorker.KEY_PLAN_ID, planId)
            .putString(RunPlanBackgroundWorker.KEY_TRIGGER_SOURCE, RunTriggerSource.SCHEDULED)
            .apply {
                if (runId != null && runId > 0L) {
                    putLong(RunPlanBackgroundWorker.KEY_RUN_ID, runId)
                }
                if (!continuationCursor.isNullOrBlank()) {
                    putString(RunPlanBackgroundWorker.KEY_CONTINUATION_CURSOR, continuationCursor)
                }
            }
            .build()

        val request = OneTimeWorkRequestBuilder<RunPlanBackgroundWorker>()
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(inputData)
            .addTag(TAG_RUN_WORK)
            .addTag(TAG_SCHEDULED_WORK)
            .addTag(TAG_PLAN_PREFIX + planId)
            .build()
        workManager.enqueueUniqueWork(
            scheduledQueueName(planId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private fun defaultConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun normalizeTriggerSource(triggerSource: String): String {
        return if (triggerSource.equals(RunTriggerSource.SCHEDULED, ignoreCase = true)) {
            RunTriggerSource.SCHEDULED
        } else {
            RunTriggerSource.MANUAL
        }
    }

    private fun manualQueueName(planId: Long): String = "backup-run-manual-plan-$planId"

    private fun scheduledQueueName(planId: Long): String = "backup-run-scheduled-plan-$planId"

    companion object {
        const val TAG_RUN_WORK = "backup-run-work"
        const val TAG_MANUAL_WORK = "backup-run-manual"
        const val TAG_SCHEDULED_WORK = "backup-run-scheduled"
        const val TAG_PLAN_PREFIX = "backup-run-plan-"
        private const val RETRY_BACKOFF_SECONDS = 30L
    }
}
