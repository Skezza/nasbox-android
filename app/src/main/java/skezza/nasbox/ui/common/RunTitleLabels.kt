package skezza.nasbox.ui.common

import java.util.Locale
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.domain.sync.RunTriggerSource

internal data class PlanDisplayInfo(
    val planName: String,
    val serverName: String?,
)

internal fun buildPlanDisplayInfoMap(
    plans: List<PlanEntity>,
    servers: List<ServerEntity>,
): Map<Long, PlanDisplayInfo> {
    val serversById = servers.associateBy { it.serverId }
    return plans.associate { plan ->
        plan.planId to PlanDisplayInfo(
            planName = plan.name.ifBlank { "Job #${plan.planId}" },
            serverName = serversById[plan.serverId]?.name,
        )
    }
}

fun runTriggerLabel(triggerSource: String): String {
    return when (triggerSource.uppercase(Locale.US)) {
        RunTriggerSource.MANUAL -> "Manual"
        RunTriggerSource.SCHEDULED -> "Scheduled"
        else -> triggerSource
            .lowercase(Locale.US)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}

fun runTitleLabel(serverName: String?, planName: String, triggerSource: String): String {
    val normalizedPlan = planName.ifBlank { "Job" }
    val target = serverName?.takeIf { it.isNotBlank() } ?: normalizedPlan
    return "$target - ${runTriggerLabel(triggerSource)}"
}
