package skezza.nasbox.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import skezza.nasbox.AppContainer
import skezza.nasbox.domain.sync.RunTriggerSource

class ScheduleTriggerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val appContainer = AppContainer(appContext)

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(KEY_PLAN_ID, -1L)
        if (planId <= 0L) return Result.failure()

        return runCatching {
            val plan = appContainer.planRepository.getPlan(planId) ?: return@runCatching Result.success()
            if (!plan.enabled || !plan.scheduleEnabled) {
                appContainer.planScheduleCoordinator.cancelPlan(planId)
                return@runCatching Result.success()
            }
            appContainer.enqueuePlanRunUseCase(
                planId = planId,
                triggerSource = RunTriggerSource.SCHEDULED,
            )
            appContainer.planScheduleCoordinator.synchronizePlan(plan)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PLAN_ID = "schedule_plan_id"
    }
}
