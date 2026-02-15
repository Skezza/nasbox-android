package skezza.nasbox.ui.plans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.domain.plan.PlanSourceType
import skezza.nasbox.domain.schedule.PlanScheduleFrequency
import skezza.nasbox.domain.schedule.PlanScheduleWeekday
import skezza.nasbox.domain.schedule.weeklyMaskFor

class PlansViewModelMappingTest {

    @Test
    fun editorAndEntity_roundTrip_preservesRecurrenceFields() {
        val recurrenceMask = weeklyMaskFor(
            PlanScheduleWeekday.MONDAY,
            PlanScheduleWeekday.WEDNESDAY,
            PlanScheduleWeekday.FRIDAY,
        )
        val plan = PlanEntity(
            planId = 42L,
            name = "Camera Job",
            sourceAlbum = "camera",
            sourceType = PlanSourceType.ALBUM.name,
            serverId = 99L,
            directoryTemplate = "{year}/{month}",
            filenamePattern = "{timestamp}_{mediaId}.{ext}",
            enabled = true,
            scheduleEnabled = true,
            scheduleTimeMinutes = 21 * 60,
            scheduleFrequency = PlanScheduleFrequency.WEEKLY.name,
            scheduleDaysMask = recurrenceMask,
            scheduleDayOfMonth = 17,
            scheduleIntervalHours = 12,
        )

        val editorState = editorStateFromPlanEntity(plan)
        val mappedBack = planEntityFromEditorState(editorState)

        assertEquals(PlanScheduleFrequency.WEEKLY, editorState.scheduleFrequency)
        assertEquals(recurrenceMask, editorState.scheduleDaysMask)
        assertEquals(17, editorState.scheduleDayOfMonth)
        assertEquals(12, editorState.scheduleIntervalHours)

        assertEquals(PlanScheduleFrequency.WEEKLY.name, mappedBack.scheduleFrequency)
        assertEquals(recurrenceMask, mappedBack.scheduleDaysMask)
        assertEquals(17, mappedBack.scheduleDayOfMonth)
        assertEquals(12, mappedBack.scheduleIntervalHours)
        assertEquals(21 * 60, mappedBack.scheduleTimeMinutes)
    }

    @Test
    fun planEntityFromEditorState_clampsInvalidRecurrenceInputs() {
        val editorState = PlanEditorUiState(
            editingPlanId = 7L,
            name = "Example",
            enabled = true,
            sourceType = PlanSourceType.ALBUM,
            selectedAlbumId = "album",
            selectedServerId = 100L,
            scheduleEnabled = true,
            scheduleTimeMinutes = -40,
            scheduleFrequency = PlanScheduleFrequency.MONTHLY,
            scheduleDaysMask = 0,
            scheduleDayOfMonth = 99,
            scheduleIntervalHours = 300,
        )

        val entity = planEntityFromEditorState(editorState)

        assertEquals(0, entity.scheduleTimeMinutes)
        assertTrue(entity.scheduleDaysMask != 0)
        assertEquals(31, entity.scheduleDayOfMonth)
        assertEquals(168, entity.scheduleIntervalHours)
    }
}
