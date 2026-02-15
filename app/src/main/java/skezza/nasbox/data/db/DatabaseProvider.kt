package skezza.nasbox.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseProvider {
    @Volatile
    private var instance: NasBoxDatabase? = null

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

    private val migration4To5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE plans ADD COLUMN schedule_enabled INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE plans ADD COLUMN schedule_time_minutes INTEGER NOT NULL DEFAULT 120")

            database.execSQL("ALTER TABLE runs ADD COLUMN heartbeat_at_epoch_ms INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE runs ADD COLUMN trigger_source TEXT NOT NULL DEFAULT 'MANUAL'")
            database.execSQL(
                "UPDATE runs SET heartbeat_at_epoch_ms = COALESCE(finished_at_epoch_ms, started_at_epoch_ms)",
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_runs_status ON runs(status)")
        }
    }

    private val migration5To6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE plans ADD COLUMN schedule_frequency TEXT NOT NULL DEFAULT 'DAILY'")
            database.execSQL("ALTER TABLE plans ADD COLUMN schedule_days_mask INTEGER NOT NULL DEFAULT 127")
            database.execSQL("ALTER TABLE plans ADD COLUMN schedule_day_of_month INTEGER NOT NULL DEFAULT 1")
            database.execSQL("ALTER TABLE plans ADD COLUMN schedule_interval_hours INTEGER NOT NULL DEFAULT 24")
        }
    }

    private val migration6To7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE runs ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'FOREGROUND'")
            database.execSQL("ALTER TABLE runs ADD COLUMN phase TEXT NOT NULL DEFAULT 'RUNNING'")
            database.execSQL("ALTER TABLE runs ADD COLUMN continuation_cursor TEXT")
            database.execSQL("ALTER TABLE runs ADD COLUMN resume_count INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE runs ADD COLUMN last_progress_at_epoch_ms INTEGER NOT NULL DEFAULT 0")
            database.execSQL(
                "UPDATE runs SET last_progress_at_epoch_ms = COALESCE(heartbeat_at_epoch_ms, started_at_epoch_ms)",
            )
        }
    }

    fun get(context: Context): NasBoxDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NasBoxDatabase::class.java,
                "nasbox.db",
            ).addMigrations(
                migration1To2,
                migration2To3,
                migration3To4,
                migration4To5,
                migration5To6,
                migration6To7,
            )
                .build().also { instance = it }
        }
    }
}
