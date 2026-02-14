package skezza.smbsync.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseProvider {
    @Volatile
    private var instance: SMBSyncDatabase? = null

    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE servers ADD COLUMN lastTestLatencyMs INTEGER")
            database.execSQL("ALTER TABLE servers ADD COLUMN lastTestErrorCategory TEXT")
            database.execSQL("ALTER TABLE servers ADD COLUMN lastTestErrorMessage TEXT")
        }
    }

    private val migration2To3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // No schema changes; version bump preserves forward compatibility with previously shipped v3 db.
        }
    }

    fun get(context: Context): SMBSyncDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SMBSyncDatabase::class.java,
                "smbsync.db",
            ).addMigrations(migration1To2, migration2To3)
                .build().also { instance = it }
        }
    }
}
