package skezza.nasbox.data.schedule

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.domain.schedule.PlanScheduleCoordinator
import skezza.nasbox.domain.schedule.PlanScheduleFrequency
import skezza.nasbox.work.ScheduleTriggerWorker

class WorkManagerPlanScheduleCoordinator(
    private val workManager: WorkManager,
    private val planRepository: PlanRepository,
    private val recurrenceCalculator: PlanRecurrenceCalculator = PlanRecurrenceCalculator(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : PlanScheduleCoordinator {

    override suspend fun synchronizePlan(plan: PlanEntity) {
        if (!plan.enabled || !plan.scheduleEnabled) {
            cancelPlan(plan.planId)
            return
        }

        val now = nowEpochMs()
        val nextRunEpochMs = recurrenceCalculator.nextRunEpochMs(
            nowEpochMs = now,
            frequency = PlanScheduleFrequency.fromRaw(plan.scheduleFrequency),
            scheduleTimeMinutes = plan.scheduleTimeMinutes,
            scheduleDaysMask = plan.scheduleDaysMask,
            scheduleDayOfMonth = plan.scheduleDayOfMonth,
            scheduleIntervalHours = plan.scheduleIntervalHours,
        )
        val initialDelayMs = (nextRunEpochMs - now).coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<ScheduleTriggerWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(ScheduleTriggerWorker.KEY_PLAN_ID, plan.planId)
                    .build(),
            )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag(SCHEDULE_TAG)
            .addTag(planTag(plan.planId))
            .build()

        workManager.cancelUniqueWork(legacyPeriodicName(plan.planId))
        workManager.enqueueUniqueWork(
            nextRunName(plan.planId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override suspend fun cancelPlan(planId: Long) {
        workManager.cancelUniqueWork(nextRunName(planId))
        workManager.cancelUniqueWork(legacyPeriodicName(planId))
    }

    override suspend fun reconcileSchedules() {
        val plans = planRepository.observePlans().first()
        val desiredPlanIds = plans
            .filter { it.enabled && it.scheduleEnabled }
            .map { it.planId }
            .toSet()

        plans.forEach { plan ->
            if (plan.planId in desiredPlanIds) {
                synchronizePlan(plan)
            } else {
                cancelPlan(plan.planId)
            }
        }

        val existing = workManager.getWorkInfosByTag(SCHEDULE_TAG).get()
        existing.forEach { info ->
            val taggedPlanId = info.tags
                .firstOrNull { it.startsWith(PLAN_TAG_PREFIX) }
                ?.removePrefix(PLAN_TAG_PREFIX)
                ?.toLongOrNull()
            if (taggedPlanId != null && taggedPlanId !in desiredPlanIds) {
                workManager.cancelWorkById(info.id)
            }
        }
    }

    private fun nextRunName(planId: Long): String = "plan-schedule-next-$planId"
    private fun legacyPeriodicName(planId: Long): String = "plan-schedule-$planId"

    private fun planTag(planId: Long): String = "$PLAN_TAG_PREFIX$planId"

    companion object {
        private const val SCHEDULE_TAG = "plan-schedule"
        private const val PLAN_TAG_PREFIX = "plan-schedule-id-"
    }
}
