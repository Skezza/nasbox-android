package skezza.smbsync.data.db

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile
    private var instance: SMBSyncDatabase? = null

    fun get(context: Context): SMBSyncDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SMBSyncDatabase::class.java,
                "smbsync.db",
            ).build().also { instance = it }
        }
    }
}
