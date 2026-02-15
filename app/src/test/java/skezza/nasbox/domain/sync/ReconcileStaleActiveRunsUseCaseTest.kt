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

class ReconcileStaleActiveRunsUseCaseTest {

    @Test
    fun invoke_staleCancelRequested_finalizesAsCanceled() = runBlocking {
        val now = 10_000L
        val runRepository = FakeRunRepository(
            runs = mutableListOf(
                RunEntity(
                    runId = 7,
                    planId = 1,
                    status = RunStatus.CANCEL_REQUESTED,
                    startedAtEpochMs = 100,
                    heartbeatAtEpochMs = 1_000,
                    uploadedCount = 2,
                ),
            ),
        )
        val runLogRepository = FakeRunLogRepository()
        val useCase = ReconcileStaleActiveRunsUseCase(
            runRepository = runRepository,
            runLogRepository = runLogRepository,
            nowEpochMs = { now },
            staleCancelRequestedAfterMs = 1_000L,
            staleRunningAfterMs = 1_000L,
        )

        val result = useCase()

        assertEquals(1, result.canceledCount)
        assertEquals(0, result.interruptedCount)
        val updated = runRepository.getRun(7)
        assertEquals(RunStatus.CANCELED, updated?.status)
        assertEquals(now, updated?.finishedAtEpochMs)
        assertTrue(runLogRepository.logs.any { it.message == "Run finalized as canceled" })
    }

    @Test
    fun invoke_staleRunning_marksInterrupted() = runBlocking {
        val now = 10_000L
        val runRepository = FakeRunRepository(
            runs = mutableListOf(
                RunEntity(
                    runId = 8,
                    planId = 1,
                    status = RunStatus.RUNNING,
                    startedAtEpochMs = 100,
                    heartbeatAtEpochMs = 1_000,
                    uploadedCount = 2,
                ),
            ),
        )
        val runLogRepository = FakeRunLogRepository()
        val useCase = ReconcileStaleActiveRunsUseCase(
            runRepository = runRepository,
            runLogRepository = runLogRepository,
            nowEpochMs = { now },
            staleCancelRequestedAfterMs = 1_000L,
            staleRunningAfterMs = 1_000L,
        )

        val result = useCase()

        assertEquals(0, result.canceledCount)
        assertEquals(1, result.interruptedCount)
        val updated = runRepository.getRun(8)
        assertEquals(RunStatus.INTERRUPTED, updated?.status)
        assertEquals(now, updated?.finishedAtEpochMs)
        assertTrue(runLogRepository.logs.any { it.message == "Run marked as interrupted" })
    }

    private class FakeRunRepository(
        private val runs: MutableList<RunEntity>,
    ) : RunRepository {
        override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> = flowOf(runs.filter { it.planId == planId })
        override fun observeLatestRun(): Flow<RunEntity?> = flowOf(runs.maxByOrNull { it.startedAtEpochMs })
        override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = flowOf(runs.take(limit))
        override fun observeLatestRunsByStatuses(limit: Int, statuses: Set<String>): Flow<List<RunEntity>> {
            val normalized = statuses.map { it.trim().uppercase() }.toSet()
            return flowOf(
                runs
                    .filter { it.status.trim().uppercase() in normalized }
                    .sortedByDescending { it.startedAtEpochMs }
                    .take(limit),
            )
        }

        override fun observeRunsByStatus(status: String): Flow<List<RunEntity>> = flowOf(runs.filter { it.status == status })
        override fun observeRun(runId: Long): Flow<RunEntity?> = flowOf(runs.firstOrNull { it.runId == runId })
        override suspend fun createRun(run: RunEntity): Long = run.runId

        override suspend fun updateRun(run: RunEntity) {
            val index = runs.indexOfFirst { it.runId == run.runId }
            if (index >= 0) {
                runs[index] = run
            } else {
                runs += run
            }
        }

        override suspend fun getRun(runId: Long): RunEntity? = runs.firstOrNull { it.runId == runId }
        override suspend fun runsByStatus(status: String): List<RunEntity> = runs.filter { it.status == status }
        override suspend fun latestRuns(limit: Int): List<RunEntity> = runs.sortedByDescending { it.startedAtEpochMs }.take(limit)

        override suspend fun latestRunsByStatuses(limit: Int, statuses: Set<String>): List<RunEntity> {
            val normalized = statuses.map { it.trim().uppercase() }.toSet()
            return runs
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

        override fun observeLogsForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>> =
            flowOf(logs.filter { it.runId == runId }.sortedByDescending { it.timestampEpochMs }.take(limit))

        override fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>> = flowOf(emptyList())
    }
}
