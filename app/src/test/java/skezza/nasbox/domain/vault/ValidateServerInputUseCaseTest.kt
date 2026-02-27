package skezza.nasbox.domain.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateServerInputUseCaseTest {
    private val useCase = ValidateServerInputUseCase()

    @Test
    fun returnsErrorsWhenRequiredFieldsAreMissing() {
        val result = useCase(
            ServerInput(
                name = "",
                host = "",
                shareName = "",
                basePath = "",
                username = "",
                password = "",
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.nameError != null)
        assertTrue(result.passwordError == null)
    }

    @Test
    fun acceptsBlankCredentialsForGuestAccess() {
        val result = useCase(
            ServerInput(
                name = "Home NAS",
                host = "example.local",
                shareName = "photos",
                basePath = "backup",
                username = "",
                password = "",
            ),
        )

        assertTrue(result.isValid)
        assertTrue(result.usernameError == null)
        assertTrue(result.passwordError == null)
    }

    @Test
    fun requiresPasswordWhenUsernameProvided() {
        val result = useCase(
            ServerInput(
                name = "Home NAS",
                host = "example.local",
                shareName = "photos",
                basePath = "backup",
                username = "admin",
                password = "",
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.passwordError != null)
    }

    @Test
    fun acceptsSmbUriHostWhenShareFieldEmpty() {
        val result = useCase(
            ServerInput(
                name = "Home NAS",
                host = "smb://example.local/photos",
                shareName = "",
                basePath = "backup",
                username = "user",
                password = "secret",
            ),
        )

        assertTrue(result.isValid)
    }

    @Test
    fun acceptsValidInputWithoutShareForRootValidation() {
        val result = useCase(
            ServerInput(
                name = "Home NAS",
                host = "example.local",
                shareName = "",
                basePath = "backup",
                username = "user",
                password = "secret",
            ),
        )

        assertTrue(result.isValid)
    }

    @Test
    fun acceptsValidInput() {
        val result = useCase(
            ServerInput(
                name = "Home NAS",
                host = "192.168.1.10",
                shareName = "photos",
                basePath = "backup",
                username = "user",
                password = "secret",
            ),
        )

        assertTrue(result.isValid)
    }
}
