package skezza.nasbox.ui.about

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import skezza.nasbox.domain.archive.ExportBackupSetsUseCase
import skezza.nasbox.domain.archive.ImportBackupSetsUseCase
import skezza.nasbox.domain.archive.buildExportFileName

class AboutViewModel(
    private val exportBackupSetsUseCase: ExportBackupSetsUseCase,
    private val importBackupSetsUseCase: ImportBackupSetsUseCase,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun suggestedExportFileName(): String = buildExportFileName(nowEpochMs())

    fun exportBackupSets(targetUri: Uri) {
        if (_uiState.value.isWorking) return
        _uiState.value = AboutUiState(isWorking = true)
        viewModelScope.launch {
            runCatching {
                exportBackupSetsUseCase(targetUri)
            }.onSuccess { result ->
                _message.value = "Exported ${result.planCount} jobs, ${result.serverCount} servers, ${result.recordCount} records."
            }.onFailure {
                _message.value = "Export failed. Try again."
            }
            _uiState.value = AboutUiState()
        }
    }

    fun importBackupSets(sourceUri: Uri) {
        if (_uiState.value.isWorking) return
        _uiState.value = AboutUiState(isWorking = true)
        viewModelScope.launch {
            runCatching {
                importBackupSetsUseCase(sourceUri)
            }.onSuccess { result ->
                val baseMessage = "Imported ${result.createdPlanCount} jobs, ${result.createdServerCount} new servers, ${result.importedRecordCount} records."
                _message.value = if (result.serversNeedingPasswordCount > 0) {
                    "$baseMessage Re-save imported servers before running those jobs."
                } else {
                    baseMessage
                }
            }.onFailure { error ->
                _message.value = when (error) {
                    is IllegalArgumentException -> "Import failed. The file is invalid or unsupported."
                    is IOException -> "Import failed. Unable to read the selected file."
                    else -> "Import failed. The file is invalid or unsupported."
                }
            }
            _uiState.value = AboutUiState()
        }
    }

    companion object {
        fun factory(
            exportBackupSetsUseCase: ExportBackupSetsUseCase,
            importBackupSetsUseCase: ImportBackupSetsUseCase,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(AboutViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return AboutViewModel(
                        exportBackupSetsUseCase = exportBackupSetsUseCase,
                        importBackupSetsUseCase = importBackupSetsUseCase,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
        }
    }
}

data class AboutUiState(
    val isWorking: Boolean = false,
)
