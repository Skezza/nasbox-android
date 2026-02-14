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
            database.execSQL("ALTER TABLE plans ADD COLUMN source_type TEXT NOT NULL DEFAULT 'ALBUM'")
            database.execSQL("ALTER TABLE plans ADD COLUMN folder_path TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE plans ADD COLUMN include_videos INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE plans ADD COLUMN use_album_templating INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration3To4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE servers ADD COLUMN domain TEXT NOT NULL DEFAULT ''")
        }
    }

    fun get(context: Context): SMBSyncDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SMBSyncDatabase::class.java,
                "smbsync.db",
            ).addMigrations(migration1To2, migration2To3)
                .addMigrations(migration3To4)
                .build().also { instance = it }
        }
    }
}
