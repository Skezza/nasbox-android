package skezza.nasbox.domain.schedule

import java.util.Locale

enum class PlanScheduleFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    INTERVAL_HOURS,
    ;

    companion object {
        fun fromRaw(value: String?): PlanScheduleFrequency {
            val normalized = value?.trim()?.uppercase(Locale.US).orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: DAILY
        }
    }
}

enum class PlanScheduleWeekday(
    val bitIndex: Int,
    val shortLabel: String,
) {
    MONDAY(bitIndex = 0, shortLabel = "Mon"),
    TUESDAY(bitIndex = 1, shortLabel = "Tue"),
    WEDNESDAY(bitIndex = 2, shortLabel = "Wed"),
    THURSDAY(bitIndex = 3, shortLabel = "Thu"),
    FRIDAY(bitIndex = 4, shortLabel = "Fri"),
    SATURDAY(bitIndex = 5, shortLabel = "Sat"),
    SUNDAY(bitIndex = 6, shortLabel = "Sun"),
    ;

    val bitMask: Int get() = 1 shl bitIndex
}

const val PLAN_MINUTES_PER_DAY = 24 * 60
const val PLAN_DEFAULT_SCHEDULE_MINUTES = 120
const val PLAN_DEFAULT_DAY_OF_MONTH = 1
const val PLAN_DEFAULT_INTERVAL_HOURS = 24
const val PLAN_MAX_INTERVAL_HOURS = 168
const val PLAN_WEEKLY_ALL_DAYS_MASK = 0b111_1111

fun normalizeScheduleMinutes(value: Int): Int = value.coerceIn(0, PLAN_MINUTES_PER_DAY - 1)

fun normalizeWeeklyDaysMask(value: Int): Int {
    val normalized = value and PLAN_WEEKLY_ALL_DAYS_MASK
    return if (normalized == 0) PLAN_WEEKLY_ALL_DAYS_MASK else normalized
}

fun normalizeDayOfMonth(value: Int): Int = value.coerceIn(1, 31)

fun normalizeIntervalHours(value: Int): Int = value.coerceIn(1, PLAN_MAX_INTERVAL_HOURS)

fun weeklyMaskFor(vararg days: PlanScheduleWeekday): Int {
    if (days.isEmpty()) return PLAN_WEEKLY_ALL_DAYS_MASK
    return days.fold(0) { mask, day -> mask or day.bitMask }
}

fun isDaySelected(mask: Int, weekday: PlanScheduleWeekday): Boolean {
    return normalizeWeeklyDaysMask(mask) and weekday.bitMask != 0
}
