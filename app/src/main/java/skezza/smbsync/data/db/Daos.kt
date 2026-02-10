package skezza.smbsync.data.db

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
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: RunEntity): Long

    @Update
    suspend fun update(run: RunEntity)

    @Query("SELECT * FROM runs WHERE plan_id = :planId ORDER BY started_at_epoch_ms DESC")
    fun observeForPlan(planId: Long): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<RunEntity>
}

@Dao
interface RunLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: RunLogEntity): Long

    @Query("SELECT * FROM run_logs WHERE run_id = :runId ORDER BY timestamp_epoch_ms ASC")
    suspend fun getForRun(runId: Long): List<RunLogEntity>
}
