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
import skezza.nasbox.domain.sync.RunContinuationScheduler
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.domain.sync.StopRunUseCase
import skezza.nasbox.ui.common.LoadState
import skezza.nasbox.ui.common.PlanDisplayInfo
import skezza.nasbox.ui.common.buildPlanDisplayInfoMap
import java.util.Locale

class DashboardViewModel(
    private val planRepository: PlanRepository,
    private val serverRepository: ServerRepository,
    private val runRepository: RunRepository,
    private val stopRunUseCase: StopRunUseCase,
    private val runContinuationScheduler: RunContinuationScheduler,
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
        val planInfoById = buildPlanDisplayInfoMap(plans, servers)
        val currentRunSummaries = currentRuns.map { it.toCurrent(planInfoById) }
        val recentRunSummaries = recentRuns.map { it.toRecent(planInfoById) }
        val now = nowEpochMs()
        DashboardUiState(
            vaultHealth = computeVaultHealth(servers, now),
            currentRuns = currentRunSummaries,
            recentRuns = recentRunSummaries,
            runHealth = computeRunHealth(recentRunSummaries),
            nextScheduledRun = computeNextScheduledRun(plans, now),
            stoppingRunIds = stoppingRunIds,
            loadState = LoadState.Success,
            errorMessage = null,
            hasLoadedData = true,
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
        initialValue = DashboardUiState(loadState = LoadState.Loading),
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

    fun restartRun(planId: Long, runId: Long, continuationCursor: String?) {
        viewModelScope.launch {
            runCatching {
                runContinuationScheduler.enqueueContinuation(planId, runId, continuationCursor.orEmpty())
            }
        }
    }

    fun clearRecentRun(runId: Long) {
        viewModelScope.launch {
            runRepository.deleteRun(runId)
        }
    }

    fun clearAllRecentRuns() {
        val runIds = uiState.value.recentRuns.map { it.runId }
        if (runIds.isEmpty()) return
        viewModelScope.launch {
            runRepository.deleteRuns(runIds)
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

    private fun RunEntity.toCurrent(planInfoById: Map<Long, PlanDisplayInfo>): DashboardCurrentRun {
        val planInfo = planInfoById[planId]
        return DashboardCurrentRun(
            runId = runId,
            planName = planInfo?.planName ?: "Job #$planId",
            serverName = planInfo?.serverName,
            status = status,
            triggerSource = triggerSource,
            executionMode = executionMode,
            phase = phase,
            startedAtEpochMs = startedAtEpochMs,
            scannedCount = scannedCount,
            uploadedCount = uploadedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            summaryError = summaryError,
        )
    }

    private fun RunEntity.toRecent(planInfoById: Map<Long, PlanDisplayInfo>): DashboardRecentRun {
        val planInfo = planInfoById[planId]
        return DashboardRecentRun(
            runId = runId,
            planId = planId,
            planName = planInfo?.planName ?: "Job #$planId",
            serverName = planInfo?.serverName,
            status = status,
            triggerSource = triggerSource,
            executionMode = executionMode,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            uploadedCount = uploadedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            summaryError = summaryError,
            continuationCursor = continuationCursor,
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

    private fun computeRunHealth(
        recentRuns: List<DashboardRecentRun>,
    ): DashboardRunHealth {
        if (recentRuns.isEmpty()) {
            return DashboardRunHealth(
                level = RunHealthLevel.HEALTHY,
                title = "No jobs have run",
                detail = "Run a job to start archiving files.",
            )
        }

        var recentFailureCount = 0
        var totalObservedRuns = 0
        var lastSuccessAt: Long? = null
        val streaks = mutableMapOf<Long, FailureStreak>()
        val sealedPlans = mutableSetOf<Long>()

        recentRuns
            .sortedWith(
                compareByDescending<DashboardRecentRun> { it.startedAtEpochMs }
                    .thenByDescending { it.finishedAtEpochMs ?: it.startedAtEpochMs },
            )
            .forEach { run ->
                val planId = run.planId
                if (planId in sealedPlans) {
                    return@forEach
                }
                totalObservedRuns++
                val normalizedStatus = run.status.uppercase(Locale.US)
                if (normalizedStatus in RUN_HEALTH_FAILURE_STATUSES) {
                    recentFailureCount++
                }
                when {
                    normalizedStatus in RUN_HEALTH_ISSUE_STATUSES -> {
                        val existing = streaks[planId]
                        if (existing == null) {
                            streaks[planId] = FailureStreak(latestRun = run, length = 1)
                        } else {
                            existing.length += 1
                        }
                    }
                    normalizedStatus == RunStatus.SUCCESS -> {
                        if (lastSuccessAt == null) {
                            lastSuccessAt = run.finishedAtEpochMs ?: run.startedAtEpochMs
                        }
                        sealedPlans += planId
                        streaks.remove(planId)
                    }
                    normalizedStatus in RUN_HEALTH_FAILURE_STATUSES -> {
                        sealedPlans += planId
                        streaks.remove(planId)
                    }
                    else -> {
                        sealedPlans += planId
                        streaks.remove(planId)
                    }
                }
            }

        val issues = streaks.values
            .sortedWith(
                compareByDescending<FailureStreak> { it.length }
                    .thenByDescending { it.latestRun.startedAtEpochMs },
            )
            .take(MAX_RUN_HEALTH_ISSUES)
            .map { streak ->
                DashboardRunIssue(
                    planId = streak.latestRun.planId,
                    planName = streak.latestRun.planName,
                    runId = streak.latestRun.runId,
                    status = streak.latestRun.status,
                    streakLength = streak.length,
                    lastUpdatedAtEpochMs = streak.latestRun.finishedAtEpochMs
                        ?: streak.latestRun.startedAtEpochMs,
                    summaryError = streak.latestRun.summaryError,
                    continuationCursor = streak.latestRun.continuationCursor,
                )
            }

        val level = when {
            issues.any { it.streakLength >= 2 } -> RunHealthLevel.CRITICAL
            issues.isNotEmpty() || recentFailureCount > 0 -> RunHealthLevel.WARNING
            else -> RunHealthLevel.HEALTHY
        }

        val title = when (level) {
            RunHealthLevel.CRITICAL -> "Critical"
            RunHealthLevel.WARNING -> "Warning"
            RunHealthLevel.HEALTHY -> "Good"
        }

        val detail = when {
            totalObservedRuns == 0 -> "No jobs run yet."
            recentFailureCount == 0 -> "All recent runs succeeded."
            else -> "$recentFailureCount of $totalObservedRuns runs failed."
        }

        return DashboardRunHealth(
            level = level,
            title = title,
            detail = detail,
            recentFailureCount = recentFailureCount,
            totalObservedRuns = totalObservedRuns,
            lastSuccessAtEpochMs = lastSuccessAt,
            issues = issues,
        )
    }

    private data class FailureStreak(
        val latestRun: DashboardRecentRun,
        var length: Int,
    )

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
        private const val MAX_RUN_HEALTH_ISSUES = 3
        private val RUN_HEALTH_ISSUE_STATUSES = setOf(
            RunStatus.FAILED,
            RunStatus.PARTIAL,
            RunStatus.INTERRUPTED,
        )
        private val RUN_HEALTH_FAILURE_STATUSES = setOf(
            RunStatus.FAILED,
            RunStatus.PARTIAL,
            RunStatus.INTERRUPTED,
            RunStatus.CANCELED,
        )

        fun factory(
            planRepository: PlanRepository,
            serverRepository: ServerRepository,
            runRepository: RunRepository,
            stopRunUseCase: StopRunUseCase,
            runContinuationScheduler: RunContinuationScheduler,
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
                        runContinuationScheduler = runContinuationScheduler,
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
    val runHealth: DashboardRunHealth = DashboardRunHealth(),
    val currentRuns: List<DashboardCurrentRun> = emptyList(),
    val recentRuns: List<DashboardRecentRun> = emptyList(),
    val nextScheduledRun: DashboardNextScheduledRun? = null,
    val stoppingRunIds: Set<Long> = emptySet(),
    val loadState: LoadState = LoadState.Success,
    val errorMessage: String? = null,
    val hasLoadedData: Boolean = false,
)

data class DashboardVaultHealth(
    val level: VaultHealthLevel,
    val title: String,
    val detail: String,
)

data class DashboardCurrentRun(
    val runId: Long,
    val planName: String,
    val serverName: String?,
    val status: String,
    val triggerSource: String,
    val executionMode: String,
    val phase: String,
    val startedAtEpochMs: Long,
    val scannedCount: Int,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String?,
)

data class DashboardRecentRun(
    val runId: Long,
    val planId: Long,
    val planName: String,
    val serverName: String?,
    val status: String,
    val triggerSource: String,
    val executionMode: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val uploadedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val summaryError: String?,
    val continuationCursor: String?,
)

data class DashboardNextScheduledRun(
    val planId: Long,
    val planName: String,
    val nextRunAtEpochMs: Long,
    val scheduleSummary: String,
    val additionalScheduledPlans: Int,
)

data class DashboardRunHealth(
    val level: RunHealthLevel = RunHealthLevel.HEALTHY,
    val title: String = "No jobs have run yet",
    val detail: String = "Run a backup to start tracking health.",
    val recentFailureCount: Int = 0,
    val totalObservedRuns: Int = 0,
    val lastSuccessAtEpochMs: Long? = null,
    val issues: List<DashboardRunIssue> = emptyList(),
)

data class DashboardRunIssue(
    val planId: Long,
    val planName: String,
    val runId: Long,
    val status: String,
    val streakLength: Int,
    val lastUpdatedAtEpochMs: Long,
    val summaryError: String?,
    val continuationCursor: String?,
)

enum class RunHealthLevel {
    HEALTHY,
    WARNING,
    CRITICAL,
}
