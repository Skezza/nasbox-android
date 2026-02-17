package skezza.nasbox.domain.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.work.RunPlanBackgroundWorker
import skezza.nasbox.work.RunPlanForegroundWorker
import skezza.nasbox.work.PlanRerunTokenWorker

class EnqueuePlanRunUseCase(
    private val workManager: WorkManager,
    private val planRepository: PlanRepository,
) : RunContinuationScheduler {

    suspend operator fun invoke(
        planId: Long,
        triggerSource: String = RunTriggerSource.MANUAL,
    ): PlanRunEnqueueResult {
        val plan = planRepository.getPlan(planId) ?: return PlanRunEnqueueResult.IGNORED_DISABLED
        if (!plan.enabled) return PlanRunEnqueueResult.IGNORED_DISABLED
        val normalizedTrigger = normalizeTriggerSource(triggerSource)
        return if (normalizedTrigger == RunTriggerSource.SCHEDULED) {
            if (!plan.scheduleEnabled) {
                PlanRunEnqueueResult.IGNORED_DISABLED
            } else {
                enqueueScheduled(planId)
            }
        } else {
            enqueueManual(planId)
        }
    }

    suspend fun enqueueScheduledContinuation(
        planId: Long,
        runId: Long,
        continuationCursor: String,
    ): PlanRunEnqueueResult {
        val plan = planRepository.getPlan(planId) ?: return PlanRunEnqueueResult.IGNORED_DISABLED
        if (!plan.enabled || !plan.scheduleEnabled) return PlanRunEnqueueResult.IGNORED_DISABLED

        val request = buildScheduledRequest(
            planId = planId,
            runId = runId,
            continuationCursor = continuationCursor,
        )
        workManager.enqueueUniqueWork(
            continuationQueueName(planId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return PlanRunEnqueueResult.ENQUEUED
    }

    override suspend fun enqueueContinuation(
        planId: Long,
        runId: Long,
        continuationCursor: String,
    ) {
        enqueueScheduledContinuation(
            planId = planId,
            runId = runId,
            continuationCursor = continuationCursor,
        )
    }

    suspend fun drainRerunToken(
        planId: Long,
    ): PlanRunEnqueueResult {
        val plan = planRepository.getPlan(planId) ?: return PlanRunEnqueueResult.IGNORED_DISABLED
        if (!plan.enabled || !plan.scheduleEnabled) return PlanRunEnqueueResult.IGNORED_DISABLED
        if (hasExecutionOrContinuationPending(planId)) return PlanRunEnqueueResult.COALESCED

        val request = buildScheduledRequest(
            planId = planId,
            runId = null,
            continuationCursor = null,
        )
        workManager.enqueueUniqueWork(
            scheduledExecutionQueueName(planId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return PlanRunEnqueueResult.ENQUEUED
    }

    suspend fun cancelQueuedPlanRunWork(planId: Long) {
        workManager.cancelUniqueWork(manualExecutionQueueName(planId))
        workManager.cancelUniqueWork(scheduledExecutionQueueName(planId))
        workManager.cancelUniqueWork(continuationQueueName(planId))
        workManager.cancelUniqueWork(rerunTokenQueueName(planId))
        // Legacy queue names from earlier versions.
        workManager.cancelUniqueWork(legacyManualQueueName(planId))
        workManager.cancelUniqueWork(legacyScheduledQueueName(planId))
    }

    private suspend fun enqueueManual(planId: Long): PlanRunEnqueueResult {
        if (hasExecutionOrContinuationPending(planId)) {
            return PlanRunEnqueueResult.IGNORED_ALREADY_ACTIVE
        }
        val request = buildManualRequest(planId)
        workManager.enqueueUniqueWork(
            manualExecutionQueueName(planId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return PlanRunEnqueueResult.ENQUEUED
    }

    private suspend fun enqueueScheduled(planId: Long): PlanRunEnqueueResult {
        if (hasExecutionOrContinuationPending(planId)) {
            if (!hasActiveOrPending(rerunTokenQueueName(planId))) {
                workManager.enqueueUniqueWork(
                    rerunTokenQueueName(planId),
                    ExistingWorkPolicy.KEEP,
                    buildRerunTokenRequest(planId),
                )
            }
            return PlanRunEnqueueResult.COALESCED
        }
        val request = buildScheduledRequest(
            planId = planId,
            runId = null,
            continuationCursor = null,
        )
        workManager.enqueueUniqueWork(
            scheduledExecutionQueueName(planId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return PlanRunEnqueueResult.ENQUEUED
    }

    private fun buildManualRequest(planId: Long) = OneTimeWorkRequestBuilder<RunPlanForegroundWorker>()
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

    private fun buildScheduledRequest(
        planId: Long,
        runId: Long?,
        continuationCursor: String?,
    ) = run {
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

        OneTimeWorkRequestBuilder<RunPlanBackgroundWorker>()
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(inputData)
            .addTag(TAG_RUN_WORK)
            .addTag(TAG_SCHEDULED_WORK)
            .addTag(TAG_PLAN_PREFIX + planId)
            .build()
    }

    private fun buildRerunTokenRequest(planId: Long) = OneTimeWorkRequestBuilder<PlanRerunTokenWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
        .setInputData(
            Data.Builder()
                .putLong(PlanRerunTokenWorker.KEY_PLAN_ID, planId)
                .build(),
        )
        .addTag(TAG_RUN_WORK)
        .addTag(TAG_RERUN_WORK)
        .addTag(TAG_PLAN_PREFIX + planId)
        .build()

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

    private suspend fun hasExecutionOrContinuationPending(planId: Long): Boolean {
        return hasActiveOrPending(manualExecutionQueueName(planId)) ||
            hasActiveOrPending(scheduledExecutionQueueName(planId)) ||
            hasActiveOrPending(continuationQueueName(planId)) ||
            hasActiveOrPending(legacyManualQueueName(planId)) ||
            hasActiveOrPending(legacyScheduledQueueName(planId))
    }

    private suspend fun hasActiveOrPending(uniqueWorkName: String): Boolean = withContext(Dispatchers.IO) {
        workManager
            .getWorkInfosForUniqueWork(uniqueWorkName)
            .get()
            .any { it.state in ACTIVE_WORK_STATES }
    }

    private fun manualExecutionQueueName(planId: Long): String = "backup-run-manual-exec-plan-$planId"

    private fun scheduledExecutionQueueName(planId: Long): String = "backup-run-scheduled-exec-plan-$planId"

    private fun continuationQueueName(planId: Long): String = "backup-run-continuation-plan-$planId"

    private fun rerunTokenQueueName(planId: Long): String = "backup-run-rerun-token-plan-$planId"

    private fun legacyManualQueueName(planId: Long): String = "backup-run-manual-plan-$planId"

    private fun legacyScheduledQueueName(planId: Long): String = "backup-run-scheduled-plan-$planId"

    companion object {
        const val TAG_RUN_WORK = "backup-run-work"
        const val TAG_MANUAL_WORK = "backup-run-manual"
        const val TAG_SCHEDULED_WORK = "backup-run-scheduled"
        const val TAG_RERUN_WORK = "backup-run-rerun"
        const val TAG_PLAN_PREFIX = "backup-run-plan-"
        private const val RETRY_BACKOFF_SECONDS = 30L
        private val ACTIVE_WORK_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
        )
    }
}

enum class PlanRunEnqueueResult {
    ENQUEUED,
    COALESCED,
    IGNORED_ALREADY_ACTIVE,
    IGNORED_DISABLED,
}
