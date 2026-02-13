package skezza.smbsync.domain.plan

enum class PlanSourceType {
    ALBUM,
    FOLDER,
}

data class PlanInput(
    val name: String,
    val sourceType: PlanSourceType,
    val selectedAlbumId: String?,
    val folderPath: String,
    val selectedServerId: Long?,
    val includeVideos: Boolean,
    val useAlbumTemplating: Boolean,
    val directoryTemplate: String,
    val filenamePattern: String,
)

data class PlanValidationResult(
    val nameError: String? = null,
    val albumError: String? = null,
    val folderPathError: String? = null,
    val serverError: String? = null,
    val templateError: String? = null,
    val filenamePatternError: String? = null,
) {
    val isValid: Boolean = listOf(
        nameError,
        albumError,
        folderPathError,
        serverError,
        templateError,
        filenamePatternError,
    ).all { it == null }
}

class ValidatePlanInputUseCase {
    operator fun invoke(input: PlanInput): PlanValidationResult {
        val albumError = if (input.sourceType == PlanSourceType.ALBUM && input.selectedAlbumId.isNullOrBlank()) {
            "Select an album."
        } else {
            null
        }
        val folderError = if (input.sourceType == PlanSourceType.FOLDER && input.folderPath.trim().isBlank()) {
            "Select or enter a folder path."
        } else {
            null
        }
        val templateError = if (
            input.sourceType == PlanSourceType.ALBUM &&
            input.useAlbumTemplating &&
            input.directoryTemplate.trim().isBlank()
        ) {
            "Directory template is required when album templating is enabled."
        } else {
            null
        }
        val filenamePatternError = if (
            input.sourceType == PlanSourceType.ALBUM &&
            input.useAlbumTemplating &&
            input.filenamePattern.trim().isBlank()
        ) {
            "Filename pattern is required when album templating is enabled."
        } else {
            null
        }

        return PlanValidationResult(
            nameError = if (input.name.trim().isBlank()) "Plan name is required." else null,
            albumError = albumError,
            folderPathError = folderError,
            serverError = if (input.selectedServerId == null) "Select a destination server." else null,
            templateError = templateError,
            filenamePatternError = filenamePatternError,
        )
    }
}
