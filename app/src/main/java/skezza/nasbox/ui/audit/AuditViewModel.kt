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
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.domain.sync.RunStatus

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModel(
    private val planRepository: PlanRepository,
    private val runRepository: RunRepository,
    private val runLogRepository: RunLogRepository,
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(AuditFilter.ALL)
    private val selectedRunId = MutableStateFlow<Long?>(null)

    val listUiState: StateFlow<AuditListUiState> = combine(
        planRepository.observePlans(),
        runRepository.observeLatestRuns(RUN_LIST_LIMIT),
        runLogRepository.observeLatestTimeline(PHASE_TRANSITIONS_SCAN_LIMIT),
        selectedFilter,
    ) { plans, runs, timelineRows, filter ->
        val planNamesById = plans.associateBy(PlanEntity::planId, PlanEntity::name)
        val mapped = runs.map { run ->
            run.toListItem(planNamesById[run.planId] ?: "Plan #${run.planId}")
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
            phaseTransitions = timelineRows
                .asSequence()
                .filter { row ->
                    row.message == MESSAGE_RUN_STARTED ||
                        row.message == MESSAGE_RUN_FINISHED ||
                        row.message == MESSAGE_CHUNK_PAUSED ||
                        row.message == MESSAGE_INTERRUPTED ||
                        row.message == MESSAGE_FINALIZED_CANCELED ||
                        row.message == MESSAGE_FOREGROUND_BLOCKED ||
                        row.message.startsWith(MESSAGE_RESUMED_PREFIX)
                }
                .take(PHASE_TRANSITIONS_LIMIT)
                .map { row ->
                    AuditPhaseTransition(
                        runId = row.runId,
                        planName = planNamesById[row.planId] ?: "Plan #${row.planId}",
                        timestampEpochMs = row.timestampEpochMs,
                        message = row.message,
                        detail = row.detail,
                    )
                }
                .toList(),
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
                runRepository.observeRun(runId),
                runLogRepository.observeLogsForRunNewest(runId, RUN_LOG_LIMIT),
            ) { plans, run, logs ->
                val planName = run?.let { current ->
                    plans.firstOrNull { it.planId == current.planId }?.name
                } ?: run?.let { "Plan #${it.planId}" }

                AuditDetailUiState(
                    run = run?.toDetailSummary(planName.orEmpty()),
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

    private fun RunEntity.toListItem(planName: String): AuditRunListItem = AuditRunListItem(
        runId = runId,
        planName = planName,
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

    private fun RunEntity.toDetailSummary(planName: String): AuditRunSummary = AuditRunSummary(
        runId = runId,
        planName = planName,
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
        private const val PHASE_TRANSITIONS_SCAN_LIMIT = 120
        private const val PHASE_TRANSITIONS_LIMIT = 20
        private const val MESSAGE_RUN_STARTED = "Run started"
        private const val MESSAGE_RUN_FINISHED = "Run finished"
        private const val MESSAGE_CHUNK_PAUSED = "Chunk paused for system window"
        private const val MESSAGE_INTERRUPTED = "Run marked as interrupted"
        private const val MESSAGE_FINALIZED_CANCELED = "Run finalized as canceled"
        private const val MESSAGE_FOREGROUND_BLOCKED = "Foreground start blocked"
        private const val MESSAGE_RESUMED_PREFIX = "Resumed attempt #"

        fun factory(
            planRepository: PlanRepository,
            runRepository: RunRepository,
            runLogRepository: RunLogRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(AuditViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return AuditViewModel(planRepository, runRepository, runLogRepository) as T
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
    val phaseTransitions: List<AuditPhaseTransition> = emptyList(),
)

data class AuditRunListItem(
    val runId: Long,
    val planName: String,
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

data class AuditPhaseTransition(
    val runId: Long,
    val planName: String,
    val timestampEpochMs: Long,
    val message: String,
    val detail: String?,
)
