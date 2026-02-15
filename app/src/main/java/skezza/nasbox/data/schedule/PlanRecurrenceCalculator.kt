package skezza.nasbox.data.schedule

import java.util.Calendar
import java.util.TimeZone
import skezza.nasbox.domain.schedule.PlanScheduleFrequency
import skezza.nasbox.domain.schedule.PlanScheduleWeekday
import skezza.nasbox.domain.schedule.isDaySelected
import skezza.nasbox.domain.schedule.normalizeDayOfMonth
import skezza.nasbox.domain.schedule.normalizeIntervalHours
import skezza.nasbox.domain.schedule.normalizeScheduleMinutes
import skezza.nasbox.domain.schedule.normalizeWeeklyDaysMask

class PlanRecurrenceCalculator(
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {

    fun nextRunEpochMs(
        nowEpochMs: Long,
        frequency: PlanScheduleFrequency,
        scheduleTimeMinutes: Int,
        scheduleDaysMask: Int,
        scheduleDayOfMonth: Int,
        scheduleIntervalHours: Int,
    ): Long {
        return when (frequency) {
            PlanScheduleFrequency.DAILY -> nextDaily(nowEpochMs, scheduleTimeMinutes)
            PlanScheduleFrequency.WEEKLY -> nextWeekly(nowEpochMs, scheduleTimeMinutes, scheduleDaysMask)
            PlanScheduleFrequency.MONTHLY -> nextMonthly(nowEpochMs, scheduleTimeMinutes, scheduleDayOfMonth)
            PlanScheduleFrequency.INTERVAL_HOURS -> nextInterval(nowEpochMs, scheduleIntervalHours)
        }
    }

    private fun nextDaily(nowEpochMs: Long, scheduleTimeMinutes: Int): Long {
        val candidate = baseCandidate(nowEpochMs, scheduleTimeMinutes)
        if (candidate.timeInMillis <= nowEpochMs) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate.timeInMillis
    }

    private fun nextWeekly(
        nowEpochMs: Long,
        scheduleTimeMinutes: Int,
        scheduleDaysMask: Int,
    ): Long {
        val normalizedMask = normalizeWeeklyDaysMask(scheduleDaysMask)
        val candidateBase = baseCandidate(nowEpochMs, scheduleTimeMinutes)
        for (dayOffset in 0..7) {
            val candidate = (candidateBase.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val weekday = weekdayForCalendarDay(candidate.get(Calendar.DAY_OF_WEEK))
            if (!isDaySelected(normalizedMask, weekday)) continue
            if (candidate.timeInMillis > nowEpochMs) return candidate.timeInMillis
        }

        val fallback = (candidateBase.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
        }
        while (!isDaySelected(normalizedMask, weekdayForCalendarDay(fallback.get(Calendar.DAY_OF_WEEK)))) {
            fallback.add(Calendar.DAY_OF_YEAR, 1)
        }
        return fallback.timeInMillis
    }

    private fun nextMonthly(
        nowEpochMs: Long,
        scheduleTimeMinutes: Int,
        scheduleDayOfMonth: Int,
    ): Long {
        val normalizedDay = normalizeDayOfMonth(scheduleDayOfMonth)
        val candidate = baseCandidate(nowEpochMs, scheduleTimeMinutes)
        applyDayOfMonthFallback(candidate, normalizedDay)
        if (candidate.timeInMillis <= nowEpochMs) {
            candidate.add(Calendar.MONTH, 1)
            applyDayOfMonthFallback(candidate, normalizedDay)
        }
        return candidate.timeInMillis
    }

    private fun nextInterval(nowEpochMs: Long, scheduleIntervalHours: Int): Long {
        val hours = normalizeIntervalHours(scheduleIntervalHours)
        return nowEpochMs + hours.toLong() * HOUR_MS
    }

    private fun baseCandidate(nowEpochMs: Long, scheduleTimeMinutes: Int): Calendar {
        val normalizedMinutes = normalizeScheduleMinutes(scheduleTimeMinutes)
        return calendarFor(nowEpochMs).apply {
            set(Calendar.HOUR_OF_DAY, normalizedMinutes / 60)
            set(Calendar.MINUTE, normalizedMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun applyDayOfMonthFallback(calendar: Calendar, targetDay: Int) {
        val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        calendar.set(Calendar.DAY_OF_MONTH, minOf(targetDay, maxDay))
    }

    private fun weekdayForCalendarDay(calendarDay: Int): PlanScheduleWeekday = when (calendarDay) {
        Calendar.MONDAY -> PlanScheduleWeekday.MONDAY
        Calendar.TUESDAY -> PlanScheduleWeekday.TUESDAY
        Calendar.WEDNESDAY -> PlanScheduleWeekday.WEDNESDAY
        Calendar.THURSDAY -> PlanScheduleWeekday.THURSDAY
        Calendar.FRIDAY -> PlanScheduleWeekday.FRIDAY
        Calendar.SATURDAY -> PlanScheduleWeekday.SATURDAY
        Calendar.SUNDAY -> PlanScheduleWeekday.SUNDAY
        else -> PlanScheduleWeekday.MONDAY
    }

    private fun calendarFor(epochMs: Long): Calendar {
        return Calendar.getInstance(timeZone).apply { timeInMillis = epochMs }
    }

    companion object {
        private const val HOUR_MS = 60L * 60L * 1000L
    }
}
