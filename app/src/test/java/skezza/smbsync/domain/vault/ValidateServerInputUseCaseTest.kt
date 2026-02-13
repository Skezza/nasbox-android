package skezza.smbsync.domain.vault

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
        assertTrue(result.passwordError != null)
    }

    @Test
    fun acceptsSmbUriHostWhenShareFieldEmpty() {
        val result = useCase(
            ServerInput(
                name = "Home NAS",
                host = "smb://quanta.local/photos",
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
                host = "quanta.local",
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
