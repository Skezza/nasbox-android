package skezza.smbsync.domain.plan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatePlanInputUseCaseTest {

    private val useCase = ValidatePlanInputUseCase()

    @Test
    fun invoke_returnsErrorsWhenRequiredFieldsMissingForAlbumTemplateMode() {
        val result = useCase(
            PlanInput(
                name = "",
                sourceType = PlanSourceType.ALBUM,
                selectedAlbumId = null,
                folderPath = "",
                selectedServerId = null,
                includeVideos = true,
                useAlbumTemplating = true,
                directoryTemplate = "",
                filenamePattern = "",
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.nameError != null)
        assertTrue(result.albumError != null)
        assertTrue(result.serverError != null)
        assertTrue(result.templateError != null)
        assertTrue(result.filenamePatternError != null)
    }

    @Test
    fun invoke_requiresFolderPathForFolderPlans() {
        val result = useCase(
            PlanInput(
                name = "General Files",
                sourceType = PlanSourceType.FOLDER,
                selectedAlbumId = null,
                folderPath = "",
                selectedServerId = 42L,
                includeVideos = false,
                useAlbumTemplating = false,
                directoryTemplate = "",
                filenamePattern = "",
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.folderPathError != null)
    }

    @Test
    fun invoke_acceptsValidInputWithoutTemplating() {
        val result = useCase(
            PlanInput(
                name = "Family Photos",
                sourceType = PlanSourceType.ALBUM,
                selectedAlbumId = "123",
                folderPath = "",
                selectedServerId = 42L,
                includeVideos = true,
                useAlbumTemplating = false,
                directoryTemplate = "",
                filenamePattern = "",
            ),
        )

        assertTrue(result.isValid)
    }
}
