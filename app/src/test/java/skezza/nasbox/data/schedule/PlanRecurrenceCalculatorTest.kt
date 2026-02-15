package skezza.nasbox.data.schedule

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import skezza.nasbox.domain.schedule.PlanScheduleFrequency
import skezza.nasbox.domain.schedule.PlanScheduleWeekday
import skezza.nasbox.domain.schedule.weeklyMaskFor

class PlanRecurrenceCalculatorTest {

    private val timeZone: TimeZone = TimeZone.getTimeZone("UTC")
    private val calculator = PlanRecurrenceCalculator(timeZone = timeZone)

    @Test
    fun nextRun_daily_beforeScheduledTime_usesSameDay() {
        val now = epoch(2026, Calendar.JANUARY, 1, 1, 0)

        val nextRun = calculator.nextRunEpochMs(
            nowEpochMs = now,
            frequency = PlanScheduleFrequency.DAILY,
            scheduleTimeMinutes = 2 * 60,
            scheduleDaysMask = 0,
            scheduleDayOfMonth = 1,
            scheduleIntervalHours = 24,
        )

        assertEquals(epoch(2026, Calendar.JANUARY, 1, 2, 0), nextRun)
    }

    @Test
    fun nextRun_daily_afterScheduledTime_usesNextDay() {
        val now = epoch(2026, Calendar.JANUARY, 1, 3, 0)

        val nextRun = calculator.nextRunEpochMs(
            nowEpochMs = now,
            frequency = PlanScheduleFrequency.DAILY,
            scheduleTimeMinutes = 2 * 60,
            scheduleDaysMask = 0,
            scheduleDayOfMonth = 1,
            scheduleIntervalHours = 24,
        )

        assertEquals(epoch(2026, Calendar.JANUARY, 2, 2, 0), nextRun)
    }

    @Test
    fun nextRun_weekly_acrossSelectedWeekdays() {
        val weeklyMask = weeklyMaskFor(
            PlanScheduleWeekday.TUESDAY,
            PlanScheduleWeekday.THURSDAY,
        )

        val mondayLate = epoch(2026, Calendar.JANUARY, 5, 22, 0)
        val nextFromMonday = calculator.nextRunEpochMs(
            nowEpochMs = mondayLate,
            frequency = PlanScheduleFrequency.WEEKLY,
            scheduleTimeMinutes = 21 * 60,
            scheduleDaysMask = weeklyMask,
            scheduleDayOfMonth = 1,
            scheduleIntervalHours = 24,
        )
        assertEquals(epoch(2026, Calendar.JANUARY, 6, 21, 0), nextFromMonday)

        val tuesdayLate = epoch(2026, Calendar.JANUARY, 6, 22, 0)
        val nextFromTuesday = calculator.nextRunEpochMs(
            nowEpochMs = tuesdayLate,
            frequency = PlanScheduleFrequency.WEEKLY,
            scheduleTimeMinutes = 21 * 60,
            scheduleDaysMask = weeklyMask,
            scheduleDayOfMonth = 1,
            scheduleIntervalHours = 24,
        )
        assertEquals(epoch(2026, Calendar.JANUARY, 8, 21, 0), nextFromTuesday)
    }

    @Test
    fun nextRun_monthly_overflowUsesLastDayFallback() {
        val beforeFallbackDay = epoch(2026, Calendar.FEBRUARY, 27, 12, 0)
        val nextInFebruary = calculator.nextRunEpochMs(
            nowEpochMs = beforeFallbackDay,
            frequency = PlanScheduleFrequency.MONTHLY,
            scheduleTimeMinutes = 9 * 60 + 30,
            scheduleDaysMask = 0,
            scheduleDayOfMonth = 31,
            scheduleIntervalHours = 24,
        )
        assertEquals(epoch(2026, Calendar.FEBRUARY, 28, 9, 30), nextInFebruary)

        val afterFallbackDay = epoch(2026, Calendar.FEBRUARY, 28, 12, 0)
        val nextInMarch = calculator.nextRunEpochMs(
            nowEpochMs = afterFallbackDay,
            frequency = PlanScheduleFrequency.MONTHLY,
            scheduleTimeMinutes = 9 * 60 + 30,
            scheduleDaysMask = 0,
            scheduleDayOfMonth = 31,
            scheduleIntervalHours = 24,
        )
        assertEquals(epoch(2026, Calendar.MARCH, 31, 9, 30), nextInMarch)
    }

    @Test
    fun nextRun_intervalHours_nextRunAndClamping() {
        val now = epoch(2026, Calendar.JANUARY, 1, 0, 0)

        val clampedLow = calculator.nextRunEpochMs(
            nowEpochMs = now,
            frequency = PlanScheduleFrequency.INTERVAL_HOURS,
            scheduleTimeMinutes = 0,
            scheduleDaysMask = 0,
            scheduleDayOfMonth = 1,
            scheduleIntervalHours = 0,
        )
        assertEquals(epoch(2026, Calendar.JANUARY, 1, 1, 0), clampedLow)

        val clampedHigh = calculator.nextRunEpochMs(
            nowEpochMs = now,
            frequency = PlanScheduleFrequency.INTERVAL_HOURS,
            scheduleTimeMinutes = 0,
            scheduleDaysMask = 0,
            scheduleDayOfMonth = 1,
            scheduleIntervalHours = 200,
        )
        assertEquals(epoch(2026, Calendar.JANUARY, 8, 0, 0), clampedHigh)
    }

    private fun epoch(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long {
        return Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
