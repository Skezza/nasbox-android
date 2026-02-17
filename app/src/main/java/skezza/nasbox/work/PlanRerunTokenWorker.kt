package skezza.nasbox.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import skezza.nasbox.AppContainer
import skezza.nasbox.domain.sync.PlanRunEnqueueResult

class PlanRerunTokenWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val appContainer = AppContainer(appContext)

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(KEY_PLAN_ID, -1L)
        if (planId <= 0L) return Result.failure()

        return runCatching {
            when (appContainer.enqueuePlanRunUseCase.drainRerunToken(planId)) {
                PlanRunEnqueueResult.ENQUEUED,
                PlanRunEnqueueResult.IGNORED_DISABLED,
                -> Result.success()

                PlanRunEnqueueResult.COALESCED,
                PlanRunEnqueueResult.IGNORED_ALREADY_ACTIVE,
                -> Result.retry()
            }
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PLAN_ID = "rerun_plan_id"
    }
}
