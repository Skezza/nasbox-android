package skezza.smbsync.domain.vault

data class ServerFormInput(
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

fun validateServerForm(input: ServerFormInput): ServerValidationResult {
    return ServerValidationResult(
        nameError = input.name.requireField("Server name is required."),
        hostError = input.host.requireField("Host is required."),
        shareNameError = input.shareName.requireField("Share is required."),
        basePathError = input.basePath.requireField("Base path is required."),
        usernameError = input.username.requireField("Username is required."),
        passwordError = input.password.requireField("Password is required."),
    )
}

private fun String.requireField(message: String): String? {
    return if (trim().isEmpty()) message else null
}
