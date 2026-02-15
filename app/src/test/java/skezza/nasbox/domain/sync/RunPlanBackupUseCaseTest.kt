package skezza.nasbox.domain.sync

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.nasbox.data.db.BackupRecordEntity
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.db.RunTimelineLogRow
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.media.FullDeviceScanResult
import skezza.nasbox.data.media.MediaAlbum
import skezza.nasbox.data.media.MediaImageItem
import skezza.nasbox.data.media.MediaStoreDataSource
import skezza.nasbox.data.repository.BackupRecordRepository
import skezza.nasbox.data.repository.PlanRepository
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.data.smb.SmbClient
import skezza.nasbox.data.smb.SmbConnectionRequest
import skezza.nasbox.data.smb.SmbConnectionResult

class RunPlanBackupUseCaseTest {

    @Test
    fun invoke_uploadsNewItemsAndSkipsExistingProofs() = runBlocking {
        val plan = PlanEntity(
            planId = 10,
            name = "Camera",
            sourceAlbum = "album-1",
            serverId = 20,
            directoryTemplate = "{year}/{month}",
            filenamePattern = "{mediaId}.{ext}",
            enabled = true,
        )
        val server = baseServer()
        val media = listOf(
            MediaImageItem("1", "album-1", "one.jpg", "image/jpeg", 1_700_000_000_000, 4),
            MediaImageItem("2", "album-1", "two.jpg", "image/jpeg", 1_700_000_000_100, 4),
        )

        val backupRepo = FakeBackupRecordRepository(existingMediaItemId = "1")
        val runRepo = FakeRunRepository()
        val logRepo = FakeRunLogRepository()
        val mediaDataSource = FakeMediaStoreDataSource(albumItems = media)
        val smbClient = FakeSmbClient()

        val useCase = runUseCase(
            plan = plan,
            server = server,
            backupRepo = backupRepo,
            runRepo = runRepo,
            logRepo = logRepo,
            mediaDataSource = mediaDataSource,
            smbClient = smbClient,
        )

        val result = useCase(plan.planId)

        assertEquals("SUCCESS", result.status)
        assertEquals(1, result.uploadedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.failedCount)
        assertEquals(1, smbClient.uploadedPaths.size)
        assertEquals("photos/Camera/two.jpg", smbClient.uploadedPaths.first())
        assertTrue(backupRepo.createdRecords.any { it.mediaItemId == "2" })
        assertEquals("SUCCESS", runRepo.updatedRuns.last().status)
        assertTrue(runRepo.updatedRuns.any { it.status == "RUNNING" })
        assertTrue(logRepo.logs.any { it.message == "Run finished" })
        assertTrue(logRepo.logs.any { it.message == "Run progress" })
    }

    @Test
    fun invoke_persistsRunningCountersBeforeFinalStatus() = runBlocking {
        val plan = PlanEntity(
            planId = 10,
            name = "Camera",
            sourceAlbum = "album-1",
            serverId = 20,
            directoryTemplate = "",
            filenamePattern = "",
            enabled = true,
        )
        val media = listOf(
            MediaImageItem("1", "album-1", "one.jpg", "image/jpeg", 1_700_000_000_000, 4),
            MediaImageItem("2", "album-1", "two.jpg", "image/jpeg", 1_700_000_000_100, 4),
        )

        val runRepo = FakeRunRepository()
        runUseCase(
            plan = plan,
            server = baseServer(),
            backupRepo = FakeBackupRecordRepository(existingMediaItemId = "1"),
            runRepo = runRepo,
            logRepo = FakeRunLogRepository(),
            mediaDataSource = FakeMediaStoreDataSource(albumItems = media),
            smbClient = FakeSmbClient(),
        )(plan.planId)

        val runningSnapshots = runRepo.updatedRuns.filter { it.status == "RUNNING" }
        assertTrue(runningSnapshots.isNotEmpty())
        assertTrue(runningSnapshots.any { it.scannedCount == media.size })
        assertTrue(runningSnapshots.any { it.skippedCount > 0 || it.uploadedCount > 0 || it.failedCount > 0 })
        assertEquals("SUCCESS", runRepo.updatedRuns.last().status)
    }

