package skezza.nasbox.ui.plans

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
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.media.MediaAlbum
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.domain.media.ListMediaAlbumsUseCase
import skezza.nasbox.domain.media.firstCameraAlbumOrNull
import skezza.nasbox.domain.plan.PlanInput
import skezza.nasbox.domain.plan.PlanSourceType
import skezza.nasbox.domain.plan.PlanValidationResult
import skezza.nasbox.domain.plan.ValidatePlanInputUseCase
import skezza.nasbox.domain.schedule.PLAN_DEFAULT_DAY_OF_MONTH
import skezza.nasbox.domain.schedule.PLAN_DEFAULT_INTERVAL_HOURS
import skezza.nasbox.domain.schedule.PLAN_DEFAULT_SCHEDULE_MINUTES
import skezza.nasbox.domain.schedule.PLAN_WEEKLY_ALL_DAYS_MASK
import skezza.nasbox.domain.schedule.PlanScheduleCoordinator
import skezza.nasbox.domain.schedule.PlanScheduleFrequency
import skezza.nasbox.domain.schedule.PlanScheduleWeekday
import skezza.nasbox.domain.schedule.formatPlanScheduleSummary
import skezza.nasbox.domain.schedule.normalizeDayOfMonth
import skezza.nasbox.domain.schedule.normalizeIntervalHours
import skezza.nasbox.domain.schedule.normalizeScheduleMinutes
import skezza.nasbox.domain.schedule.normalizeWeeklyDaysMask
import skezza.nasbox.domain.schedule.weeklyMaskFor
import skezza.nasbox.domain.sync.EnqueuePlanRunUseCase
import skezza.nasbox.domain.sync.RunTriggerSource

