package skezza.smbsync.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerEntity::class,
        PlanEntity::class,
        BackupRecordEntity::class,
        RunEntity::class,
        RunLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SMBSyncDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun planDao(): PlanDao
    abstract fun backupRecordDao(): BackupRecordDao
    abstract fun runDao(): RunDao
    abstract fun runLogDao(): RunLogDao
}