    @Test
    fun invoke_executesFolderPlanAndPreservesRelativePaths() = runBlocking {
        val plan = PlanEntity(
            planId = 10,
            name = "Folder",
            sourceAlbum = "",
            sourceType = "FOLDER",
            folderPath = "content://tree/folder",
            serverId = 20,
            directoryTemplate = "",
            filenamePattern = "",
            enabled = true,
        )
        val folderItems = listOf(
            MediaImageItem(
                mediaId = "content://tree/folder/item-1",
                bucketId = "Folder",
                displayName = "old.jpg",
                mimeType = "image/jpeg",
                dateTakenEpochMs = 1_700_000_000_000,
                sizeBytes = 4,
                relativePath = "Trips",
            ),
            MediaImageItem(
                mediaId = "content://tree/folder/item-2",
                bucketId = "Folder",
                displayName = "new.jpg",
                mimeType = "image/jpeg",
                dateTakenEpochMs = 1_700_000_000_100,
                sizeBytes = 4,
                relativePath = "Trips/2025",
            ),
        )

        val backupRepo = FakeBackupRecordRepository(existingMediaItemId = "content://tree/folder/item-1")
        val smbClient = FakeSmbClient()

        val result = runUseCase(
            plan = plan,
            server = baseServer(),
            backupRepo = backupRepo,
            runRepo = FakeRunRepository(),
            logRepo = FakeRunLogRepository(),
            mediaDataSource = FakeMediaStoreDataSource(
                albumItems = emptyList(),
                folderItems = folderItems,
            ),
            smbClient = smbClient,
        )(plan.planId)

        assertEquals("SUCCESS", result.status)
        assertEquals(1, result.uploadedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.failedCount)
        assertEquals("photos/Trips/2025/new.jpg", smbClient.uploadedPaths.single())
        assertTrue(backupRepo.createdRecords.any { it.mediaItemId == "content://tree/folder/item-2" })
    }

    @Test
    fun invoke_marksFullDeviceRunPartialWhenSharedRootsFail() = runBlocking {
        val plan = PlanEntity(
            planId = 10,
            name = "Phone",
            sourceAlbum = "",
            sourceType = "FULL_DEVICE",
            folderPath = "FULL_DEVICE_SHARED_STORAGE",
            serverId = 20,
            directoryTemplate = "",
            filenamePattern = "",
            enabled = true,
        )
        val scan = FullDeviceScanResult(
            items = listOf(
                MediaImageItem(
                    mediaId = "file:///storage/emulated/0/DCIM/Camera/pic.jpg",
                    bucketId = "DCIM",
                    displayName = "pic.jpg",
                    mimeType = "image/jpeg",
                    dateTakenEpochMs = 1_700_000_000_000,
                    sizeBytes = 4,
                    relativePath = "DCIM/Camera",
                ),
            ),
            inaccessibleRoots = listOf("Documents (permission denied)"),
        )

        val runRepo = FakeRunRepository()
        val result = runUseCase(
            plan = plan,
            server = baseServer(),
            backupRepo = FakeBackupRecordRepository(existingMediaItemId = "none"),
            runRepo = runRepo,
            logRepo = FakeRunLogRepository(),
            mediaDataSource = FakeMediaStoreDataSource(
                albumItems = emptyList(),
                fullDeviceScanResult = scan,
            ),
            smbClient = FakeSmbClient(),
        )(plan.planId)

        assertEquals("PARTIAL", result.status)
        assertEquals(1, result.uploadedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, result.failedCount)
        assertEquals("Skipped inaccessible shared-storage location: Documents (permission denied)", result.summaryError)
        assertEquals("PARTIAL", runRepo.updatedRuns.last().status)
    }

