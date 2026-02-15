package skezza.nasbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository

class DashboardViewModel(
    private val planRepository: PlanRepository,
    private val serverRepository: ServerRepository,
    private val runRepository: RunRepository,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        planRepository.observePlans(),
        serverRepository.observeServers(),
        runRepository.observeLatestRuns(RECENT_RUNS_LIMIT),
    ) { plans, servers, recentRuns ->
        val planNamesById = plans.associateBy({ it.planId }, { it.name })
        DashboardUiState(
            vaultHealth = computeVaultHealth(servers, nowEpochMs()),
            recentRuns = recentRuns.map { it.toRecent(planNamesById) },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DashboardUiState(),
    )

    private fun computeVaultHealth(
        servers: List<ServerEntity>,
        nowEpochMs: Long,
    ): DashboardVaultHealth {
        if (servers.isEmpty()) {
            return DashboardVaultHealth(
                level = VaultHealthLevel.NOT_CONFIGURED,
                title = "No servers configured",
                detail = "Add a destination server in Vault to start backups.",
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

    companion object {
        private const val STATUS_SUCCESS = "SUCCESS"
        private const val STATUS_FAILED = "FAILED"
        private const val VAULT_TEST_STALE_MS = 24L * 60L * 60L * 1000L
        private const val RECENT_RUNS_LIMIT = 5

        fun factory(
            planRepository: PlanRepository,
            serverRepository: ServerRepository,
            runRepository: RunRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return DashboardViewModel(
                        planRepository = planRepository,
                        serverRepository = serverRepository,
                        runRepository = runRepository,
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
        detail = "Add a destination server in Vault to start backups.",
    ),
    val recentRuns: List<DashboardRecentRun> = emptyList(),
)

data class DashboardVaultHealth(
    val level: VaultHealthLevel,
    val title: String,
    val detail: String,
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
