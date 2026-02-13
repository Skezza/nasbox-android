package skezza.smbsync.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import skezza.smbsync.data.db.PlanEntity
import skezza.smbsync.data.media.MediaAlbum
import skezza.smbsync.data.repository.PlanRepository
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.domain.media.ListMediaAlbumsUseCase
import skezza.smbsync.domain.media.firstCameraAlbumOrNull
import skezza.smbsync.domain.plan.PlanInput
import skezza.smbsync.domain.plan.PlanValidationResult
import skezza.smbsync.domain.plan.ValidatePlanInputUseCase

class PlansViewModel(
    private val planRepository: PlanRepository,
    private val serverRepository: ServerRepository,
    private val listMediaAlbumsUseCase: ListMediaAlbumsUseCase,
    private val validatePlanInputUseCase: ValidatePlanInputUseCase = ValidatePlanInputUseCase(),
) : ViewModel() {

    private val _editorState = MutableStateFlow(PlanEditorUiState())
    val editorState: StateFlow<PlanEditorUiState> = _editorState.asStateFlow()

    private val _albums = MutableStateFlow<List<MediaAlbum>>(emptyList())
    val albums: StateFlow<List<MediaAlbum>> = _albums.asStateFlow()

    private val _hasMediaPermission = MutableStateFlow(false)
    val hasMediaPermission: StateFlow<Boolean> = _hasMediaPermission.asStateFlow()

    private val _isLoadingAlbums = MutableStateFlow(false)
    val isLoadingAlbums: StateFlow<Boolean> = _isLoadingAlbums.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val plans: StateFlow<List<PlanListItemUiState>> = combine(
        planRepository.observePlans(),
        serverRepository.observeServers(),
    ) { plans, servers ->
        plans.map { plan ->
            val serverName = servers.firstOrNull { it.serverId == plan.serverId }?.name ?: "Unknown server"
            PlanListItemUiState(
                planId = plan.planId,
                name = plan.name,
                album = plan.sourceAlbum,
                serverName = serverName,
                enabled = plan.enabled,
                template = plan.directoryTemplate,
                filenamePattern = plan.filenamePattern,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val servers: StateFlow<List<PlanServerOption>> = serverRepository.observeServers().combine(_editorState) { servers, editor ->
        servers.map {
            PlanServerOption(
                serverId = it.serverId,
                label = "${it.name} (${it.host}/${it.shareName})",
                isSelected = editor.selectedServerId == it.serverId,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearMessage() {
        _message.value = null
    }

    fun setMediaPermissionGranted(granted: Boolean) {
        _hasMediaPermission.value = granted
        if (granted) {
            refreshAlbums()
        } else {
            _albums.value = emptyList()
        }
    }

    fun refreshAlbums() {
        if (!_hasMediaPermission.value) return
        viewModelScope.launch {
            _isLoadingAlbums.value = true
            runCatching { listMediaAlbumsUseCase() }
                .onSuccess { albums ->
                    _albums.value = albums
                    maybeApplyFirstPlanDefaults(albums)
                }
                .onFailure {
                    _message.value = "Unable to read local albums. Check media permission and try again."
                }
            _isLoadingAlbums.value = false
        }
    }

    fun loadPlanForEdit(planId: Long?) {
        viewModelScope.launch {
            if (planId == null) {
                _editorState.value = PlanEditorUiState()
                maybeApplyFirstPlanDefaults(_albums.value)
                return@launch
            }
            runCatching {
                planRepository.getPlan(planId) ?: throw IllegalStateException("Plan not found")
            }.onSuccess { plan ->
                _editorState.value = PlanEditorUiState(
                    editingPlanId = plan.planId,
                    name = plan.name,
                    enabled = plan.enabled,
                    selectedAlbumId = plan.sourceAlbum,
                    selectedServerId = plan.serverId,
                    directoryTemplate = plan.directoryTemplate,
                    filenamePattern = plan.filenamePattern,
                )
            }.onFailure {
                _message.value = "Unable to load the selected plan."
            }
        }
    }

    fun updateEditorName(value: String) { _editorState.value = _editorState.value.copy(name = value) }
    fun updateEditorEnabled(value: Boolean) { _editorState.value = _editorState.value.copy(enabled = value) }
    fun updateEditorAlbum(value: String) { _editorState.value = _editorState.value.copy(selectedAlbumId = value) }
    fun updateEditorServer(value: Long) { _editorState.value = _editorState.value.copy(selectedServerId = value) }
    fun updateEditorDirectoryTemplate(value: String) { _editorState.value = _editorState.value.copy(directoryTemplate = value) }
    fun updateEditorFilenamePattern(value: String) { _editorState.value = _editorState.value.copy(filenamePattern = value) }

    fun savePlan(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val state = _editorState.value
            val validation = validatePlanInputUseCase(
                PlanInput(
                    name = state.name,
                    selectedAlbumId = state.selectedAlbumId,
                    selectedServerId = state.selectedServerId,
                    directoryTemplate = state.directoryTemplate,
                    filenamePattern = state.filenamePattern,
                ),
            )
            if (!validation.isValid) {
                _editorState.value = state.copy(validation = validation)
                return@launch
            }

            val entity = PlanEntity(
                planId = state.editingPlanId ?: 0,
                name = state.name.trim(),
                sourceAlbum = requireNotNull(state.selectedAlbumId),
                serverId = requireNotNull(state.selectedServerId),
                directoryTemplate = state.directoryTemplate.trim(),
                filenamePattern = state.filenamePattern.trim(),
                enabled = state.enabled,
            )

            runCatching {
                if (state.editingPlanId == null) {
                    planRepository.createPlan(entity)
                } else {
                    planRepository.updatePlan(entity)
                }
            }.onSuccess {
                onSuccess()
            }.onFailure {
                _message.value = "Unable to save plan. Plan names must be unique."
            }
        }
    }

    fun deletePlan(planId: Long) {
        viewModelScope.launch {
            runCatching { planRepository.deletePlan(planId) }
                .onFailure { _message.value = "Unable to delete plan." }
        }
    }

    private suspend fun maybeApplyFirstPlanDefaults(albums: List<MediaAlbum>) {
        val state = _editorState.value
        if (state.editingPlanId != null || state.name.isNotBlank()) return
        val hasPlans = planRepository.observePlans().first().isNotEmpty()
        if (hasPlans) return

        val defaultAlbum = albums.firstCameraAlbumOrNull() ?: albums.firstOrNull()
        _editorState.value = state.copy(
            selectedAlbumId = defaultAlbum?.bucketId,
            directoryTemplate = if (state.directoryTemplate.isBlank()) "{year}/{month}" else state.directoryTemplate,
            filenamePattern = if (state.filenamePattern.isBlank()) "{timestamp}_{mediaId}.{ext}" else state.filenamePattern,
        )
    }

    companion object {
        fun factory(
            planRepository: PlanRepository,
            serverRepository: ServerRepository,
            listMediaAlbumsUseCase: ListMediaAlbumsUseCase,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(PlansViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return PlansViewModel(
                        planRepository = planRepository,
                        serverRepository = serverRepository,
                        listMediaAlbumsUseCase = listMediaAlbumsUseCase,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
        }
    }
}

data class PlanListItemUiState(
    val planId: Long,
    val name: String,
    val album: String,
    val serverName: String,
    val enabled: Boolean,
    val template: String,
    val filenamePattern: String,
)

data class PlanServerOption(
    val serverId: Long,
    val label: String,
    val isSelected: Boolean,
)

data class PlanEditorUiState(
    val editingPlanId: Long? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val selectedAlbumId: String? = null,
    val selectedServerId: Long? = null,
    val directoryTemplate: String = "",
    val filenamePattern: String = "",
    val validation: PlanValidationResult = PlanValidationResult(),
)
