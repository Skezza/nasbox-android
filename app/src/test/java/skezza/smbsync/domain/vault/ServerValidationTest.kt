package skezza.smbsync.domain.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerValidationTest {
    @Test
    fun `validateServerForm returns errors when required fields are blank`() {
        val result = validateServerForm(
            ServerFormInput(
                name = " ",
                host = "",
                shareName = "",
                basePath = "",
                username = "",
                password = "",
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.nameError != null)
        assertTrue(result.hostError != null)
        assertTrue(result.shareNameError != null)
        assertTrue(result.basePathError != null)
        assertTrue(result.usernameError != null)
        assertTrue(result.passwordError != null)
    }

    @Test
    fun `validateServerForm accepts fully populated input`() {
        val result = validateServerForm(
            ServerFormInput(
                name = "Home NAS",
                host = "192.168.1.10",
                shareName = "photos",
                basePath = "archive",
                username = "backup_user",
                password = "topsecret",
            ),
        )

        assertTrue(result.isValid)
    }
}
