package skezza.nasbox.domain.sync

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import skezza.nasbox.work.RunPlanWorker

class EnqueuePlanRunUseCase(
    private val workManager: WorkManager,
) {

    suspend operator fun invoke(
        planId: Long,
        triggerSource: String = RunTriggerSource.MANUAL,
    ) {
        val request = OneTimeWorkRequestBuilder<RunPlanWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                Data.Builder()
                    .putLong(RunPlanWorker.KEY_PLAN_ID, planId)
                    .putString(RunPlanWorker.KEY_TRIGGER_SOURCE, triggerSource)
                    .build(),
            )
            .addTag(TAG_RUN_QUEUE)
            .addTag(TAG_PLAN_PREFIX + planId)
            .build()

        workManager.beginUniqueWork(
            UNIQUE_RUN_QUEUE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).enqueue()
    }

    companion object {
        const val UNIQUE_RUN_QUEUE = "backup-run-queue"
        const val TAG_RUN_QUEUE = "backup-run-queue-tag"
        const val TAG_PLAN_PREFIX = "backup-run-plan-"
    }
}
