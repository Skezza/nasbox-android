package skezza.nasbox.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.db.RunTimelineLogRow
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository

class MarkRunInterruptedUseCaseTest {

    @Test
    fun invoke_runningRunMarksInterruptedAndAddsLog() = runBlocking {
        val runRepo = FakeRunRepository(
            run = RunEntity(
                runId = 7,
                planId = 1,
                status = RunStatus.RUNNING,
                startedAtEpochMs = 100,
                heartbeatAtEpochMs = 200,
            ),
        )
        val logRepo = FakeRunLogRepository()
        val useCase = MarkRunInterruptedUseCase(
            runRepository = runRepo,
            runLogRepository = logRepo,
            nowEpochMs = { 999L },
        )

        val changed = useCase(7)

        assertTrue(changed)
        assertEquals(RunStatus.INTERRUPTED, runRepo.run?.status)
        assertEquals(999L, runRepo.run?.finishedAtEpochMs)
        assertEquals(1, logRepo.logs.size)
        assertEquals("Run marked as interrupted", logRepo.logs.first().message)
    }

    @Test
    fun invoke_nonRunningRunReturnsFalse() = runBlocking {
        val runRepo = FakeRunRepository(
            run = RunEntity(
                runId = 8,
                planId = 1,
                status = RunStatus.SUCCESS,
                startedAtEpochMs = 100,
                finishedAtEpochMs = 200,
            ),
        )
        val useCase = MarkRunInterruptedUseCase(
            runRepository = runRepo,
            runLogRepository = FakeRunLogRepository(),
            nowEpochMs = { 999L },
        )

        val changed = useCase(8)

        assertFalse(changed)
        assertEquals(RunStatus.SUCCESS, runRepo.run?.status)
    }

    private class FakeRunRepository(
        var run: RunEntity?,
    ) : RunRepository {
        override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> = flowOf(emptyList())
        override fun observeLatestRun(): Flow<RunEntity?> = flowOf(run)
        override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = flowOf(listOfNotNull(run))
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
