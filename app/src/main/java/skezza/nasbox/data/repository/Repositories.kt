package skezza.nasbox.data.repository

import kotlinx.coroutines.flow.Flow
import skezza.nasbox.data.db.BackupRecordDao
import skezza.nasbox.data.db.BackupRecordEntity
import skezza.nasbox.data.db.PlanDao
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.RunDao
import skezza.nasbox.data.db.RunEntity
import skezza.nasbox.data.db.RunLogDao
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.db.RunTimelineLogRow
import skezza.nasbox.data.db.ServerDao
import skezza.nasbox.data.db.ServerEntity

interface ServerRepository {
    fun observeServers(): Flow<List<ServerEntity>>
    suspend fun getServer(serverId: Long): ServerEntity?
    suspend fun createServer(server: ServerEntity): Long
    suspend fun updateServer(server: ServerEntity)
    suspend fun deleteServer(serverId: Long)
}

class DefaultServerRepository(
    private val serverDao: ServerDao,
) : ServerRepository {
    override fun observeServers(): Flow<List<ServerEntity>> = serverDao.observeAll()

    override suspend fun getServer(serverId: Long): ServerEntity? = serverDao.getById(serverId)

    override suspend fun createServer(server: ServerEntity): Long = serverDao.insert(server)

    override suspend fun updateServer(server: ServerEntity) = serverDao.update(server)

    override suspend fun deleteServer(serverId: Long) = serverDao.delete(serverId)
}

interface PlanRepository {
    fun observePlans(): Flow<List<PlanEntity>>
    suspend fun getPlan(planId: Long): PlanEntity?
    suspend fun createPlan(plan: PlanEntity): Long
    suspend fun updatePlan(plan: PlanEntity)
    suspend fun deletePlan(planId: Long)
}

class DefaultPlanRepository(
    private val planDao: PlanDao,
) : PlanRepository {
    override fun observePlans(): Flow<List<PlanEntity>> = planDao.observeAll()

    override suspend fun getPlan(planId: Long): PlanEntity? = planDao.getById(planId)

    override suspend fun createPlan(plan: PlanEntity): Long = planDao.insert(plan)

    override suspend fun updatePlan(plan: PlanEntity) = planDao.update(plan)

    override suspend fun deletePlan(planId: Long) = planDao.delete(planId)
}

interface BackupRecordRepository {
    suspend fun create(record: BackupRecordEntity): Long
    suspend fun findByPlanAndMediaItem(planId: Long, mediaItemId: String): BackupRecordEntity?
}

class DefaultBackupRecordRepository(
    private val backupRecordDao: BackupRecordDao,
) : BackupRecordRepository {
    override suspend fun create(record: BackupRecordEntity): Long = backupRecordDao.insert(record)

    override suspend fun findByPlanAndMediaItem(
        planId: Long,
        mediaItemId: String,
    ): BackupRecordEntity? = backupRecordDao.getByPlanAndMediaItem(planId, mediaItemId)
}

interface RunRepository {
    fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>>
    fun observeLatestRun(): Flow<RunEntity?>
    fun observeLatestRuns(limit: Int): Flow<List<RunEntity>>
    fun observeRunsByStatus(status: String): Flow<List<RunEntity>>
    fun observeRun(runId: Long): Flow<RunEntity?>
    suspend fun createRun(run: RunEntity): Long
    suspend fun updateRun(run: RunEntity)
    suspend fun getRun(runId: Long): RunEntity?
    suspend fun runsByStatus(status: String): List<RunEntity>
    suspend fun latestRuns(limit: Int): List<RunEntity>
}

class DefaultRunRepository(
    private val runDao: RunDao,
) : RunRepository {
    override fun observeRunsForPlan(planId: Long): Flow<List<RunEntity>> = runDao.observeForPlan(planId)

    override fun observeLatestRun(): Flow<RunEntity?> = runDao.observeLatest()

    override fun observeLatestRuns(limit: Int): Flow<List<RunEntity>> = runDao.observeLatestRuns(limit)

    override fun observeRunsByStatus(status: String): Flow<List<RunEntity>> = runDao.observeByStatus(status)

    override fun observeRun(runId: Long): Flow<RunEntity?> = runDao.observeById(runId)

    override suspend fun createRun(run: RunEntity): Long = runDao.insert(run)

    override suspend fun updateRun(run: RunEntity) = runDao.update(run)

    override suspend fun getRun(runId: Long): RunEntity? = runDao.getById(runId)

    override suspend fun runsByStatus(status: String): List<RunEntity> = runDao.getByStatus(status)

    override suspend fun latestRuns(limit: Int): List<RunEntity> = runDao.getLatest(limit)
}

interface RunLogRepository {
    suspend fun createLog(log: RunLogEntity): Long
    suspend fun logsForRun(runId: Long): List<RunLogEntity>
    fun observeLogsForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>>
    fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>>
}

class DefaultRunLogRepository(
    private val runLogDao: RunLogDao,
) : RunLogRepository {
    override suspend fun createLog(log: RunLogEntity): Long = runLogDao.insert(log)

    override suspend fun logsForRun(runId: Long): List<RunLogEntity> = runLogDao.getForRun(runId)

    override fun observeLogsForRunNewest(runId: Long, limit: Int): Flow<List<RunLogEntity>> =
        runLogDao.observeForRunNewest(runId, limit)

    override fun observeLatestTimeline(limit: Int): Flow<List<RunTimelineLogRow>> = runLogDao.observeLatestTimeline(limit)
}
