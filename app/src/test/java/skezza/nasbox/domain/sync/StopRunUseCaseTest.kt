package skezza.nasbox.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.db.RunTimelineLogRow
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository

class StopRunUseCaseTest {

    @Test
    fun invoke_runningRun_setsCanceledAndLogs() = runBlocking {
        val runRepository = FakeRunRepository(
            run = RunEntity(
                runId = 7,
                planId = 1,
                status = RunStatus.RUNNING,
                startedAtEpochMs = 100,
                heartbeatAtEpochMs = 100,
            ),
        )
        val runLogRepository = FakeRunLogRepository()
        val useCase = StopRunUseCase(
            runRepository = runRepository,
            runLogRepository = runLogRepository,
            nowEpochMs = { 999L },
        )

        val result = useCase(7)

        assertEquals(StopRunResult.Requested, result)
        assertEquals(RunStatus.CANCELED, runRepository.run?.status)
        assertTrue(runLogRepository.logs.any { it.message == "Run canceled by user" })
    }

    @Test
    fun invoke_alreadyRequestedRun_returnsAlreadyRequested() = runBlocking {
        val runRepository = FakeRunRepository(
            run = RunEntity(
                runId = 7,
                planId = 1,
                status = RunStatus.CANCEL_REQUESTED,
                startedAtEpochMs = 100,
                heartbeatAtEpochMs = 100,
            ),
        )
        val useCase = StopRunUseCase(
            runRepository = runRepository,
            runLogRepository = FakeRunLogRepository(),
            nowEpochMs = { 999L },
        )

        val result = useCase(7)

        assertEquals(StopRunResult.AlreadyRequested, result)
    }

    @Test
    fun invoke_terminalRun_returnsNotActive() = runBlocking {
        val runRepository = FakeRunRepository(
            run = RunEntity(
                runId = 7,
                planId = 1,
                status = RunStatus.SUCCESS,
                startedAtEpochMs = 100,
                finishedAtEpochMs = 200,
            ),
        )
        val useCase = StopRunUseCase(
            runRepository = runRepository,
            runLogRepository = FakeRunLogRepository(),
            nowEpochMs = { 999L },
        )

        val result = useCase(7)

        assertEquals(StopRunResult.NotActive, result)
        assertEquals(RunStatus.SUCCESS, runRepository.run?.status)
    }

    @Test
    fun invoke_missingRun_returnsNotFound() = runBlocking {
        val useCase = StopRunUseCase(
            runRepository = FakeRunRepository(run = null),
            runLogRepository = FakeRunLogRepository(),
            nowEpochMs = { 999L },
        )

        val result = useCase(7)

        assertEquals(StopRunResult.NotFound, result)
    }

    private class FakeRunRepository(
        var run: RunEntity?,
    ) : RunRepository {
        override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> = flowOf(emptyList())
        override fun observeLatestRun(): Flow<RunEntity?> = flowOf(run)
        override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = flowOf(listOfNotNull(run))

        override fun observeLatestRunsByStatuses(limit: Int, statuses: Set<String>): Flow<List<RunEntity>> {
            val normalized = statuses.map { it.trim().uppercase() }.toSet()
            return flowOf(listOfNotNull(run).filter { it.status.trim().uppercase() in normalized }.take(limit))
        }

        override fun observeRunsByStatus(status: String): Flow<List<RunEntity>> =
            flowOf(listOfNotNull(run).filter { it.status == status })

        override fun observeRun(runId: Long): Flow<RunEntity?> = flowOf(run?.takeIf { it.runId == runId })
        override suspend fun createRun(run: RunEntity): Long = run.runId
        override suspend fun updateRun(run: RunEntity) {
            this.run = run
        }

        override suspend fun getRun(runId: Long): RunEntity? = run?.takeIf { it.runId == runId }
        override suspend fun runsByStatus(status: String): List<RunEntity> = listOfNotNull(run).filter { it.status == status }
        override suspend fun latestRuns(limit: Int): List<RunEntity> = listOfNotNull(run).take(limit)

        override suspend fun latestRunsByStatuses(limit: Int, statuses: Set<String>): List<RunEntity> {
            val normalized = statuses.map { it.trim().uppercase() }.toSet()
            return listOfNotNull(run).filter { it.status.trim().uppercase() in normalized }.take(limit)
        }
    }

    private class FakeRunLogRepository : RunLogRepository {
        val logs = mutableListOf<RunLogEntity>()

        override suspend fun createLog(log: RunLogEntity): Long {
            logs += log
            return logs.size.toLong()
        }

        override suspend fun logsForRun(runId: Long): List<RunLogEntity> = logs.filter { it.runId == runId }

        override fun observeLogsForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>> =
            flowOf(logs.filter { it.runId == runId }.sortedByDescending { it.timestampEpochMs }.take(limit))

        override fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>> = flowOf(emptyList())
    }
}
