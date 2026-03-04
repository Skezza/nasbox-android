package skezza.nasbox.domain.archive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import skezza.nasbox.data.db.BackupRecordEntity
import skezza.nasbox.data.db.NasBoxDatabase
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.ServerEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupSetTransferUseCasesTest {
    private lateinit var database: NasBoxDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NasBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun buildExportDocument_includesReferencedServersAndRecordsOnly() = runBlocking {
        val includedServerId = database.serverDao().insert(
            ServerEntity(
                name = "Home NAS",
                host = "nas.local",
                shareName = "photos",
                basePath = "backup",
                domain = "",
                username = "joe",
                credentialAlias = "vault/one",
            ),
        )
        database.serverDao().insert(
            ServerEntity(
                name = "Unused",
                host = "unused.local",
                shareName = "drop",
                basePath = "root",
                domain = "",
                username = "other",
                credentialAlias = "vault/two",
            ),
        )
        val planId = database.planDao().insert(
            PlanEntity(
                name = "Photos",
                sourceAlbum = "camera",
                sourceType = "ALBUM",
                serverId = includedServerId,
                directoryTemplate = "{year}/{month}",
                filenamePattern = "{mediaId}.{ext}",
                enabled = true,
                checksumVerificationEnabled = true,
            ),
        )
        database.backupRecordDao().insert(
            BackupRecordEntity(
                planId = planId,
                mediaItemId = "media-1",
                remotePath = "backup/2026/03/media-1.jpg",
                uploadedAtEpochMs = 10L,
                verifiedSizeBytes = 42L,
                checksumAlgorithm = "MD5",
                checksumValue = "abcd",
                checksumVerifiedAtEpochMs = 11L,
            ),
        )

        val document = buildExportDocument(
            servers = database.serverDao().getAll(),
            plans = database.planDao().getAll(),
            recordsForPlan = { plan -> database.backupRecordDao().getForPlan(plan) },
            exportedAtEpochMs = 99L,
        )
        val json = document.toJson().toString()

        assertEquals(1, document.servers.size)
        assertEquals("Home NAS", document.servers.first().name)
        assertEquals(1, document.backupSets.size)
        assertEquals(1, document.backupSets.first().records.size)
        assertTrue(!json.contains("credentialAlias"))
        assertTrue(!json.contains("vault/one"))
    }

    @Test
    fun importExportDocument_reusesMatchingServerAndCreatesDisabledImportedPlans() = runBlocking {
        val existingServerId = database.serverDao().insert(
            ServerEntity(
                name = "Home NAS",
                host = "nas.local",
                shareName = "photos",
                basePath = "backup",
                domain = "",
                username = "joe",
                credentialAlias = "vault/existing",
            ),
        )

        val result = importExportDocument(
            database = database,
            document = ExportDocument(
                formatVersion = 1,
                exportedAtEpochMs = 10L,
                app = "NASBox",
                servers = listOf(
                    ExportServerDto(
                        exportServerId = 1L,
                        name = "Home NAS",
                        host = "nas.local",
                        shareName = "photos",
                        basePath = "backup",
                        domain = "",
                        username = "joe",
                    ),
                ),
                backupSets = listOf(
                    ExportBackupSetDto(
                        plan = ExportPlanDto(
                            exportPlanId = 10L,
                            name = "Photos",
                            sourceType = "ALBUM",
                            sourceAlbumIdsCsv = "camera",
                            folderPath = "",
                            sourceIncludeVideos = true,
                            useAlbumTemplating = false,
                            serverExportId = 1L,
                            remoteFolderTemplate = "{year}/{month}",
                            remoteFileNameTemplate = "{mediaId}.{ext}",
                            scheduleIntervalHours = 24,
                            progressNotificationEnabled = true,
                            checksumVerificationEnabled = true,
                        ),
                        records = listOf(
                            ExportRecordDto(
                                mediaItemId = "media-1",
                                remotePath = "backup/2026/03/media-1.jpg",
                                uploadedAtEpochMs = 100L,
                                verifiedSizeBytes = 42L,
                                checksumAlgorithm = "MD5",
                                checksumValue = "abcd",
                                checksumVerifiedAtEpochMs = 101L,
                            ),
                        ),
                    ),
                ),
            ),
            nowEpochMs = { 500L },
        )

        val plans = database.planDao().getAll()
        val records = database.backupRecordDao().getForPlan(plans.first().planId)

        assertEquals(0, result.createdServerCount)
        assertEquals(1, result.reusedServerCount)
        assertEquals(1, result.createdPlanCount)
        assertEquals(1, result.importedRecordCount)
        assertEquals(1, plans.size)
        assertEquals(existingServerId, plans.first().serverId)
        assertTrue(!plans.first().enabled)
        assertTrue(!plans.first().scheduleEnabled)
        assertEquals(500L, plans.first().importedAtEpochMs)
        assertEquals(1, records.size)
        assertEquals("abcd", records.first().checksumValue)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseExportDocument_rejectsUnsupportedFormatVersion() {
        parseExportDocument(
            """
            {
              "formatVersion": 2,
              "servers": [],
              "backupSets": []
            }
            """.trimIndent(),
        )
    }
}
