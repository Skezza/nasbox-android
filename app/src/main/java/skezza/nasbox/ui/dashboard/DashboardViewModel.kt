package skezza.nasbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.schedule.PlanRecurrenceCalculator
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.domain.schedule.PlanScheduleFrequency
import skezza.nasbox.domain.schedule.formatPlanScheduleSummary
import skezza.nasbox.domain.sync.ReconcileStaleActiveRunsUseCase
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.domain.sync.StopRunUseCase
import skezza.nasbox.ui.common.LoadState

class DashboardViewModel(
    private val planRepository: PlanRepository,
    private val serverRepository: ServerRepository,
    private val runRepository: RunRepository,
    private val stopRunUseCase: StopRunUseCase,
    private val reconcileStaleActiveRunsUseCase: ReconcileStaleActiveRunsUseCase,
    private val recurrenceCalculator: PlanRecurrenceCalculator = PlanRecurrenceCalculator(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val stopRequestsInFlight = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<DashboardUiState> = combine(
        planRepository.observePlans(),
        serverRepository.observeServers(),
        runRepository.observeLatestRunsByStatuses(CURRENT_RUNS_LIMIT, CURRENT_RUN_STATUSES),
        runRepository.observeLatestRunsByStatuses(RECENT_RUNS_LIMIT, TERMINAL_RUN_STATUSES),
        stopRequestsInFlight,
    ) { plans, servers, currentRuns, recentRuns, stoppingRunIds ->
        val planNamesById = plans.associateBy({ it.planId }, { it.name })
        val now = nowEpochMs()
        DashboardUiState(
            vaultHealth = computeVaultHealth(servers, now),
            currentRuns = currentRuns.map { it.toCurrent(planNamesById) },
            recentRuns = recentRuns.map { it.toRecent(planNamesById) },
            nextScheduledRun = computeNextScheduledRun(plans, now),
            stoppingRunIds = stoppingRunIds,
            loadState = LoadState.Success,
            errorMessage = null,
        )
    }.onStart {
        emit(DashboardUiState(loadState = LoadState.Loading))
    }.catch {
        emit(
            DashboardUiState(
                loadState = LoadState.Error(DASHBOARD_ERROR_MESSAGE),
                errorMessage = DASHBOARD_ERROR_MESSAGE,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DashboardUiState(),
    )

    init {
        viewModelScope.launch {
            reconcileStaleActiveRunsUseCase()
        }
    }

    fun requestStopRun(runId: Long) {
        if (runId in stopRequestsInFlight.value) return
        viewModelScope.launch {
            stopRequestsInFlight.update { it + runId }
            try {
                stopRunUseCase(runId)
            } finally {
                stopRequestsInFlight.update { it - runId }
            }
        }
    }

    private fun computeVaultHealth(
        servers: List<ServerEntity>,
        nowEpochMs: Long,
    ): DashboardVaultHealth {
        if (servers.isEmpty()) {
            return DashboardVaultHealth(
                level = VaultHealthLevel.NOT_CONFIGURED,
                title = "No servers configured",
                detail = "Add a destination server to start backups.",
            )
        }

        val failedCount = servers.count { it.lastTestStatus == STATUS_FAILED }
        val healthyCount = servers.count { server ->
            server.lastTestStatus == STATUS_SUCCESS &&
                server.lastTestTimestampEpochMs != null &&
                nowEpochMs - server.lastTestTimestampEpochMs <= VAULT_TEST_STALE_MS
        }
        val staleOrUntestedCount = servers.size - failedCount - healthyCount

        val summary = "Healthy $healthyCount, failed $failedCount, stale/untested $staleOrUntestedCount"
        return when {
            failedCount > 0 -> DashboardVaultHealth(
                level = VaultHealthLevel.ATTENTION,
                title = "Attention needed",
                detail = summary,
            )

            staleOrUntestedCount > 0 -> DashboardVaultHealth(
                level = VaultHealthLevel.NEEDS_TEST,
                title = "Needs testing",
                detail = summary,
            )

            else -> DashboardVaultHealth(
                level = VaultHealthLevel.HEALTHY,
                title = "Healthy",
                detail = summary,
            )
        }
    }

    private fun RunEntity.toCurrent(planNamesById: Map<Long, String>): DashboardCurrentRun {
        return DashboardCurrentRun(
            runId = runId,
            planName = planNamesById[planId] ?: "Job #$planId",
            status = status,
            triggerSource = triggerSource,
            startedAtEpochMs = startedAtEpochMs,
            scannedCount = scannedCount,
            uploadedCount = uploadedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            summaryError = summaryError,
        )
    }

    private fun RunEntity.toRecent(planNamesById: Map<Long, String>): DashboardRecentRun {
        return DashboardRecentRun(
            runId = runId,
            planName = planNamesById[planId] ?: "Job #$planId",
            status = status,
            triggerSource = triggerSource,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            uploadedCount = uploadedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            summaryError = summaryError,
        )
    }

    private fun computeNextScheduledRun(
        plans: List<PlanEntity>,
        nowEpochMs: Long,
    ): DashboardNextScheduledRun? {
        val upcomingRuns = plans
            .asSequence()
            .filter { it.enabled && it.scheduleEnabled }
            .map { plan ->
                val frequency = PlanScheduleFrequency.fromRaw(plan.scheduleFrequency)
                DashboardNextScheduledRun(
                    planId = plan.planId,
                    planName = plan.name.ifBlank { "Job #${plan.planId}" },
                    nextRunAtEpochMs = recurrenceCalculator.nextRunEpochMs(
                        nowEpochMs = nowEpochMs,
                        frequency = frequency,
                        scheduleTimeMinutes = plan.scheduleTimeMinutes,
                        scheduleDaysMask = plan.scheduleDaysMask,
                        scheduleDayOfMonth = plan.scheduleDayOfMonth,
                        scheduleIntervalHours = plan.scheduleIntervalHours,
                    ),
                    scheduleSummary = formatPlanScheduleSummary(
                        enabled = true,
                        frequency = frequency,
                        scheduleTimeMinutes = plan.scheduleTimeMinutes,
                        scheduleDaysMask = plan.scheduleDaysMask,
                        scheduleDayOfMonth = plan.scheduleDayOfMonth,
                        scheduleIntervalHours = plan.scheduleIntervalHours,
                    ),
                    additionalScheduledPlans = 0,
                )
            }
            .sortedBy { it.nextRunAtEpochMs }
            .toList()
        val first = upcomingRuns.firstOrNull() ?: return null
        return first.copy(additionalScheduledPlans = (upcomingRuns.size - 1).coerceAtLeast(0))
    }

    companion object {
        private const val STATUS_SUCCESS = "SUCCESS"
        private const val STATUS_FAILED = "FAILED"
        private const val VAULT_TEST_STALE_MS = 24L * 60L * 60L * 1000L
        private const val CURRENT_RUNS_LIMIT = 20
        private const val RECENT_RUNS_LIMIT = 5
        private val CURRENT_RUN_STATUSES = setOf(
            RunStatus.RUNNING,
        )
        private val TERMINAL_RUN_STATUSES = setOf(
            RunStatus.SUCCESS,
            RunStatus.PARTIAL,
            RunStatus.FAILED,
            RunStatus.INTERRUPTED,
            RunStatus.CANCELED,
        )
        private const val DASHBOARD_ERROR_MESSAGE = "Unable to load dashboard data. Check your network or SMB configuration."

        fun factory(
            planRepository: PlanRepository,
            serverRepository: ServerRepository,
            runRepository: RunRepository,
            stopRunUseCase: StopRunUseCase,
            reconcileStaleActiveRunsUseCase: ReconcileStaleActiveRunsUseCase,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return DashboardViewModel(
                        planRepository = planRepository,
                        serverRepository = serverRepository,
                        runRepository = runRepository,
                        stopRunUseCase = stopRunUseCase,
                        reconcileStaleActiveRunsUseCase = reconcileStaleActiveRunsUseCase,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
        }
    }
}

enum class VaultHealthLevel {
    HEALTHY,
    NEEDS_TEST,
    ATTENTION,
    NOT_CONFIGURED,
}

data class DashboardUiState(
    val vaultHealth: DashboardVaultHealth = DashboardVaultHealth(
        level = VaultHealthLevel.NOT_CONFIGURED,
        title = "No servers configured",
        detail = "Add a destination server to start backups.",
    ),
    val currentRuns: List<DashboardCurrentRun> = emptyList(),
    val recentRuns: List<DashboardRecentRun> = emptyList(),
    val nextScheduledRun: DashboardNextScheduledRun? = null,
    val stoppingRunIds: Set<Long> = emptySet(),
    val loadState: LoadState = LoadState.Success,
    val errorMessage: String? = null,
)

data class DashboardVaultHealth(
    val level: VaultHealthLevel,
    val title: String,
    val detail: String,
)

data class DashboardCurrentRun(
    val runId: Long,
    val planName: String,
    val status: String,
    val triggerSource: String,
    val startedAtEpochMs: Long,
    val scannedCount: Int,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String?,
)

data class DashboardRecentRun(
    val runId: Long,
    val planName: String,
    val status: String,
    val triggerSource: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String?,
)

data class DashboardNextScheduledRun(
    val planId: Long,
    val planName: String,
    val nextRunAtEpochMs: Long,
    val scheduleSummary: String,
    val additionalScheduledPlans: Int,
)
