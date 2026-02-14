package skezza.smbsync.data.db

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SMBSyncDatabaseTest {
    private lateinit var database: SMBSyncDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SMBSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun serverAndPlanCrud_arePersisted() = runBlocking {
        val serverId = database.serverDao().insert(
            ServerEntity(
                name = "Home NAS",
                host = "192.168.1.10",
                shareName = "photos",
                basePath = "backup",
                domain = "",
                username = "user",
                credentialAlias = "vault/server-1",
            ),
        )

        val planId = database.planDao().insert(
            PlanEntity(
                name = "Camera Uploads",
                sourceAlbum = "Camera",
                serverId = serverId,
                directoryTemplate = "{year}/{month}",
                filenamePattern = "{mediaId}.{ext}",
                enabled = true,
            ),
        )

        val plans = database.planDao().observeAll().first()

        assertEquals(1, plans.size)
        assertEquals(planId, plans.first().planId)
        assertEquals(serverId, plans.first().serverId)
    }

    @Test
    fun backupRecordUniqueIndex_blocksDuplicatePlanMediaPairs() = runBlocking {
        val serverId = database.serverDao().insert(
            ServerEntity(
                name = "Server One",
                host = "host",
                shareName = "share",
                basePath = "root",
                domain = "",
                username = "name",
                credentialAlias = "vault/one",
            ),
        )

        val planId = database.planDao().insert(
            PlanEntity(
                name = "Plan A",
                sourceAlbum = "Camera",
                serverId = serverId,
                directoryTemplate = "{year}",
                filenamePattern = "{mediaId}.{ext}",
                enabled = true,
            ),
        )

        database.backupRecordDao().insert(
            BackupRecordEntity(
                planId = planId,
                mediaItemId = "media-1",
                remotePath = "folder/media-1.jpg",
                uploadedAtEpochMs = 1L,
            ),
        )

        var duplicateRejected = false
        try {
            database.backupRecordDao().insert(
                BackupRecordEntity(
                    planId = planId,
                    mediaItemId = "media-1",
                    remotePath = "folder/media-1.jpg",
                    uploadedAtEpochMs = 2L,
                ),
            )
        } catch (_: Exception) {
            duplicateRejected = true
        }

        assertTrue(duplicateRejected)
    }

    @Test
    fun runAndLogEntities_canBeStoredAndRead() = runBlocking {
        val serverId = database.serverDao().insert(
            ServerEntity(
                name = "Server Two",
                host = "host",
                shareName = "share",
                basePath = "root",
                domain = "",
                username = "name",
                credentialAlias = "vault/two",
            ),
        )
        val planId = database.planDao().insert(
            PlanEntity(
                name = "Plan B",
                sourceAlbum = "Camera",
                serverId = serverId,
                directoryTemplate = "{year}/{month}",
                filenamePattern = "{mediaId}.{ext}",
                enabled = true,
            ),
        )

        val runId = database.runDao().insert(
            RunEntity(
                planId = planId,
                status = "SUCCESS",
                startedAtEpochMs = 100L,
                finishedAtEpochMs = 300L,
                scannedCount = 10,
                uploadedCount = 8,
                skippedCount = 2,
                failedCount = 0,
            ),
        )

        database.runLogDao().insert(
            RunLogEntity(
                runId = runId,
                timestampEpochMs = 150L,
                severity = "INFO",
                message = "Upload started",
            ),
        )

        val latestRuns = database.runDao().getLatest(limit = 1)
        val logs = database.runLogDao().getForRun(runId)

        assertEquals(1, latestRuns.size)
        assertEquals(runId, latestRuns.first().runId)
        assertEquals(1, logs.size)
        assertNotNull(logs.first().message)
    }

    @Test
    fun schemaIncludesBackupRecordUniqueIndex() {
        val query = SimpleSQLiteQuery("PRAGMA index_list('backup_records')")
        val cursor = database.query(query)
        var foundUnique = false
        cursor.use {
            val uniqueColumn = it.getColumnIndex("unique")
            while (it.moveToNext()) {
                if (it.getInt(uniqueColumn) == 1) {
                    foundUnique = true
                }
            }
        }

        assertTrue(foundUnique)
    }
}