class PlansViewModel(
    private val planRepository: PlanRepository,
    private val serverRepository: ServerRepository,
    private val listMediaAlbumsUseCase: ListMediaAlbumsUseCase,
    private val enqueuePlanRunUseCase: EnqueuePlanRunUseCase,
    private val planScheduleCoordinator: PlanScheduleCoordinator,
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

    private val _activeRunPlanIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeRunPlanIds: StateFlow<Set<Long>> = _activeRunPlanIds.asStateFlow()

    val plans: StateFlow<List<PlanListItemUiState>> = combine(
        planRepository.observePlans(),
        serverRepository.observeServers(),
    ) { plans, servers ->
        plans.map { plan ->
            val serverName = servers.firstOrNull { it.serverId == plan.serverId }?.name ?: "Unknown server"
            val sourceType = parsePlanSourceType(plan.sourceType)
            val sourceSummary = when (sourceType) {
                PlanSourceType.ALBUM -> "Album (${plan.sourceAlbum})${if (plan.includeVideos) " + videos" else ""}"
                PlanSourceType.FOLDER -> "Folder (${plan.folderPath.ifBlank { "not set" }})"
                PlanSourceType.FULL_DEVICE -> "Full device backup (shared storage)"
            }
            val scheduleFrequency = PlanScheduleFrequency.fromRaw(plan.scheduleFrequency)
            val scheduleDaysMask = normalizeWeeklyDaysMask(plan.scheduleDaysMask)
            val scheduleDayOfMonth = normalizeDayOfMonth(plan.scheduleDayOfMonth)
            val scheduleIntervalHours = normalizeIntervalHours(plan.scheduleIntervalHours)
            PlanListItemUiState(
                planId = plan.planId,
                name = plan.name,
                sourceType = sourceType,
                sourceSummary = sourceSummary,
                serverName = serverName,
                enabled = plan.enabled,
                includeVideos = plan.includeVideos,
                useAlbumTemplating = plan.useAlbumTemplating,
                template = plan.directoryTemplate,
                filenamePattern = plan.filenamePattern,
                scheduleEnabled = plan.scheduleEnabled,
                scheduleTimeMinutes = normalizeScheduleMinutes(plan.scheduleTimeMinutes),
                scheduleFrequency = scheduleFrequency,
                scheduleDaysMask = scheduleDaysMask,
                scheduleDayOfMonth = scheduleDayOfMonth,
                scheduleIntervalHours = scheduleIntervalHours,
                scheduleSummary = formatPlanScheduleSummary(
                    enabled = plan.scheduleEnabled,
                    frequency = scheduleFrequency,
                    scheduleTimeMinutes = plan.scheduleTimeMinutes,
                    scheduleDaysMask = scheduleDaysMask,
                    scheduleDayOfMonth = scheduleDayOfMonth,
                    scheduleIntervalHours = scheduleIntervalHours,
                ),
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

    fun showMessage(message: String) {
        _message.value = message
    }

    fun setMediaPermissionGranted(granted: Boolean) {
        _hasMediaPermission.value = granted
        if (granted) refreshAlbums() else _albums.value = emptyList()
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
                _editorState.value = editorStateFromPlanEntity(plan)
            }.onFailure {
                _message.value = "Unable to load the selected job."
            }
        }
    }

    fun updateEditorName(value: String) { _editorState.value = _editorState.value.copy(name = value) }
    fun updateEditorEnabled(value: Boolean) { _editorState.value = _editorState.value.copy(enabled = value) }
    fun updateEditorSourceType(value: PlanSourceType) { _editorState.value = _editorState.value.copy(sourceType = value) }
    fun updateEditorAlbum(value: String) { _editorState.value = _editorState.value.copy(selectedAlbumId = value) }
    fun updateEditorFolderPath(value: String) { _editorState.value = _editorState.value.copy(folderPath = value) }
    fun updateEditorServer(value: Long) { _editorState.value = _editorState.value.copy(selectedServerId = value) }
    fun updateEditorIncludeVideos(value: Boolean) { _editorState.value = _editorState.value.copy(includeVideos = value) }
    fun updateEditorUseAlbumTemplating(value: Boolean) { _editorState.value = _editorState.value.copy(useAlbumTemplating = value) }
    fun updateEditorDirectoryTemplate(value: String) { _editorState.value = _editorState.value.copy(directoryTemplate = value) }
    fun updateEditorFilenamePattern(value: String) { _editorState.value = _editorState.value.copy(filenamePattern = value) }
    fun updateEditorScheduleEnabled(value: Boolean) { _editorState.value = _editorState.value.copy(scheduleEnabled = value) }
    fun updateEditorScheduleTimeMinutes(value: Int) {
        _editorState.value = _editorState.value.copy(scheduleTimeMinutes = normalizeScheduleMinutes(value))
    }

    fun updateEditorScheduleFrequency(value: PlanScheduleFrequency) {
        val state = _editorState.value
        _editorState.value = state.copy(
            scheduleFrequency = value,
            scheduleDaysMask = if (value == PlanScheduleFrequency.WEEKLY) {
                normalizeWeeklyDaysMask(state.scheduleDaysMask)
            } else {
                state.scheduleDaysMask
            },
        )
    }

    fun toggleEditorScheduleWeekday(weekday: PlanScheduleWeekday) {
        val state = _editorState.value
        val toggledMask = state.scheduleDaysMask xor weekday.bitMask
        if (toggledMask == 0) return
        _editorState.value = state.copy(scheduleDaysMask = normalizeWeeklyDaysMask(toggledMask))
    }

    fun updateEditorScheduleDayOfMonth(value: Int) {
        _editorState.value = _editorState.value.copy(scheduleDayOfMonth = normalizeDayOfMonth(value))
    }

    fun updateEditorScheduleIntervalHours(value: Int) {
        _editorState.value = _editorState.value.copy(scheduleIntervalHours = normalizeIntervalHours(value))
    }

    fun applySchedulePreset(preset: PlanSchedulePreset) {
        val state = _editorState.value
        _editorState.value = when (preset) {
            PlanSchedulePreset.NIGHTLY -> state.copy(
                scheduleEnabled = true,
                scheduleFrequency = PlanScheduleFrequency.DAILY,
                scheduleTimeMinutes = 2 * 60,
            )
            PlanSchedulePreset.WORKDAYS -> state.copy(
                scheduleEnabled = true,
                scheduleFrequency = PlanScheduleFrequency.WEEKLY,
                scheduleDaysMask = weeklyMaskFor(
                    PlanScheduleWeekday.MONDAY,
                    PlanScheduleWeekday.TUESDAY,
                    PlanScheduleWeekday.WEDNESDAY,
                    PlanScheduleWeekday.THURSDAY,
                    PlanScheduleWeekday.FRIDAY,
                ),
                scheduleTimeMinutes = 21 * 60,
            )
            PlanSchedulePreset.WEEKEND -> state.copy(
                scheduleEnabled = true,
                scheduleFrequency = PlanScheduleFrequency.WEEKLY,
                scheduleDaysMask = weeklyMaskFor(
                    PlanScheduleWeekday.SATURDAY,
                    PlanScheduleWeekday.SUNDAY,
                ),
                scheduleTimeMinutes = 9 * 60,
            )
            PlanSchedulePreset.EVERY_6_HOURS -> state.copy(
                scheduleEnabled = true,
                scheduleFrequency = PlanScheduleFrequency.INTERVAL_HOURS,
                scheduleIntervalHours = 6,
            )
        }
    }

    fun savePlan(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val state = _editorState.value
            val validation = validatePlanInputUseCase(
                PlanInput(
                    name = state.name,
                    sourceType = state.sourceType,
                    selectedAlbumId = state.selectedAlbumId,
                    folderPath = state.folderPath,
                    selectedServerId = state.selectedServerId,
                    includeVideos = state.includeVideos,
                    useAlbumTemplating = state.useAlbumTemplating,
                    directoryTemplate = state.directoryTemplate,
                    filenamePattern = state.filenamePattern,
                ),
            )
            if (!validation.isValid) {
                _editorState.value = state.copy(validation = validation)
                return@launch
            }

            val entity = planEntityFromEditorState(state)

            runCatching {
                val saved = if (state.editingPlanId == null) {
                    val newId = planRepository.createPlan(entity)
                    entity.copy(planId = newId)
                } else {
                    planRepository.updatePlan(entity)
                    entity
                }
                planScheduleCoordinator.synchronizePlan(saved)
            }.onSuccess {
                onSuccess()
            }.onFailure {
                _message.value = "Unable to save job. Job names must be unique."
            }
        }
    }

    fun runPlanNow(planId: Long) {
        viewModelScope.launch {
            _activeRunPlanIds.value = _activeRunPlanIds.value + planId
            runCatching {
                enqueuePlanRunUseCase(planId, RunTriggerSource.MANUAL)
            }
                .onSuccess {
                    _message.value = "Run queued."
                }
                .onFailure { error ->
                    val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
                    _message.value = "Run could not start: $detail"
                }
            _activeRunPlanIds.value = _activeRunPlanIds.value - planId
        }
    }

    fun deletePlan(planId: Long) {
        viewModelScope.launch {
            runCatching {
                planScheduleCoordinator.cancelPlan(planId)
                planRepository.deletePlan(planId)
            }
                .onFailure { _message.value = "Unable to delete job." }
        }
    }

    private suspend fun maybeApplyFirstPlanDefaults(albums: List<MediaAlbum>) {
        val state = _editorState.value
        if (state.editingPlanId != null || state.name.isNotBlank()) return
        if (planRepository.observePlans().first().isNotEmpty()) return

        val defaultAlbum = albums.firstCameraAlbumOrNull() ?: albums.firstOrNull()
        _editorState.value = state.copy(
            sourceType = PlanSourceType.ALBUM,
            selectedAlbumId = defaultAlbum?.bucketId,
            includeVideos = true,
            useAlbumTemplating = false,
            directoryTemplate = if (state.directoryTemplate.isBlank()) "{year}/{month}" else state.directoryTemplate,
            filenamePattern = if (state.filenamePattern.isBlank()) "{timestamp}_{mediaId}.{ext}" else state.filenamePattern,
        )
    }

    companion object {
        fun factory(
            planRepository: PlanRepository,
            serverRepository: ServerRepository,
            listMediaAlbumsUseCase: ListMediaAlbumsUseCase,
            enqueuePlanRunUseCase: EnqueuePlanRunUseCase,
            planScheduleCoordinator: PlanScheduleCoordinator,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(PlansViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return PlansViewModel(
                        planRepository,
                        serverRepository,
                        listMediaAlbumsUseCase,
                        enqueuePlanRunUseCase,
                        planScheduleCoordinator,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
        }
    }
}

enum class PlanSchedulePreset {
    NIGHTLY,
    WORKDAYS,
    WEEKEND,
    EVERY_6_HOURS,
}

data class PlanListItemUiState(
    val planId: Long,
    val name: String,
    val sourceType: PlanSourceType,
    val sourceSummary: String,
    val serverName: String,
    val enabled: Boolean,
    val includeVideos: Boolean,
    val useAlbumTemplating: Boolean,
    val template: String,
    val filenamePattern: String,
    val scheduleEnabled: Boolean,
    val scheduleTimeMinutes: Int,
    val scheduleFrequency: PlanScheduleFrequency,
    val scheduleDaysMask: Int,
    val scheduleDayOfMonth: Int,
    val scheduleIntervalHours: Int,
    val scheduleSummary: String,
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
    val sourceType: PlanSourceType = PlanSourceType.ALBUM,
    val selectedAlbumId: String? = null,
    val folderPath: String = "",
    val selectedServerId: Long? = null,
    val includeVideos: Boolean = false,
    val useAlbumTemplating: Boolean = false,
    val directoryTemplate: String = "",
    val filenamePattern: String = "",
    val scheduleEnabled: Boolean = false,
    val scheduleTimeMinutes: Int = PLAN_DEFAULT_SCHEDULE_MINUTES,
    val scheduleFrequency: PlanScheduleFrequency = PlanScheduleFrequency.DAILY,
    val scheduleDaysMask: Int = PLAN_WEEKLY_ALL_DAYS_MASK,
    val scheduleDayOfMonth: Int = PLAN_DEFAULT_DAY_OF_MONTH,
    val scheduleIntervalHours: Int = PLAN_DEFAULT_INTERVAL_HOURS,
    val validation: PlanValidationResult = PlanValidationResult(),
)

internal fun parsePlanSourceType(value: String): PlanSourceType =
    PlanSourceType.entries.firstOrNull { it.name == value } ?: PlanSourceType.ALBUM

internal fun editorStateFromPlanEntity(plan: PlanEntity): PlanEditorUiState {
    return PlanEditorUiState(
        editingPlanId = plan.planId,
        name = plan.name,
        enabled = plan.enabled,
        sourceType = parsePlanSourceType(plan.sourceType),
        selectedAlbumId = plan.sourceAlbum.ifBlank { null },
        folderPath = plan.folderPath,
        selectedServerId = plan.serverId,
        includeVideos = plan.includeVideos,
        useAlbumTemplating = plan.useAlbumTemplating,
        directoryTemplate = plan.directoryTemplate,
        filenamePattern = plan.filenamePattern,
        scheduleEnabled = plan.scheduleEnabled,
        scheduleTimeMinutes = normalizeScheduleMinutes(plan.scheduleTimeMinutes),
        scheduleFrequency = PlanScheduleFrequency.fromRaw(plan.scheduleFrequency),
        scheduleDaysMask = normalizeWeeklyDaysMask(plan.scheduleDaysMask),
        scheduleDayOfMonth = normalizeDayOfMonth(plan.scheduleDayOfMonth),
        scheduleIntervalHours = normalizeIntervalHours(plan.scheduleIntervalHours),
    )
}

internal fun planEntityFromEditorState(state: PlanEditorUiState): PlanEntity {
    val isAlbum = state.sourceType == PlanSourceType.ALBUM
    val isFolder = state.sourceType == PlanSourceType.FOLDER
    return PlanEntity(
        planId = state.editingPlanId ?: 0,
        name = state.name.trim(),
        sourceAlbum = if (isAlbum) state.selectedAlbumId.orEmpty() else "",
        sourceType = state.sourceType.name,
        folderPath = when {
            isFolder -> state.folderPath.trim()
            state.sourceType == PlanSourceType.FULL_DEVICE -> FULL_DEVICE_PRESET
            else -> ""
        },
        includeVideos = isAlbum && state.includeVideos,
        useAlbumTemplating = isAlbum && state.useAlbumTemplating,
        serverId = requireNotNull(state.selectedServerId),
        directoryTemplate = if (isAlbum && state.useAlbumTemplating) state.directoryTemplate.trim() else "",
        filenamePattern = if (isAlbum && state.useAlbumTemplating) state.filenamePattern.trim() else "",
        enabled = state.enabled,
        scheduleEnabled = state.scheduleEnabled,
        scheduleTimeMinutes = normalizeScheduleMinutes(state.scheduleTimeMinutes),
        scheduleFrequency = state.scheduleFrequency.name,
        scheduleDaysMask = normalizeWeeklyDaysMask(state.scheduleDaysMask),
        scheduleDayOfMonth = normalizeDayOfMonth(state.scheduleDayOfMonth),
        scheduleIntervalHours = normalizeIntervalHours(state.scheduleIntervalHours),
    )
}

private const val FULL_DEVICE_PRESET = "FULL_DEVICE_SHARED_STORAGE"
