package skezza.smbsync.domain.plan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatePlanInputUseCaseTest {

    private val useCase = ValidatePlanInputUseCase()

    @Test
    fun invoke_returnsErrorsWhenRequiredFieldsMissing() {
        val result = useCase(
            PlanInput(
                name = "",
                selectedAlbumId = null,
                selectedServerId = null,
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
    fun invoke_acceptsValidInput() {
        val result = useCase(
            PlanInput(
                name = "Family Photos",
                selectedAlbumId = "123",
                selectedServerId = 42L,
                directoryTemplate = "{year}/{month}",
                filenamePattern = "{timestamp}_{mediaId}.{ext}",
            ),
        )

        assertTrue(result.isValid)
    }
}
