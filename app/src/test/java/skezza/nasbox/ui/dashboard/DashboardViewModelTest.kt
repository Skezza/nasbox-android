package skezza.nasbox.ui.dashboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import skezza.nasbox.MainDispatcherRule
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun uiState_withoutServers_showsNotConfiguredVaultHealth() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(VaultHealthLevel.NOT_CONFIGURED, state.vaultHealth.level)
        assertTrue(state.recentRuns.isEmpty())
    }

    @Test
    fun uiState_withHealthyAndFailedServers_showsAttention() = runTest {
        val servers = MutableStateFlow(
            listOf(
                server(
                    serverId = 21,
                    status = "SUCCESS",
                    testEpochMs = 950_000L,
                ),
                server(
                    serverId = 22,
                    status = "FAILED",
                    testEpochMs = 950_000L,
                ),
            ),
        )

        val viewModel = buildViewModel(
            servers = servers,
            nowEpochMs = { 1_000_000L },
        )
        advanceUntilIdle()

        assertEquals(VaultHealthLevel.ATTENTION, viewModel.uiState.value.vaultHealth.level)
    }

    @Test
    fun uiState_mapsRecentRuns_withPlanNames() = runTest {
        val plans = MutableStateFlow(
            listOf(
                PlanEntity(
                    planId = 11,
                    name = "Camera Job",
                    sourceAlbum = "album",
                    serverId = 21,
                    directoryTemplate = "",
                    filenamePattern = "",
                    enabled = true,
                ),
            ),
        )
        val recentRuns = MutableStateFlow(
            listOf(
                RunEntity(
                    runId = 88,
                    planId = 11,
                    status = "SUCCESS",
                    triggerSource = "SCHEDULED",
                    startedAtEpochMs = 10_000L,
                    finishedAtEpochMs = 15_000L,
                    uploadedCount = 9,
                    skippedCount = 1,
                    failedCount = 0,
                ),
            ),
        )

        val viewModel = buildViewModel(
            plans = plans,
            servers = MutableStateFlow(listOf(server(21))),
            recentRuns = recentRuns,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.recentRuns.size)
        assertEquals("Camera Job", state.recentRuns.first().planName)
        assertEquals(9, state.recentRuns.first().uploadedCount)
    }

    private fun buildViewModel(
        plans: MutableStateFlow<List<PlanEntity>> = MutableStateFlow(emptyList()),
        servers: MutableStateFlow<List<ServerEntity>> = MutableStateFlow(emptyList()),
        recentRuns: MutableStateFlow<List<RunEntity>> = MutableStateFlow(emptyList()),
        nowEpochMs: () -> Long = { 1_000_000L },
    ): DashboardViewModel {
        return DashboardViewModel(
            planRepository = FakePlanRepository(plans),
            serverRepository = FakeServerRepository(servers),
            runRepository = FakeRunRepository(recentRuns),
            nowEpochMs = nowEpochMs,
        )
    }

    private fun server(
        serverId: Long,
        status: String? = null,
        testEpochMs: Long? = null,
    ): ServerEntity = ServerEntity(
        serverId = serverId,
        name = "NAS",
        host = "nas",
        shareName = "share",
        basePath = "backup",
        domain = "",
        username = "joe",
        credentialAlias = "alias",
        lastTestStatus = status,
        lastTestTimestampEpochMs = testEpochMs,
    )

    private class FakePlanRepository(
        private val plans: MutableStateFlow<List<PlanEntity>>,
    ) : PlanRepository {
        override fun observePlans(): Flow<List<PlanEntity>> = plans
        override suspend fun getPlan(planId: Long): PlanEntity? = plans.value.firstOrNull { it.planId == planId }
        override suspend fun createPlan(plan: PlanEntity): Long = plan.planId
        override suspend fun updatePlan(plan: PlanEntity) = Unit
        override suspend fun deletePlan(planId: Long) = Unit
    }

    private class FakeServerRepository(
        private val servers: MutableStateFlow<List<ServerEntity>>,
    ) : ServerRepository {
        override fun observeServers(): Flow<List<ServerEntity>> = servers
        override suspend fun getServer(serverId: Long): ServerEntity? = servers.value.firstOrNull { it.serverId == serverId }
        override suspend fun createServer(server: ServerEntity): Long = server.serverId
        override suspend fun updateServer(server: ServerEntity) = Unit
        override suspend fun deleteServer(serverId: Long) = Unit
    }

    private class FakeRunRepository(
        private val recentRuns: MutableStateFlow<List<RunEntity>>,
    ) : RunRepository {
        override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> = MutableStateFlow(emptyList())
        override fun observeLatestRun(): Flow<RunEntity?> = MutableStateFlow(recentRuns.value.firstOrNull())
        override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = recentRuns
        override fun observeRunsByStatus(status: String): Flow<List<RunEntity>> = MutableStateFlow(emptyList())
        override fun observeRun(runId: Long): Flow<RunEntity?> = MutableStateFlow(recentRuns.value.firstOrNull { it.runId == runId })
        override suspend fun createRun(run: RunEntity): Long = 0
        override suspend fun updateRun(run: RunEntity) = Unit
        override suspend fun getRun(runId: Long): RunEntity? = recentRuns.value.firstOrNull { it.runId == runId }
        override suspend fun runsByStatus(status: String): List<RunEntity> = recentRuns.value.filter { it.status == status }
        override suspend fun latestRuns(limit: Int): List<RunEntity> = recentRuns.value.take(limit)
    }
}
