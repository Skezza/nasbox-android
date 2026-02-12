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

    fun get(context: Context): SMBSyncDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SMBSyncDatabase::class.java,
                "smbsync.db",
            ).addMigrations(migration1To2)
                .build().also { instance = it }
        }
    }
}
