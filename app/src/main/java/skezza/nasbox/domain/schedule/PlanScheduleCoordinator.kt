package skezza.nasbox.domain.schedule

import skezza.nasbox.data.db.PlanEntity

interface PlanScheduleCoordinator {
    suspend fun synchronizePlan(plan: PlanEntity)
    suspend fun cancelPlan(planId: Long)
    suspend fun reconcileSchedules()
}
