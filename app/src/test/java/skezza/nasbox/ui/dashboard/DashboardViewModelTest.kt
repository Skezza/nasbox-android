package skezza.nasbox.ui.dashboard

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import skezza.nasbox.MainDispatcherRule
import skezza.nasbox.data.schedule.PlanRecurrenceCalculator
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.db.RunTimelineLogRow
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.domain.sync.ReconcileStaleActiveRunsUseCase
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.domain.sync.StopRunUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun uiState_withoutServers_showsNotConfiguredVaultHealth() = runTest {
        val harness = buildHarness()
        advanceUntilIdle()

        val state = harness.viewModel.uiState.value
        assertEquals(VaultHealthLevel.NOT_CONFIGURED, state.vaultHealth.level)
        assertTrue(state.currentRuns.isEmpty())
        assertTrue(state.recentRuns.isEmpty())
    }

    @Test
    fun uiState_withoutScheduledPlans_hasNoNextScheduledRun() = runTest {
        val harness = buildHarness()
        advanceUntilIdle()

        assertNull(harness.viewModel.uiState.value.nextScheduledRun)
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

        val harness = buildHarness(
            servers = servers,
            nowEpochMs = { 1_000_000L },
        )
        advanceUntilIdle()

        assertEquals(VaultHealthLevel.ATTENTION, harness.viewModel.uiState.value.vaultHealth.level)
    }

    @Test
    fun uiState_splitsCurrentAndRecentRuns_withPlanNames() = runTest {
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
        val runs = MutableStateFlow(
            listOf(
                RunEntity(
                    runId = 88,
                    planId = 11,
                    status = RunStatus.RUNNING,
                    triggerSource = "SCHEDULED",
                    startedAtEpochMs = 10_000L,
                    scannedCount = 4,
                    uploadedCount = 1,
                ),
                RunEntity(
                    runId = 89,
                    planId = 11,
                    status = RunStatus.SUCCESS,
                    triggerSource = "SCHEDULED",
                    startedAtEpochMs = 20_000L,
                    finishedAtEpochMs = 25_000L,
                    uploadedCount = 9,
                    skippedCount = 1,
                    failedCount = 0,
                ),
            ),
        )

        val harness = buildHarness(
            plans = plans,
            servers = MutableStateFlow(listOf(server(21))),
            runs = runs,
        )
        advanceUntilIdle()

        val state = harness.viewModel.uiState.value
        assertEquals(1, state.currentRuns.size)
        assertEquals(RunStatus.RUNNING, state.currentRuns.first().status)
        assertEquals("Camera Job", state.currentRuns.first().planName)
        assertEquals(1, state.recentRuns.size)
        assertEquals(RunStatus.SUCCESS, state.recentRuns.first().status)
    }

    @Test
    fun requestStopRun_updatesRunToCanceled() = runTest {
        val runs = MutableStateFlow(
            listOf(
                RunEntity(
                    runId = 42,
                    planId = 11,
                    status = RunStatus.RUNNING,
                    triggerSource = "MANUAL",
                    startedAtEpochMs = 1_000L,
                ),
            ),
        )
        val harness = buildHarness(
            servers = MutableStateFlow(listOf(server(21))),
            runs = runs,
        )
        advanceUntilIdle()

        harness.viewModel.requestStopRun(42)
        advanceUntilIdle()

        val updated = harness.runRepository.getRun(42)
        assertEquals(RunStatus.CANCELED, updated?.status)
        assertTrue(harness.logRepository.logs.any { it.message == "Run canceled by user" })
    }

    @Test
    fun uiState_withoutCurrentRuns_exposesNearestScheduledRun() = runTest {
        val plans = MutableStateFlow(
            listOf(
                PlanEntity(
                    planId = 11,
                    name = "Early Job",
                    sourceAlbum = "album",
                    serverId = 21,
                    directoryTemplate = "",
                    filenamePattern = "",
                    enabled = true,
                    scheduleEnabled = true,
                    scheduleTimeMinutes = 2 * 60,
                    scheduleFrequency = "DAILY",
                ),
                PlanEntity(
                    planId = 12,
                    name = "Later Job",
                    sourceAlbum = "album",
                    serverId = 21,
                    directoryTemplate = "",
                    filenamePattern = "",
                    enabled = true,
                    scheduleEnabled = true,
                    scheduleTimeMinutes = 5 * 60,
                    scheduleFrequency = "DAILY",
                ),
            ),
        )
        val now = epochUtc(2026, Calendar.JANUARY, 1, 1, 0)
        val harness = buildHarness(
            plans = plans,
            servers = MutableStateFlow(listOf(server(21))),
            recurrenceCalculator = PlanRecurrenceCalculator(TimeZone.getTimeZone("UTC")),
            nowEpochMs = { now },
        )
        advanceUntilIdle()

        val nextRun = harness.viewModel.uiState.value.nextScheduledRun
        assertEquals("Early Job", nextRun?.planName)
        assertEquals(epochUtc(2026, Calendar.JANUARY, 1, 2, 0), nextRun?.nextRunAtEpochMs)
        assertEquals("Daily around 02:00", nextRun?.scheduleSummary)
        assertEquals(1, nextRun?.additionalScheduledPlans)
    }

    private fun buildHarness(
        plans: MutableStateFlow<List<PlanEntity>> = MutableStateFlow(emptyList()),
        servers: MutableStateFlow<List<ServerEntity>> = MutableStateFlow(emptyList()),
        runs: MutableStateFlow<List<RunEntity>> = MutableStateFlow(emptyList()),
        recurrenceCalculator: PlanRecurrenceCalculator = PlanRecurrenceCalculator(TimeZone.getTimeZone("UTC")),
        nowEpochMs: () -> Long = { 1_000_000L },
    ): Harness {
        val runRepository = FakeRunRepository(runs)
        val runLogRepository = FakeRunLogRepository()
        val viewModel = DashboardViewModel(
            planRepository = FakePlanRepository(plans),
            serverRepository = FakeServerRepository(servers),
            runRepository = runRepository,
            stopRunUseCase = StopRunUseCase(
                runRepository = runRepository,
                runLogRepository = runLogRepository,
                nowEpochMs = nowEpochMs,
            ),
            reconcileStaleActiveRunsUseCase = ReconcileStaleActiveRunsUseCase(
                runRepository = runRepository,
                runLogRepository = runLogRepository,
                nowEpochMs = nowEpochMs,
                staleCancelRequestedAfterMs = 60L * 60L * 1000L,
                staleRunningAfterMs = 60L * 60L * 1000L,
            ),
            recurrenceCalculator = recurrenceCalculator,
            nowEpochMs = nowEpochMs,
        )
        return Harness(
            viewModel = viewModel,
            runRepository = runRepository,
            logRepository = runLogRepository,
        )
    }

    private fun epochUtc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
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

    private data class Harness(
        val viewModel: DashboardViewModel,
        val runRepository: FakeRunRepository,
        val logRepository: FakeRunLogRepository,
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
        private val runs: MutableStateFlow<List<RunEntity>>,
    ) : RunRepository {
        override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> =
            MutableStateFlow(runs.value.filter { it.planId == planId })

        override fun observeLatestRun(): Flow<RunEntity?> = MutableStateFlow(runs.value.maxByOrNull { it.startedAtEpochMs })

        override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = runs

        override fun observeLatestRunsByStatuses(limit: Int, statuses: Set<String>): Flow<List<RunEntity>> {
            val normalized = statuses.map { it.trim().uppercase() }.toSet()
            return MutableStateFlow(
                runs.value
                    .filter { it.status.trim().uppercase() in normalized }
                    .sortedByDescending { it.startedAtEpochMs }
                    .take(limit),
            )
        }

        override fun observeRunsByStatus(status: String): Flow<List<RunEntity>> =
            MutableStateFlow(runs.value.filter { it.status == status })

        override fun observeRun(runId: Long): Flow<RunEntity?> =
            MutableStateFlow(runs.value.firstOrNull { it.runId == runId })

        override suspend fun createRun(run: RunEntity): Long = run.runId

        override suspend fun updateRun(run: RunEntity) {
            val existing = runs.value.toMutableList()
            val index = existing.indexOfFirst { it.runId == run.runId }
            if (index >= 0) {
                existing[index] = run
            } else {
                existing += run
            }
            runs.value = existing
        }

        override suspend fun getRun(runId: Long): RunEntity? = runs.value.firstOrNull { it.runId == runId }

        override suspend fun runsByStatus(status: String): List<RunEntity> = runs.value.filter { it.status == status }

        override suspend fun latestRuns(limit: Int): List<RunEntity> =
            runs.value.sortedByDescending { it.startedAtEpochMs }.take(limit)

        override suspend fun latestRunsByStatuses(limit: Int, statuses: Set<String>): List<RunEntity> {
            val normalized = statuses.map { it.trim().uppercase() }.toSet()
            return runs.value
                .filter { it.status.trim().uppercase() in normalized }
                .sortedByDescending { it.startedAtEpochMs }
                .take(limit)
        }
    }

    private class FakeRunLogRepository : RunLogRepository {
        val logs = mutableListOf<RunLogEntity>()

        override suspend fun createLog(log: RunLogEntity): Long {
            logs += log
            return logs.size.toLong()
        }

        override suspend fun logsForRun(runId: Long): List<RunLogEntity> = logs.filter { it.runId == runId }

        override fun observeLogsForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>> = flowOf(
            logs.filter { it.runId == runId }.sortedByDescending { it.timestampEpochMs }.take(limit),
        )

        override fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>> = flowOf(emptyList())
    }
}
