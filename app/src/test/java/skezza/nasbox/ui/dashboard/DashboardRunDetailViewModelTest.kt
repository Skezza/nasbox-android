package skezza.nasbox.ui.dashboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import skezza.nasbox.MainDispatcherRule
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.BackupRecordEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.db.RunTimelineLogRow
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.repository.BackupRecordRepository
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.domain.sync.RunStatus

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardRunDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun uiState_activeRun_mapsCurrentFileAndFileActivities() = runTest {
        val runId = 5L
        val viewModel = DashboardRunDetailViewModel(
            runId = runId,
            planRepository = FakePlanRepository(
                MutableStateFlow(
                    listOf(
                        PlanEntity(
                            planId = 10,
                            name = "Camera Job",
                            sourceAlbum = "album",
                            serverId = 2,
                            directoryTemplate = "",
                            filenamePattern = "",
                            enabled = true,
                        ),
                    ),
                ),
            ),
            serverRepository = FakeServerRepository(MutableStateFlow(emptyList())),
            runRepository = FakeRunRepository(
                MutableStateFlow(
                    listOf(
                        RunEntity(
                            runId = runId,
                            planId = 10,
                            status = RunStatus.RUNNING,
                            triggerSource = "MANUAL",
                            startedAtEpochMs = 1_000L,
                            scannedCount = 3,
                            uploadedCount = 1,
                        ),
                    ),
                ),
            ),
            runLogRepository = FakeRunLogRepository(
                MutableStateFlow(
                    listOf(
                        RunLogEntity(
                            logId = 22,
                            runId = runId,
                            timestampEpochMs = 2_000L,
                            severity = "INFO",
                            message = "Processing item",
                            detail = "mediaId=2 displayName=IMG_0002.jpg remotePath=backup/IMG_0002.jpg",
                        ),
                        RunLogEntity(
                            logId = 23,
                            runId = runId,
                            timestampEpochMs = 2_100L,
                            severity = "INFO",
                            message = "Uploaded item",
                            detail = "mediaId=2 remotePath=backup/IMG_0002.jpg",
                        ),
                    ),
                ),
            ),
            backupRecordRepository = FakeBackupRecordRepository(),
        )

        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertNotNull(state.run)
        assertEquals("Camera Job", state.run?.planName)
        assertTrue(state.isActive)
        assertEquals("IMG_0002.jpg", state.currentFileLabel)
        assertEquals(1, state.fileActivities.size)
        assertEquals(DashboardRunFileStatus.UPLOADED, state.fileActivities.first().status)
        assertEquals("IMG_0002.jpg", state.fileActivities.first().displayName)
        assertNull(state.fileActivities.first().detail)
        assertEquals(2, state.rawLogs.size)
        assertTrue(state.milestones.any { it.label == "Started" })
    }

    @Test
    fun uiState_fileActivity_prefersFilenameAndReasonCode() = runTest {
        val runId = 9L
        val mediaId = "content://media/external/images/media/42"
        val viewModel = DashboardRunDetailViewModel(
            runId = runId,
            planRepository = FakePlanRepository(MutableStateFlow(emptyList())),
            serverRepository = FakeServerRepository(MutableStateFlow(emptyList())),
            runRepository = FakeRunRepository(
                MutableStateFlow(
                    listOf(
                        RunEntity(
                            runId = runId,
                            planId = 99,
                            status = RunStatus.SUCCESS,
                            triggerSource = "MANUAL",
                            startedAtEpochMs = 1_000L,
                            finishedAtEpochMs = 5_000L,
                        ),
                    ),
                ),
            ),
            runLogRepository = FakeRunLogRepository(
                MutableStateFlow(
                    listOf(
                        RunLogEntity(
                            logId = 40,
                            runId = runId,
                            timestampEpochMs = 2_000L,
                            severity = "INFO",
                            message = "Processing item",
                            detail = "mediaId=$mediaId displayName=/storage/emulated/0/DCIM/Camera/IMG_0042.jpg remotePath=backup/2026/02/15/device/DCIM/Camera/IMG_0042.jpg",
                        ),
                        RunLogEntity(
                            logId = 41,
                            runId = runId,
                            timestampEpochMs = 2_100L,
                            severity = "INFO",
                            message = "Skipped item",
                            detail = "mediaId=$mediaId reason=already_backed_up",
                        ),
                        RunLogEntity(
                            logId = 42,
                            runId = runId,
                            timestampEpochMs = 2_200L,
                            severity = "ERROR",
                            message = "Upload failed for item $mediaId.",
                            detail = "java.io.IOException: socket timeout",
                        ),
                    ),
                ),
            ),
            backupRecordRepository = FakeBackupRecordRepository(),
        )

        advanceUntilIdle()
        val activity = viewModel.uiState.value.fileActivities.first()

        assertEquals(DashboardRunFileStatus.FAILED, activity.status)
        assertEquals("IMG_0042.jpg", activity.displayName)
        assertEquals("Upload failed", activity.detail)
    }

    @Test
    fun uiState_fileActivity_forFolderSource_showsFilenameOnlyForSafMediaId() = runTest {
        val runId = 11L
        val mediaId = "content://com.android.externalstorage.documents/document/primary%3APictures%2FScreenshots%2FIMG_9911.jpg"
        val viewModel = DashboardRunDetailViewModel(
            runId = runId,
            planRepository = FakePlanRepository(MutableStateFlow(emptyList())),
            serverRepository = FakeServerRepository(MutableStateFlow(emptyList())),
            runRepository = FakeRunRepository(
                MutableStateFlow(
                    listOf(
                        RunEntity(
                            runId = runId,
                            planId = 77,
                            status = RunStatus.SUCCESS,
                            triggerSource = "MANUAL",
                            startedAtEpochMs = 1_000L,
                            finishedAtEpochMs = 2_000L,
                        ),
                    ),
                ),
            ),
            runLogRepository = FakeRunLogRepository(
                MutableStateFlow(
                    listOf(
                        RunLogEntity(
                            logId = 50,
                            runId = runId,
                            timestampEpochMs = 1_500L,
                            severity = "INFO",
                            message = "Skipped item",
                            detail = "mediaId=$mediaId reason=already_backed_up",
                        ),
                    ),
                ),
            ),
            backupRecordRepository = FakeBackupRecordRepository(),
        )

        advanceUntilIdle()
        val activity = viewModel.uiState.value.fileActivities.first()

        assertEquals("IMG_9911.jpg", activity.displayName)
        assertEquals("Already backed up", activity.detail)
    }

    @Test
    fun uiState_groupsNoisyProgressIntoMilestonesAndLastAction() = runTest {
        val runId = 7L
        val viewModel = DashboardRunDetailViewModel(
            runId = runId,
            planRepository = FakePlanRepository(
                MutableStateFlow(
                    listOf(
                        PlanEntity(
                            planId = 10,
                            name = "Camera Job",
                            sourceAlbum = "album",
                            serverId = 2,
                            directoryTemplate = "",
                            filenamePattern = "",
                            enabled = true,
                        ),
                    ),
                ),
            ),
            serverRepository = FakeServerRepository(MutableStateFlow(emptyList())),
            runRepository = FakeRunRepository(
                MutableStateFlow(
                    listOf(
                        RunEntity(
                            runId = runId,
                            planId = 10,
                            status = RunStatus.SUCCESS,
                            triggerSource = "MANUAL",
                            startedAtEpochMs = 1_000L,
                            finishedAtEpochMs = 4_000L,
                            scannedCount = 4,
                            uploadedCount = 4,
                        ),
                    ),
                ),
            ),
            runLogRepository = FakeRunLogRepository(
                MutableStateFlow(
                    listOf(
                        RunLogEntity(
                            logId = 33,
                            runId = runId,
                            timestampEpochMs = 3_900L,
                            severity = "INFO",
                            message = "Run finished",
                            detail = "status=SUCCESS uploaded=4 skipped=0 failed=0",
                        ),
                        RunLogEntity(
                            logId = 32,
                            runId = runId,
                            timestampEpochMs = 3_000L,
                            severity = "INFO",
                            message = "Run progress",
                            detail = "processed=3/4 uploaded=3 skipped=0 failed=0",
                        ),
                        RunLogEntity(
                            logId = 31,
                            runId = runId,
                            timestampEpochMs = 2_000L,
                            severity = "INFO",
                            message = "Scan complete",
                            detail = "source=ALBUM discovered=4 warnings=0",
                        ),
                    ),
                ),
            ),
            backupRecordRepository = FakeBackupRecordRepository(),
        )

        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertTrue(state.milestones.any { it.label == "Scan complete" })
        assertTrue(state.milestones.any { it.label.startsWith("75% processed") })
        assertEquals("Finished (Success)", state.lastAction?.label)
    }

    private class FakePlanRepository(
        private val plans: MutableStateFlow<List<PlanEntity>>,
    ) : PlanRepository {
        override fun observePlans(): Flow<List<PlanEntity>> = plans
        override suspend fun getPlan(planId: Long): PlanEntity? = plans.value.firstOrNull { it.planId == planId }
        override suspend fun createPlan(plan: PlanEntity): Long = plan.planId
        override suspend fun updatePlan(plan: PlanEntity) = Unit
        override suspend fun deletePlan(planId: Long) = Unit
    }

    private class FakeRunRepository(
        private val runs: MutableStateFlow<List<RunEntity>>,
    ) : RunRepository {
        override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> = flowOf(runs.value.filter { it.planId == planId })
        override fun observeLatestRun(): Flow<RunEntity?> = flowOf(runs.value.maxByOrNull { it.startedAtEpochMs })
        override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = flowOf(runs.value.take(limit))
        override fun observeLatestRunsByStatuses(limit: Int, statuses: Set<String>): Flow<List<RunEntity>> = flowOf(emptyList())
        override fun observeRunsByStatus(status: String): Flow<List<RunEntity>> = flowOf(runs.value.filter { it.status == status })
        override fun observeRun(runId: Long): Flow<RunEntity?> = flowOf(runs.value.firstOrNull { it.runId == runId })
        override suspend fun createRun(run: RunEntity): Long = run.runId
        override suspend fun updateRun(run: RunEntity) = Unit
        override suspend fun getRun(runId: Long): RunEntity? = runs.value.firstOrNull { it.runId == runId }
        override suspend fun runsByStatus(status: String): List<RunEntity> = runs.value.filter { it.status == status }
        override suspend fun latestRuns(limit: Int): List<RunEntity> = runs.value.take(limit)
        override suspend fun latestRunsByStatuses(limit: Int, statuses: Set<String>): List<RunEntity> = emptyList()
    }

    private class FakeRunLogRepository(
        private val logs: MutableStateFlow<List<RunLogEntity>>,
    ) : RunLogRepository {
        override suspend fun createLog(log: RunLogEntity): Long = 0
        override suspend fun logsForRun(runId: Long): List<RunLogEntity> = logs.value.filter { it.runId == runId }
        override fun observeLogsForRun(runId: Long): Flow<List<RunLogEntity>> =
            flowOf(logs.value.filter { it.runId == runId }.sortedBy { it.timestampEpochMs })

        override fun observeLogsForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>> =
            flowOf(logs.value.filter { it.runId == runId }.sortedByDescending { it.timestampEpochMs }.take(limit))

        override fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>> = flowOf(emptyList())
    }

    private class FakeBackupRecordRepository : BackupRecordRepository {
        override suspend fun create(record: BackupRecordEntity): Long = record.recordId

        override suspend fun findByPlanAndMediaItem(planId: Long, mediaItemId: String): BackupRecordEntity? = null

        override suspend fun findByPlanAndMediaItems(planId: Long, mediaItemIds: List<String>): List<BackupRecordEntity> =
            emptyList()
    }

    private class FakeServerRepository(
        private val servers: MutableStateFlow<List<ServerEntity>>,
    ) : ServerRepository {
        override fun observeServers(): Flow<List<ServerEntity>> = servers
        override suspend fun getServer(serverId: Long): ServerEntity? =
            servers.value.firstOrNull { it.serverId == serverId }

        override suspend fun createServer(server: ServerEntity): Long = server.serverId
        override suspend fun updateServer(server: ServerEntity) = Unit
        override suspend fun deleteServer(serverId: Long) = Unit
    }
}
