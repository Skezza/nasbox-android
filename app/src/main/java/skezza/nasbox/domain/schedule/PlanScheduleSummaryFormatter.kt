package skezza.nasbox.domain.schedule

import java.util.Locale

fun formatPlanScheduleSummary(
    enabled: Boolean,
    frequency: PlanScheduleFrequency,
    scheduleTimeMinutes: Int,
    scheduleDaysMask: Int,
    scheduleDayOfMonth: Int,
    scheduleIntervalHours: Int,
): String {
    if (!enabled) return "Off"

    val minutes = normalizeScheduleMinutes(scheduleTimeMinutes)
    return when (frequency) {
        PlanScheduleFrequency.DAILY -> "Daily around ${formatMinutesOfDay(minutes)}"
        PlanScheduleFrequency.WEEKLY -> {
            val selectedDays = PlanScheduleWeekday.entries
                .filter { isDaySelected(scheduleDaysMask, it) }
                .joinToString(", ") { it.shortLabel }
            "Weekly $selectedDays at ${formatMinutesOfDay(minutes)}"
        }
        PlanScheduleFrequency.MONTHLY -> {
            val day = normalizeDayOfMonth(scheduleDayOfMonth)
            "Monthly on day $day at ${formatMinutesOfDay(minutes)} (last day fallback)"
        }
        PlanScheduleFrequency.INTERVAL_HOURS -> {
            val hours = normalizeIntervalHours(scheduleIntervalHours)
            "Every $hours hour${if (hours == 1) "" else "s"}"
        }
    }
}

fun formatMinutesOfDay(minutes: Int): String {
    val clamped = normalizeScheduleMinutes(minutes)
    val hour = clamped / 60
    val minute = clamped % 60
    return String.format(Locale.US, "%02d:%02d", hour, minute)
}
