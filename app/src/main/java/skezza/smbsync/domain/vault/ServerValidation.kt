package skezza.smbsync.domain.vault

data class ServerInput(
    val name: String,
    val host: String,
    val shareName: String,
    val basePath: String,
    val username: String,
    val password: String,
)

data class ServerValidationResult(
    val nameError: String? = null,
    val hostError: String? = null,
    val shareNameError: String? = null,
    val basePathError: String? = null,
    val usernameError: String? = null,
    val passwordError: String? = null,
) {
    val isValid: Boolean = listOf(
        nameError,
        hostError,
        shareNameError,
        basePathError,
        usernameError,
        passwordError,
    ).all { it == null }
}

class ValidateServerInputUseCase {
    operator fun invoke(input: ServerInput): ServerValidationResult {
        return ServerValidationResult(
            nameError = input.name.requireValue("Server name is required."),
            hostError = input.host.requireValue("Host is required."),
            shareNameError = input.shareName.requireValue("Share is required."),
            basePathError = input.basePath.requireValue("Base path is required."),
            usernameError = input.username.requireValue("Username is required."),
            passwordError = input.password.requireValue("Password is required."),
        )
    }

    private fun String.requireValue(message: String): String? {
        return if (isBlank()) message else null
    }
}
