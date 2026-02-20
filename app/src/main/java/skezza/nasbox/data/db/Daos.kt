package skezza.nasbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Query("DELETE FROM servers WHERE server_id = :serverId")
    suspend fun delete(serverId: Long)

    @Query("SELECT * FROM servers ORDER BY name")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE server_id = :serverId")
    suspend fun getById(serverId: Long): ServerEntity?
}

@Dao
interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(plan: PlanEntity): Long

    @Update
    suspend fun update(plan: PlanEntity)

    @Query("DELETE FROM plans WHERE plan_id = :planId")
    suspend fun delete(planId: Long)

    @Query("SELECT * FROM plans ORDER BY name")
    fun observeAll(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE plan_id = :planId")
    suspend fun getById(planId: Long): PlanEntity?
}

@Dao
interface BackupRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: BackupRecordEntity): Long

    @Query("SELECT * FROM backup_records WHERE plan_id = :planId AND media_item_id = :mediaItemId")
    suspend fun getByPlanAndMediaItem(planId: Long, mediaItemId: String): BackupRecordEntity?

    @Query("SELECT * FROM backup_records WHERE plan_id = :planId AND media_item_id IN (:mediaItemIds)")
    suspend fun getByPlanAndMediaItems(planId: Long, mediaItemIds: List<String>): List<BackupRecordEntity>
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: RunEntity): Long

    @Update
    suspend fun update(run: RunEntity)

    @Query("DELETE FROM runs WHERE run_id = :runId")
    suspend fun deleteById(runId: Long)

    @Query("DELETE FROM runs WHERE run_id IN (:runIds)")
    suspend fun deleteByIds(runIds: List<Long>)

    @Query("SELECT * FROM runs WHERE plan_id = :planId ORDER BY started_at_epoch_ms DESC")
    fun observeForPlan(planId: Long): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs ORDER BY started_at_epoch_ms DESC LIMIT 1")
    fun observeLatest(): Flow<RunEntity?>

    @Query("SELECT * FROM runs ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    fun observeLatestRuns(limit: Int): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<RunEntity>

    @Query("SELECT * FROM runs WHERE status IN (:statuses) ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    fun observeLatestByStatuses(limit: Int, statuses: List<String>): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE status IN (:statuses) ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    suspend fun getLatestByStatuses(limit: Int, statuses: List<String>): List<RunEntity>

    @Query("SELECT * FROM runs WHERE status = :status ORDER BY started_at_epoch_ms DESC")
    fun observeByStatus(status: String): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE status = :status ORDER BY started_at_epoch_ms DESC")
    suspend fun getByStatus(status: String): List<RunEntity>

    @Query("SELECT * FROM runs WHERE run_id = :runId LIMIT 1")
    fun observeById(runId: Long): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE run_id = :runId LIMIT 1")
    suspend fun getById(runId: Long): RunEntity?
}

@Dao
interface RunLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: RunLogEntity): Long

    @Query("SELECT * FROM run_logs WHERE run_id = :runId ORDER BY timestamp_epoch_ms ASC")
    fun observeForRun(runId: Long): Flow<List<RunLogEntity>>

    @Query("SELECT * FROM run_logs WHERE run_id = :runId ORDER BY timestamp_epoch_ms ASC")
    suspend fun getForRun(runId: Long): List<RunLogEntity>

    @Query("SELECT * FROM run_logs WHERE run_id = :runId ORDER BY timestamp_epoch_ms DESC LIMIT :limit")
    fun observeForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>>

    @Query(
        """
        SELECT
            rl.log_id,
            rl.run_id,
            r.plan_id,
            rl.timestamp_epoch_ms,
            rl.severity,
            rl.message,
            rl.detail
        FROM run_logs rl
        INNER JOIN runs r ON r.run_id = rl.run_id
        ORDER BY rl.timestamp_epoch_ms DESC
        LIMIT :limit
        """,
    )
    fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>>
}
