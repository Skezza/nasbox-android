package skezza.nasbox.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.nasbox.data.db.BackupRecordDao
import skezza.nasbox.data.db.BackupRecordEntity

class DefaultBackupRecordRepositoryTest {

    @Test
    fun findByPlanAndMediaItems_chunksLargeRequests() = runTest {
        val dao = FakeBackupRecordDao()
        val repository = DefaultBackupRecordRepository(dao)
        val mediaIds = (1..1001).map { "media-$it" }

        val records = repository.findByPlanAndMediaItems(planId = 42L, mediaItemIds = mediaIds)

        assertEquals(2, dao.requestedMediaIdBatches.size)
        assertTrue(dao.requestedMediaIdBatches.all { it.size <= 900 })
        assertEquals(1001, records.size)
    }

    @Test
    fun findByPlanAndMediaItems_returnsEmptyForEmptyInputWithoutDaoCall() = runTest {
        val dao = FakeBackupRecordDao()
        val repository = DefaultBackupRecordRepository(dao)

        val records = repository.findByPlanAndMediaItems(planId = 42L, mediaItemIds = emptyList())

        assertTrue(records.isEmpty())
        assertTrue(dao.requestedMediaIdBatches.isEmpty())
    }

    private class FakeBackupRecordDao : BackupRecordDao {
        val requestedMediaIdBatches = mutableListOf<List<String>>()

        override suspend fun insert(record: BackupRecordEntity): Long = record.recordId

        override suspend fun getByPlanAndMediaItem(planId: Long, mediaItemId: String): BackupRecordEntity? = null

        override suspend fun getByPlanAndMediaItems(planId: Long, mediaItemIds: List<String>): List<BackupRecordEntity> {
            requestedMediaIdBatches += mediaItemIds
            return mediaItemIds.mapIndexed { index, mediaId ->
                BackupRecordEntity(
                    recordId = index.toLong(),
                    planId = planId,
                    mediaItemId = mediaId,
                    remotePath = "backup/$mediaId",
                    uploadedAtEpochMs = 1L,
                )
            }
        }
    }
}
