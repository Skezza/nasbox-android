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
import skezza.nasbox.data.db.ServerEntity
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
        val server = ServerEntity(
            serverId = 20,
            name = "NAS",
            host = "host",
            shareName = "share",
            basePath = "photos",
            domain = "",
            username = "user",
            credentialAlias = "alias",
        )
        val media = listOf(
            MediaImageItem("1", "album-1", "one.jpg", "image/jpeg", 1_700_000_000_000, 4),
            MediaImageItem("2", "album-1", "two.jpg", "image/jpeg", 1_700_000_000_100, 4),
        )

        val planRepo = FakePlanRepository(plan)
        val serverRepo = FakeServerRepository(server)
        val backupRepo = FakeBackupRecordRepository(existingMediaItemId = "1")
        val runRepo = FakeRunRepository()
        val logRepo = FakeRunLogRepository()
        val mediaDataSource = FakeMediaStoreDataSource(media)
        val smbClient = FakeSmbClient()

        val useCase = RunPlanBackupUseCase(
            planRepository = planRepo,
            serverRepository = serverRepo,
            backupRecordRepository = backupRepo,
            runRepository = runRepo,
            runLogRepository = logRepo,
            credentialStore = FakeCredentialStore("pw"),
            mediaStoreDataSource = mediaDataSource,
            smbClient = smbClient,
            nowEpochMs = { 1000L },
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
        assertTrue(logRepo.logs.any { it.message == "Run finished" })
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
        val server = ServerEntity(
            serverId = 20,
            name = "NAS",
            host = "host",
            shareName = "share",
            basePath = "photos",
            domain = "",
            username = "user",
            credentialAlias = "alias",
        )

        val useCase = RunPlanBackupUseCase(
            planRepository = FakePlanRepository(plan),
            serverRepository = FakeServerRepository(server),
            backupRecordRepository = FakeBackupRecordRepository(existingMediaItemId = "none"),
            runRepository = FakeRunRepository(),
            runLogRepository = FakeRunLogRepository(),
            credentialStore = FakeCredentialStore("pw"),
            mediaStoreDataSource = FakeMediaStoreDataSource(emptyList(), throwOnScan = true),
            smbClient = FakeSmbClient(),
            nowEpochMs = { 1000L },
        )

        val result = useCase(plan.planId)

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
            serverRepository = FakeServerRepository(
                ServerEntity(
                    serverId = 1,
                    name = "s",
                    host = "h",
                    shareName = "sh",
                    basePath = "b",
                    domain = "",
                    username = "u",
                    credentialAlias = "a",
                ),
            ),
            backupRecordRepository = FakeBackupRecordRepository(existingMediaItemId = "none"),
            runRepository = FakeRunRepository(),
            runLogRepository = FakeRunLogRepository(),
            credentialStore = FakeCredentialStore("pw"),
            mediaStoreDataSource = FakeMediaStoreDataSource(emptyList()),
            smbClient = FakeSmbClient(),
            nowEpochMs = { 1000L },
        )

        val result = useCase(100)

        assertEquals("FAILED", result.status)
    }


    @Test
    fun invoke_returnsExplicitSummaryForUnsupportedSourceMode() = runBlocking {
        val plan = PlanEntity(
            planId = 10,
            name = "Folder",
            sourceAlbum = "",
            sourceType = "FOLDER",
            folderPath = "/storage/emulated/0/DCIM",
            serverId = 20,
            directoryTemplate = "",
            filenamePattern = "",
            enabled = true,
        )
        val server = ServerEntity(
            serverId = 20,
            name = "NAS",
            host = "host",
            shareName = "share",
            basePath = "photos",
            domain = "",
            username = "user",
            credentialAlias = "alias",
        )

        val result = RunPlanBackupUseCase(
            planRepository = FakePlanRepository(plan),
            serverRepository = FakeServerRepository(server),
            backupRecordRepository = FakeBackupRecordRepository(existingMediaItemId = "none"),
            runRepository = FakeRunRepository(),
            runLogRepository = FakeRunLogRepository(),
            credentialStore = FakeCredentialStore("pw"),
            mediaStoreDataSource = FakeMediaStoreDataSource(emptyList()),
            smbClient = FakeSmbClient(),
            nowEpochMs = { 1000L },
        )(plan.planId)

        assertEquals("FAILED", result.status)
        assertEquals("Only album-based plans are supported in Phase 5.", result.summaryError)
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
    fun sanitizeSegment_replacesIllegalCharacters() {
        val sanitized = PathRenderer.sanitizeSegment("cam<era>:name?.jpg")

        assertEquals("cam_era__name_.jpg", sanitized)
    }

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
        override suspend fun createRun(run: RunEntity): Long = 5
        override suspend fun updateRun(run: RunEntity) {
            updatedRuns += run
        }
        override suspend fun latestRuns(limit: Int): List<RunEntity> = emptyList()
    }

    private class FakeRunLogRepository : RunLogRepository {
        val logs = mutableListOf<RunLogEntity>()
        override suspend fun createLog(log: RunLogEntity): Long {
            logs += log
            return logs.size.toLong()
        }
        override suspend fun logsForRun(runId: Long): List<RunLogEntity> = logs
    }

    private class FakeCredentialStore(private val password: String?) : CredentialStore {
        override suspend fun savePassword(alias: String, password: String) = Unit
        override suspend fun loadPassword(alias: String): String? = password
        override suspend fun deletePassword(alias: String) = Unit
    }

    private class FakeMediaStoreDataSource(
        private val items: List<MediaImageItem>,
        private val throwOnScan: Boolean = false,
    ) : MediaStoreDataSource {
        override suspend fun listAlbums(): List<MediaAlbum> = listOf(MediaAlbum("album-1", "Camera", items.size, null))
        override suspend fun listImagesForAlbum(bucketId: String): List<MediaImageItem> {
            if (throwOnScan) error("permission denied")
            return items
        }
        override suspend fun openImageStream(mediaId: String): InputStream? = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun imageContentUri(mediaId: String) = android.net.Uri.EMPTY
    }

    private class FakeSmbClient : SmbClient {
        val uploadedPaths = mutableListOf<String>()

        override suspend fun testConnection(request: SmbConnectionRequest): SmbConnectionResult = SmbConnectionResult(1)

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
