package skezza.smbsync.domain.plan

data class PlanInput(
    val name: String,
    val selectedAlbumId: String?,
    val selectedServerId: Long?,
    val directoryTemplate: String,
    val filenamePattern: String,
)

data class PlanValidationResult(
    val nameError: String? = null,
    val albumError: String? = null,
    val serverError: String? = null,
    val templateError: String? = null,
    val filenamePatternError: String? = null,
) {
    val isValid: Boolean = listOf(
        nameError,
        albumError,
        serverError,
        templateError,
        filenamePatternError,
    ).all { it == null }
}

class ValidatePlanInputUseCase {
    operator fun invoke(input: PlanInput): PlanValidationResult {
        return PlanValidationResult(
            nameError = if (input.name.trim().isBlank()) "Plan name is required." else null,
            albumError = if (input.selectedAlbumId.isNullOrBlank()) "Select an album." else null,
            serverError = if (input.selectedServerId == null) "Select a destination server." else null,
            templateError = if (input.directoryTemplate.trim().isBlank()) "Directory template is required." else null,
            filenamePatternError = if (input.filenamePattern.trim().isBlank()) "Filename pattern is required." else null,
        )
    }
}
