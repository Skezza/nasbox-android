package skezza.nasbox.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.ui.common.PlanDisplayInfo
import skezza.nasbox.ui.common.buildPlanDisplayInfoMap
import skezza.nasbox.domain.sync.RunStatus

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModel(
    private val planRepository: PlanRepository,
    private val serverRepository: ServerRepository,
    private val runRepository: RunRepository,
    private val runLogRepository: RunLogRepository,
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(AuditFilter.ALL)
    private val selectedRunId = MutableStateFlow<Long?>(null)

    val listUiState: StateFlow<AuditListUiState> = combine(
        planRepository.observePlans(),
        serverRepository.observeServers(),
        runRepository.observeLatestRuns(RUN_LIST_LIMIT),
        selectedFilter,
    ) { plans, servers, runs, filter ->
        val planInfoById = buildPlanDisplayInfoMap(plans, servers)
        val mapped = runs.map { run ->
            run.toListItem(planInfoById[run.planId])
        }
        val filtered = mapped.filter { item ->
            when (filter) {
                AuditFilter.ALL -> true
                AuditFilter.SUCCESS -> item.status == RunStatus.SUCCESS
                AuditFilter.PARTIAL -> item.status == RunStatus.PARTIAL
                AuditFilter.FAILED -> item.status == RunStatus.FAILED
                AuditFilter.INTERRUPTED -> item.status == RunStatus.INTERRUPTED
                AuditFilter.CANCELED -> item.status == RunStatus.CANCELED
                AuditFilter.RUNNING ->
                    item.status == RunStatus.RUNNING || item.status == RunStatus.CANCEL_REQUESTED
            }
        }
        AuditListUiState(
            selectedFilter = filter,
            runs = filtered,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AuditListUiState(),
    )

    val detailUiState: StateFlow<AuditDetailUiState> = selectedRunId.flatMapLatest { runId ->
        if (runId == null) {
            flowOf(AuditDetailUiState())
        } else {
            combine(
                planRepository.observePlans(),
                serverRepository.observeServers(),
                runRepository.observeRun(runId),
                runLogRepository.observeLogsForRunNewest(runId, RUN_LOG_LIMIT),
            ) { plans, servers, run, logs ->
                val planInfoById = buildPlanDisplayInfoMap(plans, servers)
                val planInfo = run?.let { planInfoById[it.planId] }

                AuditDetailUiState(
                    run = run?.toDetailSummary(planInfo),
                    logs = logs.map { it.toDetailItem() },
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AuditDetailUiState(),
    )

    fun setFilter(filter: AuditFilter) {
        selectedFilter.value = filter
    }

    fun selectRun(runId: Long) {
        selectedRunId.value = runId
    }

    private fun RunEntity.toListItem(planInfo: PlanDisplayInfo?): AuditRunListItem = AuditRunListItem(
        runId = runId,
        planName = planInfo?.planName ?: "Job #$planId",
        serverName = planInfo?.serverName,
        status = status,
        triggerSource = triggerSource,
        executionMode = executionMode,
        phase = phase,
        startedAtEpochMs = startedAtEpochMs,
        finishedAtEpochMs = finishedAtEpochMs,
        uploadedCount = uploadedCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        summaryError = summaryError,
    )

    private fun RunEntity.toDetailSummary(planInfo: PlanDisplayInfo?): AuditRunSummary = AuditRunSummary(
        runId = runId,
        planName = planInfo?.planName ?: "Job #$planId",
        serverName = planInfo?.serverName,
        status = status,
        triggerSource = triggerSource,
        executionMode = executionMode,
        phase = phase,
        startedAtEpochMs = startedAtEpochMs,
        finishedAtEpochMs = finishedAtEpochMs,
        uploadedCount = uploadedCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        summaryError = summaryError,
    )

    private fun RunLogEntity.toDetailItem(): AuditLogItem = AuditLogItem(
        logId = logId,
        timestampEpochMs = timestampEpochMs,
        severity = severity,
        message = message,
        detail = detail,
    )

    companion object {
        private const val RUN_LIST_LIMIT = 200
        private const val RUN_LOG_LIMIT = 300

        fun factory(
            planRepository: PlanRepository,
            serverRepository: ServerRepository,
            runRepository: RunRepository,
            runLogRepository: RunLogRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(AuditViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return AuditViewModel(
                        planRepository,
                        serverRepository,
                        runRepository,
                        runLogRepository,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
        }
    }
}

enum class AuditFilter {
    ALL,
    SUCCESS,
    PARTIAL,
    FAILED,
    INTERRUPTED,
    CANCELED,
    RUNNING,
}

data class AuditListUiState(
    val selectedFilter: AuditFilter = AuditFilter.ALL,
    val runs: List<AuditRunListItem> = emptyList(),
)

data class AuditRunListItem(
    val runId: Long,
    val planName: String,
    val serverName: String?,
    val status: String,
    val triggerSource: String,
    val executionMode: String,
    val phase: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String?,
)

data class AuditDetailUiState(
    val run: AuditRunSummary? = null,
    val logs: List<AuditLogItem> = emptyList(),
)

data class AuditRunSummary(
    val runId: Long,
    val planName: String,
    val serverName: String?,
    val status: String,
    val triggerSource: String,
    val executionMode: String,
    val phase: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String?,
)

data class AuditLogItem(
    val logId: Long,
    val timestampEpochMs: Long,
    val severity: String,
    val message: String,
    val detail: String?,
)