    @Test
    fun invoke_returnsFailedWhenMediaScanThrows() = runBlocking {
        val plan = PlanEntity(
            planId = 10,
            name = "Camera",
            sourceAlbum = "album-1",
            serverId = 20,
            directoryTemplate = "",
            filenamePattern = "",
            enabled = true,
        )

        val result = runUseCase(
            plan = plan,
            server = baseServer(),
            backupRepo = FakeBackupRecordRepository(existingMediaItemId = "none"),
            runRepo = FakeRunRepository(),
            logRepo = FakeRunLogRepository(),
            mediaDataSource = FakeMediaStoreDataSource(
                albumItems = emptyList(),
                throwOnAlbumScan = true,
            ),
            smbClient = FakeSmbClient(),
        )(plan.planId)

        assertEquals("FAILED", result.status)
        assertEquals(0, result.uploadedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun invoke_logsAbortReasonWhenPlanMissing() = runBlocking {
        val useCase = RunPlanBackupUseCase(
            planRepository = object : PlanRepository {
                override fun observePlans(): Flow<List<PlanEntity>> = flowOf(emptyList())
                override suspend fun getPlan(planId: Long): PlanEntity? = null
                override suspend fun createPlan(plan: PlanEntity): Long = 0
                override suspend fun updatePlan(plan: PlanEntity) = Unit
                override suspend fun deletePlan(planId: Long) = Unit
            },
            serverRepository = FakeServerRepository(baseServer(serverId = 1)),
            backupRecordRepository = FakeBackupRecordRepository(existingMediaItemId = "none"),
            runRepository = FakeRunRepository(),
            runLogRepository = FakeRunLogRepository(),
            credentialStore = FakeCredentialStore("pw"),
            mediaStoreDataSource = FakeMediaStoreDataSource(albumItems = emptyList()),
            smbClient = FakeSmbClient(),
            nowEpochMs = { 1000L },
        )

        val result = useCase(100)

        assertEquals("FAILED", result.status)
    }

    @Test
    fun invoke_returnsFailedForUnsupportedSourceType() = runBlocking {
        val plan = PlanEntity(
            planId = 10,
            name = "Unknown",
            sourceAlbum = "",
            sourceType = "CUSTOM",
            folderPath = "",
            serverId = 20,
            directoryTemplate = "",
            filenamePattern = "",
            enabled = true,
        )

        val result = runUseCase(
            plan = plan,
            server = baseServer(),
            backupRepo = FakeBackupRecordRepository(existingMediaItemId = "none"),
            runRepo = FakeRunRepository(),
            logRepo = FakeRunLogRepository(),
            mediaDataSource = FakeMediaStoreDataSource(albumItems = emptyList()),
            smbClient = FakeSmbClient(),
        )(plan.planId)

        assertEquals("FAILED", result.status)
        assertEquals("Unsupported source mode: CUSTOM.", result.summaryError)
    }

    @Test
    fun render_appliesTemplateWhenAlbumTemplatingEnabled() {
        val rendered = PathRenderer.render(
            basePath = "photos",
            directoryTemplate = "{year}/{month}/{album}",
            filenamePattern = "{timestamp}_{mediaId}.{ext}",
            mediaItem = MediaImageItem(
                mediaId = "77",
                bucketId = "bucket",
                displayName = "IMG_0077.JPG",
                mimeType = "image/jpeg",
                dateTakenEpochMs = 1_700_000_000_000,
                sizeBytes = 1024,
            ),
            fallbackAlbumToken = "Camera",
            useAlbumTemplating = true,
        )

        assertTrue(rendered.startsWith("photos/"))
        assertTrue(rendered.contains("/Camera/"))
        assertTrue(rendered.endsWith("_77.jpg"))
    }

    @Test
    fun renderPreservingSourcePath_sanitizesRelativeSegments() {
        val rendered = PathRenderer.renderPreservingSourcePath(
            basePath = "archive",
            mediaItem = MediaImageItem(
                mediaId = "file:///storage/emulated/0/DCIM/cam<era>/clip.mp4",
                bucketId = "DCIM",
                displayName = "clip?.mp4",
                mimeType = "video/mp4",
                dateTakenEpochMs = null,
                sizeBytes = 1024,
                relativePath = "DCIM/cam<era>",
            ),
        )

        assertEquals("archive/DCIM/cam_era_/clip_.mp4", rendered)
    }

    @Test
    fun sanitizeSegment_replacesIllegalCharacters() {
        val sanitized = PathRenderer.sanitizeSegment("cam<era>:name?.jpg")
        assertEquals("cam_era__name_.jpg", sanitized)
    }

    private fun runUseCase(
        plan: PlanEntity,
        server: ServerEntity,
        backupRepo: FakeBackupRecordRepository,
        runRepo: FakeRunRepository,
        logRepo: FakeRunLogRepository,
        mediaDataSource: FakeMediaStoreDataSource,
        smbClient: FakeSmbClient,
    ) = RunPlanBackupUseCase(
        planRepository = FakePlanRepository(plan),
        serverRepository = FakeServerRepository(server),
        backupRecordRepository = backupRepo,
        runRepository = runRepo,
        runLogRepository = logRepo,
        credentialStore = FakeCredentialStore("pw"),
        mediaStoreDataSource = mediaDataSource,
        smbClient = smbClient,
        nowEpochMs = { 1000L },
    )

    private fun baseServer(serverId: Long = 20) = ServerEntity(
        serverId = serverId,
        name = "NAS",
        host = "host",
        shareName = "share",
        basePath = "photos",
        domain = "",
        username = "user",
        credentialAlias = "alias",
    )

    private class FakePlanRepository(
        private val plan: PlanEntity,
    ) : PlanRepository {
        override fun observePlans(): Flow<List<PlanEntity>> = flowOf(listOf(plan))
        override suspend fun getPlan(planId: Long): PlanEntity? = plan.takeIf { it.planId == planId }
        override suspend fun createPlan(plan: PlanEntity): Long = plan.planId
        override suspend fun updatePlan(plan: PlanEntity) = Unit
        override suspend fun deletePlan(planId: Long) = Unit
    }

    private class FakeServerRepository(
        private val server: ServerEntity,
    ) : ServerRepository {
        override fun observeServers(): Flow<List<ServerEntity>> = flowOf(listOf(server))
        override suspend fun getServer(serverId: Long): ServerEntity? = server.takeIf { it.serverId == serverId }
        override suspend fun createServer(server: ServerEntity): Long = server.serverId
        override suspend fun updateServer(server: ServerEntity) = Unit
        override suspend fun deleteServer(serverId: Long) = Unit
    }

    private class FakeBackupRecordRepository(
        private val existingMediaItemId: String,
    ) : BackupRecordRepository {
        val createdRecords = mutableListOf<BackupRecordEntity>()

        override suspend fun create(record: BackupRecordEntity): Long {
            createdRecords += record
            return createdRecords.size.toLong()
        }

        override suspend fun findByPlanAndMediaItem(planId: Long, mediaItemId: String): BackupRecordEntity? {
            return if (mediaItemId == existingMediaItemId) {
                BackupRecordEntity(
                    recordId = 1,
                    planId = planId,
                    mediaItemId = mediaItemId,
                    remotePath = "existing",
                    uploadedAtEpochMs = 1,
                )
            } else {
                null
            }
        }
    }

    private class FakeRunRepository : RunRepository {
        val updatedRuns = mutableListOf<RunEntity>()

        override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> = flowOf(emptyList())

        override fun observeLatestRun(): Flow<RunEntity?> = flowOf(updatedRuns.lastOrNull())

        override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = flowOf(updatedRuns.takeLast(limit))

        override fun observeRunsByStatus(status: String): Flow<List<RunEntity>> =
            flowOf(updatedRuns.filter { it.status == status })

        override fun observeRun(runId: Long): Flow<RunEntity?> = flowOf(updatedRuns.lastOrNull { it.runId == runId })

        override suspend fun createRun(run: RunEntity): Long = 5

        override suspend fun updateRun(run: RunEntity) {
            updatedRuns += run
        }

        override suspend fun getRun(runId: Long): RunEntity? = updatedRuns.lastOrNull { it.runId == runId }

        override suspend fun runsByStatus(status: String): List<RunEntity> = updatedRuns.filter { it.status == status }

        override suspend fun latestRuns(limit: Int): List<RunEntity> = emptyList()
    }

    private class FakeRunLogRepository : RunLogRepository {
        val logs = mutableListOf<RunLogEntity>()

        override suspend fun createLog(log: RunLogEntity): Long {
            logs += log
            return logs.size.toLong()
        }

        override suspend fun logsForRun(runId: Long): List<RunLogEntity> = logs

        override fun observeLogsForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>> =
            flowOf(logs.filter { it.runId == runId }.sortedByDescending { it.timestampEpochMs }.take(limit))

        override fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>> = flowOf(emptyList())
    }

    private class FakeCredentialStore(private val password: String?) : CredentialStore {
        override suspend fun savePassword(alias: String, password: String) = Unit
        override suspend fun loadPassword(alias: String): String? = password
        override suspend fun deletePassword(alias: String) = Unit
    }

    private class FakeMediaStoreDataSource(
        private val albumItems: List<MediaImageItem>,
        private val folderItems: List<MediaImageItem> = emptyList(),
        private val fullDeviceScanResult: FullDeviceScanResult = FullDeviceScanResult(emptyList()),
        private val throwOnAlbumScan: Boolean = false,
        private val throwOnFolderScan: Boolean = false,
        private val throwOnFullDeviceScan: Boolean = false,
        private val unreadableItemIds: Set<String> = emptySet(),
    ) : MediaStoreDataSource {
        override suspend fun listAlbums(): List<MediaAlbum> =
            listOf(MediaAlbum("album-1", "Camera", albumItems.size, null))

        override suspend fun listImagesForAlbum(bucketId: String): List<MediaImageItem> {
            if (throwOnAlbumScan) error("permission denied")
            return albumItems
        }

        override suspend fun listFilesForFolder(folderPathOrUri: String): List<MediaImageItem> {
            if (throwOnFolderScan) error("folder denied")
            return folderItems
        }

        override suspend fun scanFullDeviceSharedStorage(): FullDeviceScanResult {
            if (throwOnFullDeviceScan) error("full-device denied")
            return fullDeviceScanResult
        }

        override suspend fun openImageStream(mediaId: String): InputStream? =
            openMediaStream(mediaId)

        override suspend fun openMediaStream(mediaId: String): InputStream? {
            return if (mediaId in unreadableItemIds) null else ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        override fun imageContentUri(mediaId: String) = android.net.Uri.EMPTY
    }

    private class FakeSmbClient : SmbClient {
        val uploadedPaths = mutableListOf<String>()

        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult =
            SmbConnectionResult(1)

        override suspend fun uploadFile(
            request: SmbConnectionRequest,
            remotePath: String,
            contentLengthBytes: Long?,
            inputStream: InputStream,
            onProgressBytes: (Long) -> Unit,
        ) {
            uploadedPaths += remotePath
        }

        override suspend fun listShares(host: String, username: String, password: String): List<String> = emptyList()

        override suspend fun listDirectories(
            host: String,
            shareName: String,
            path: String,
            username: String,
            password: String,
        ): List<String> = emptyList()
    }
}
