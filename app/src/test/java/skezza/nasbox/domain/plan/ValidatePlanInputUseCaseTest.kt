package skezza.nasbox.domain.plan

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
    fun invoke_acceptsFullDevicePlanWithoutFolderOrAlbum() {
        val result = useCase(
            PlanInput(
                name = "Phone backup",
                sourceType = PlanSourceType.FULL_DEVICE,
                selectedAlbumId = null,
                folderPath = "",
                selectedServerId = 42L,
                includeVideos = false,
                useAlbumTemplating = false,
                directoryTemplate = "",
                filenamePattern = "",
            ),
        )

        assertTrue(result.isValid)
    }
}
