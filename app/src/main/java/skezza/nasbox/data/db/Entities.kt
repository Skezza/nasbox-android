package skezza.nasbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "servers",
    indices = [
        Index(value = ["name"], unique = true),
    ],
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "server_id")
    val serverId: Long = 0,
    val name: String,
    val host: String,
    val shareName: String,
    val basePath: String,
    val domain: String,
    val username: String,
    val credentialAlias: String,
    val lastTestStatus: String? = null,
    val lastTestTimestampEpochMs: Long? = null,
    val lastTestLatencyMs: Long? = null,
    val lastTestErrorCategory: String? = null,
    val lastTestErrorMessage: String? = null,
)

@Entity(
    tableName = "plans",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["server_id"],
            childColumns = ["server_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["server_id"]),
    ],
)
data class PlanEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "plan_id")
    val planId: Long = 0,
    val name: String,
    @ColumnInfo(name = "source_album")
    val sourceAlbum: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String = "ALBUM",
    @ColumnInfo(name = "folder_path")
    val folderPath: String = "",
    @ColumnInfo(name = "include_videos")
    val includeVideos: Boolean = false,
    @ColumnInfo(name = "use_album_templating")
    val useAlbumTemplating: Boolean = false,
    @ColumnInfo(name = "server_id")
    val serverId: Long,
    @ColumnInfo(name = "directory_template")
    val directoryTemplate: String,
    @ColumnInfo(name = "filename_pattern")
    val filenamePattern: String,
    val enabled: Boolean,
)

@Entity(
    tableName = "backup_records",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["plan_id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["plan_id", "media_item_id"], unique = true),
    ],
)
data class BackupRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "record_id")
    val recordId: Long = 0,
    @ColumnInfo(name = "plan_id")
    val planId: Long,
    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String,
    @ColumnInfo(name = "remote_path")
    val remotePath: String,
    @ColumnInfo(name = "uploaded_at_epoch_ms")
    val uploadedAtEpochMs: Long,
)

@Entity(
    tableName = "runs",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["plan_id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["plan_id"]),
        Index(value = ["started_at_epoch_ms"]),
    ],
)
data class RunEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "run_id")
    val runId: Long = 0,
    @ColumnInfo(name = "plan_id")
    val planId: Long,
    val status: String,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAtEpochMs: Long,
    @ColumnInfo(name = "finished_at_epoch_ms")
    val finishedAtEpochMs: Long? = null,
    @ColumnInfo(name = "scanned_count")
    val scannedCount: Int = 0,
    @ColumnInfo(name = "uploaded_count")
    val uploadedCount: Int = 0,
    @ColumnInfo(name = "skipped_count")
    val skippedCount: Int = 0,
    @ColumnInfo(name = "failed_count")
    val failedCount: Int = 0,
    @ColumnInfo(name = "summary_error")
    val summaryError: String? = null,
)

@Entity(
    tableName = "run_logs",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id", "timestamp_epoch_ms"]),
    ],
)
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "log_id")
    val logId: Long = 0,
    @ColumnInfo(name = "run_id")
    val runId: Long,
    @ColumnInfo(name = "timestamp_epoch_ms")
    val timestampEpochMs: Long,
    val severity: String,
    val message: String,
    val detail: String? = null,
)
