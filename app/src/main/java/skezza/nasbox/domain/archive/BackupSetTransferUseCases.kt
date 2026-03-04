package skezza.nasbox.domain.archive

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import skezza.nasbox.data.db.BackupRecordEntity
import skezza.nasbox.data.db.NasBoxDatabase
import skezza.nasbox.data.db.PlanEntity
import skezza.nasbox.data.db.ServerEntity

data class ExportBackupSetsResult(
    val serverCount: Int,
    val planCount: Int,
    val recordCount: Int,
)

data class ImportBackupSetsResult(
    val createdServerCount: Int,
    val reusedServerCount: Int,
    val createdPlanCount: Int,
    val importedRecordCount: Int,
    val serversNeedingPasswordCount: Int,
)

data class ExportDocument(
    val formatVersion: Int,
    val exportedAtEpochMs: Long,
    val app: String,
    val servers: List<ExportServerDto>,
    val backupSets: List<ExportBackupSetDto>,
)

data class ExportServerDto(
    val exportServerId: Long,
    val name: String,
    val host: String,
    val shareName: String,
    val basePath: String,
    val domain: String,
    val username: String,
)

data class ExportBackupSetDto(
    val plan: ExportPlanDto,
    val records: List<ExportRecordDto>,
)

data class ExportPlanDto(
    val exportPlanId: Long,
    val name: String,
    val sourceType: String,
    val sourceAlbumIdsCsv: String,
    val folderPath: String,
    val sourceIncludeVideos: Boolean,
    val useAlbumTemplating: Boolean,
    val serverExportId: Long,
    val remoteFolderTemplate: String,
    val remoteFileNameTemplate: String,
    val scheduleIntervalHours: Int,
    val progressNotificationEnabled: Boolean,
    val checksumVerificationEnabled: Boolean,
)

data class ExportRecordDto(
    val mediaItemId: String,
    val remotePath: String,
    val uploadedAtEpochMs: Long,
    val verifiedSizeBytes: Long?,
    val checksumAlgorithm: String?,
    val checksumValue: String?,
    val checksumVerifiedAtEpochMs: Long?,
)

class ExportBackupSetsUseCase(
    private val context: Context,
    private val database: NasBoxDatabase,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend operator fun invoke(targetUri: Uri): ExportBackupSetsResult = withContext(Dispatchers.IO) {
        val document = buildExportDocument(
            servers = database.serverDao().getAll(),
            plans = database.planDao().getAll(),
            recordsForPlan = { planId -> database.backupRecordDao().getForPlan(planId) },
            exportedAtEpochMs = nowEpochMs(),
        )
        val content = document.toJson().toString(2)
        val outputStream = context.contentResolver.openOutputStream(targetUri, "wt")
            ?: throw IOException("Unable to open output stream.")
        outputStream.use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writer.write(content)
                writer.flush()
            }
        }
        ExportBackupSetsResult(
            serverCount = document.servers.size,
            planCount = document.backupSets.size,
            recordCount = document.backupSets.sumOf { it.records.size },
        )
    }
}

class ImportBackupSetsUseCase(
    private val context: Context,
    private val database: NasBoxDatabase,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend operator fun invoke(sourceUri: Uri): ImportBackupSetsResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Unable to open input stream.")
        val json = inputStream.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
        val document = parseExportDocument(json)
        importExportDocument(
            database = database,
            document = document,
            nowEpochMs = nowEpochMs,
        )
    }
}

internal suspend fun buildExportDocument(
    servers: List<ServerEntity>,
    plans: List<PlanEntity>,
    recordsForPlan: suspend (Long) -> List<BackupRecordEntity>,
    exportedAtEpochMs: Long,
): ExportDocument {
    val sortedPlans = plans.sortedBy { it.planId }
    val referencedServerIds = sortedPlans.map { it.serverId }.toSet()
    val exportedServers = servers
        .filter { referencedServerIds.contains(it.serverId) }
        .sortedBy { it.serverId }
        .map { server ->
            ExportServerDto(
                exportServerId = server.serverId,
                name = server.name,
                host = server.host,
                shareName = server.shareName,
                basePath = server.basePath,
                domain = server.domain,
                username = server.username,
            )
        }
    val exportedBackupSets = sortedPlans.map { plan ->
        val records = recordsForPlan(plan.planId)
            .sortedBy { it.recordId }
            .map { record ->
                ExportRecordDto(
                    mediaItemId = record.mediaItemId,
                    remotePath = record.remotePath,
                    uploadedAtEpochMs = record.uploadedAtEpochMs,
                    verifiedSizeBytes = record.verifiedSizeBytes,
                    checksumAlgorithm = record.checksumAlgorithm,
                    checksumValue = record.checksumValue,
                    checksumVerifiedAtEpochMs = record.checksumVerifiedAtEpochMs,
                )
            }
        ExportBackupSetDto(
            plan = ExportPlanDto(
                exportPlanId = plan.planId,
                name = plan.name,
                sourceType = plan.sourceType,
                sourceAlbumIdsCsv = plan.sourceAlbum,
                folderPath = plan.folderPath,
                sourceIncludeVideos = plan.includeVideos,
                useAlbumTemplating = plan.useAlbumTemplating,
                serverExportId = plan.serverId,
                remoteFolderTemplate = plan.directoryTemplate,
                remoteFileNameTemplate = plan.filenamePattern,
                scheduleIntervalHours = plan.scheduleIntervalHours,
                progressNotificationEnabled = plan.progressNotificationEnabled,
                checksumVerificationEnabled = plan.checksumVerificationEnabled,
            ),
            records = records,
        )
    }
    return ExportDocument(
        formatVersion = EXPORT_FORMAT_VERSION,
        exportedAtEpochMs = exportedAtEpochMs,
        app = EXPORT_APP_NAME,
        servers = exportedServers,
        backupSets = exportedBackupSets,
    )
}

internal fun ExportDocument.toJson(): JSONObject {
    return JSONObject()
        .put("formatVersion", formatVersion)
        .put("exportedAtEpochMs", exportedAtEpochMs)
        .put("app", app)
        .put(
            "servers",
            JSONArray().apply {
                servers.forEach { server ->
                    put(
                        JSONObject()
                            .put("exportServerId", server.exportServerId)
                            .put("name", server.name)
                            .put("host", server.host)
                            .put("shareName", server.shareName)
                            .put("basePath", server.basePath)
                            .put("domain", server.domain)
                            .put("username", server.username),
                    )
                }
            },
        )
        .put(
            "backupSets",
            JSONArray().apply {
                backupSets.forEach { backupSet ->
                    put(
                        JSONObject()
                            .put(
                                "plan",
                                JSONObject()
                                    .put("exportPlanId", backupSet.plan.exportPlanId)
                                    .put("name", backupSet.plan.name)
                                    .put("sourceType", backupSet.plan.sourceType)
                                    .put("sourceAlbumIdsCsv", backupSet.plan.sourceAlbumIdsCsv)
                                    .put("folderPath", backupSet.plan.folderPath)
                                    .put("sourceIncludeVideos", backupSet.plan.sourceIncludeVideos)
                                    .put("useAlbumTemplating", backupSet.plan.useAlbumTemplating)
                                    .put("serverExportId", backupSet.plan.serverExportId)
                                    .put("remoteFolderTemplate", backupSet.plan.remoteFolderTemplate)
                                    .put("remoteFileNameTemplate", backupSet.plan.remoteFileNameTemplate)
                                    .put("scheduleIntervalHours", backupSet.plan.scheduleIntervalHours)
                                    .put("scheduleOnlyOnWifi", false)
                                    .put("progressNotificationEnabled", backupSet.plan.progressNotificationEnabled)
                                    .put("checksumVerificationEnabled", backupSet.plan.checksumVerificationEnabled),
                            )
                            .put(
                                "records",
                                JSONArray().apply {
                                    backupSet.records.forEach { record ->
                                        put(
                                            JSONObject()
                                                .put("mediaItemId", record.mediaItemId)
                                                .put("remotePath", record.remotePath)
                                                .put("uploadedAtEpochMs", record.uploadedAtEpochMs)
                                                .put("verifiedSizeBytes", record.verifiedSizeBytes)
                                                .put("checksumAlgorithm", record.checksumAlgorithm)
                                                .put("checksumValue", record.checksumValue)
                                                .put(
                                                    "checksumVerifiedAtEpochMs",
                                                    record.checksumVerifiedAtEpochMs,
                                                ),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
}

internal fun parseExportDocument(json: String): ExportDocument {
    val root = try {
        JSONObject(json)
    } catch (error: JSONException) {
        throw IllegalArgumentException("Invalid export format.", error)
    }
    val formatVersion = root.optInt("formatVersion", -1)
    if (formatVersion != EXPORT_FORMAT_VERSION) {
        throw IllegalArgumentException("Unsupported export format.")
    }
    val serversArray = root.optJSONArray("servers")
        ?: throw IllegalArgumentException("Missing servers.")
    val backupSetsArray = root.optJSONArray("backupSets")
        ?: throw IllegalArgumentException("Missing backup sets.")
    val servers = buildList {
        for (index in 0 until serversArray.length()) {
            val item = serversArray.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid server entry.")
            add(
                ExportServerDto(
                    exportServerId = item.requireLong("exportServerId"),
                    name = item.requireString("name"),
                    host = item.requireString("host"),
                    shareName = item.requireString("shareName"),
                    basePath = item.requireString("basePath"),
                    domain = item.optString("domain", ""),
                    username = item.requireString("username"),
                ),
            )
        }
    }
    val serverIds = servers.map { it.exportServerId }.toSet()
    val backupSets = buildList {
        for (index in 0 until backupSetsArray.length()) {
            val item = backupSetsArray.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid backup set entry.")
            val plan = item.optJSONObject("plan")
                ?: throw IllegalArgumentException("Missing plan entry.")
            val recordsArray = item.optJSONArray("records")
                ?: throw IllegalArgumentException("Missing records entry.")
            val exportPlan = ExportPlanDto(
                exportPlanId = plan.requireLong("exportPlanId"),
                name = plan.requireString("name"),
                sourceType = plan.requireString("sourceType"),
                sourceAlbumIdsCsv = plan.optString("sourceAlbumIdsCsv", ""),
                folderPath = plan.optString("folderPath", ""),
                sourceIncludeVideos = plan.optBoolean("sourceIncludeVideos", false),
                useAlbumTemplating = plan.optBoolean("useAlbumTemplating", false),
                serverExportId = plan.requireLong("serverExportId"),
                remoteFolderTemplate = plan.optString("remoteFolderTemplate", ""),
                remoteFileNameTemplate = plan.optString("remoteFileNameTemplate", ""),
                scheduleIntervalHours = max(1, plan.optInt("scheduleIntervalHours", DEFAULT_SCHEDULE_INTERVAL_HOURS)),
                progressNotificationEnabled = plan.optBoolean("progressNotificationEnabled", true),
                checksumVerificationEnabled = plan.optBoolean("checksumVerificationEnabled", false),
            )
            if (!serverIds.contains(exportPlan.serverExportId)) {
                throw IllegalArgumentException("Backup set references missing server.")
            }
            val records = buildList {
                for (recordIndex in 0 until recordsArray.length()) {
                    val record = recordsArray.optJSONObject(recordIndex)
                        ?: throw IllegalArgumentException("Invalid backup record entry.")
                    add(
                        ExportRecordDto(
                            mediaItemId = record.requireString("mediaItemId"),
                            remotePath = record.requireString("remotePath"),
                            uploadedAtEpochMs = record.requireLong("uploadedAtEpochMs"),
                            verifiedSizeBytes = record.optNullableLong("verifiedSizeBytes"),
                            checksumAlgorithm = record.optNullableString("checksumAlgorithm"),
                            checksumValue = record.optNullableString("checksumValue"),
                            checksumVerifiedAtEpochMs = record.optNullableLong("checksumVerifiedAtEpochMs"),
                        ),
                    )
                }
            }
            add(ExportBackupSetDto(plan = exportPlan, records = records))
        }
    }
    return ExportDocument(
        formatVersion = formatVersion,
        exportedAtEpochMs = root.optLong("exportedAtEpochMs", 0L),
        app = root.optString("app", EXPORT_APP_NAME),
        servers = servers,
        backupSets = backupSets,
    )
}

internal suspend fun importExportDocument(
    database: NasBoxDatabase,
    document: ExportDocument,
    nowEpochMs: () -> Long = { System.currentTimeMillis() },
): ImportBackupSetsResult {
    val importTime = nowEpochMs()
    return database.withTransaction {
        val serverDao = database.serverDao()
        val planDao = database.planDao()
        val backupRecordDao = database.backupRecordDao()

        val existingServers = serverDao.getAll().toMutableList()
        val serverNameSet = existingServers.mapTo(linkedSetOf()) { it.name }
        val serverIdMap = linkedMapOf<Long, Long>()
        var createdServers = 0
        var reusedServers = 0

        document.servers.sortedBy { it.exportServerId }.forEach { exportedServer ->
            val existingMatch = existingServers.firstOrNull { server ->
                server.host == exportedServer.host &&
                    server.shareName == exportedServer.shareName &&
                    server.basePath == exportedServer.basePath &&
                    server.domain == exportedServer.domain &&
                    server.username == exportedServer.username
            }
            if (existingMatch != null) {
                serverIdMap[exportedServer.exportServerId] = existingMatch.serverId
                reusedServers += 1
            } else {
                val resolvedName = resolveImportedName(exportedServer.name, serverNameSet)
                val entity = ServerEntity(
                    name = resolvedName,
                    host = exportedServer.host,
                    shareName = exportedServer.shareName,
                    basePath = exportedServer.basePath,
                    domain = exportedServer.domain,
                    username = exportedServer.username,
                    credentialAlias = "archive/${UUID.randomUUID()}",
                    lastTestStatus = "FAILED",
                    lastTestTimestampEpochMs = importTime,
                    lastTestErrorCategory = "AUTH",
                    lastTestErrorMessage = PASSWORD_NOT_IMPORTED_MESSAGE,
                )
                val serverId = serverDao.insert(entity)
                existingServers += entity.copy(serverId = serverId)
                serverNameSet += resolvedName
                serverIdMap[exportedServer.exportServerId] = serverId
                createdServers += 1
            }
        }

        val existingPlans = planDao.getAll().toMutableList()
        val planNameSet = existingPlans.mapTo(linkedSetOf()) { it.name }
        var createdPlans = 0
        var importedRecords = 0

        document.backupSets.forEach { backupSet ->
            val localServerId = serverIdMap[backupSet.plan.serverExportId]
                ?: throw IllegalArgumentException("Backup set references unknown server.")
            val resolvedPlanName = resolveImportedName(backupSet.plan.name, planNameSet)
            val planEntity = PlanEntity(
                name = resolvedPlanName,
                sourceAlbum = backupSet.plan.sourceAlbumIdsCsv,
                sourceType = backupSet.plan.sourceType,
                folderPath = backupSet.plan.folderPath,
                includeVideos = backupSet.plan.sourceIncludeVideos,
                useAlbumTemplating = backupSet.plan.useAlbumTemplating,
                serverId = localServerId,
                directoryTemplate = backupSet.plan.remoteFolderTemplate,
                filenamePattern = backupSet.plan.remoteFileNameTemplate,
                enabled = false,
                scheduleEnabled = false,
                scheduleTimeMinutes = DEFAULT_SCHEDULE_TIME_MINUTES,
                scheduleFrequency = DEFAULT_SCHEDULE_FREQUENCY,
                scheduleDaysMask = DEFAULT_SCHEDULE_DAYS_MASK,
                scheduleDayOfMonth = DEFAULT_SCHEDULE_DAY_OF_MONTH,
                scheduleIntervalHours = max(1, backupSet.plan.scheduleIntervalHours),
                progressNotificationEnabled = backupSet.plan.progressNotificationEnabled,
                checksumVerificationEnabled = backupSet.plan.checksumVerificationEnabled,
                pendingScheduledVerify = false,
                importedAtEpochMs = importTime,
            )
            val planId = planDao.insert(planEntity)
            existingPlans += planEntity.copy(planId = planId)
            planNameSet += resolvedPlanName
            createdPlans += 1

            backupSet.records.forEach { record ->
                backupRecordDao.insert(
                    BackupRecordEntity(
                        planId = planId,
                        mediaItemId = record.mediaItemId,
                        remotePath = record.remotePath,
                        uploadedAtEpochMs = record.uploadedAtEpochMs,
                        verifiedSizeBytes = record.verifiedSizeBytes,
                        checksumAlgorithm = record.checksumAlgorithm,
                        checksumValue = record.checksumValue,
                        checksumVerifiedAtEpochMs = record.checksumVerifiedAtEpochMs,
                    ),
                )
                importedRecords += 1
            }
        }

        ImportBackupSetsResult(
            createdServerCount = createdServers,
            reusedServerCount = reusedServers,
            createdPlanCount = createdPlans,
            importedRecordCount = importedRecords,
            serversNeedingPasswordCount = createdServers,
        )
    }
}

internal fun buildExportFileName(nowEpochMs: Long): String {
    val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    val timestamp = formatter.format(Date(nowEpochMs))
    return "nasbox-export-$timestamp.json"
}

private fun resolveImportedName(baseName: String, existingNames: Set<String>): String {
    val trimmedBase = baseName.trim().ifBlank { "Imported" }
    if (!existingNames.contains(trimmedBase)) return trimmedBase
    val importedBase = "$trimmedBase (Imported)"
    if (!existingNames.contains(importedBase)) return importedBase
    var index = 2
    while (true) {
        val candidate = "$trimmedBase (Imported $index)"
        if (!existingNames.contains(candidate)) return candidate
        index += 1
    }
}

private fun JSONObject.requireString(key: String): String {
    val value = optString(key, "").trim()
    if (value.isBlank()) {
        throw IllegalArgumentException("Missing $key.")
    }
    return value
}

private fun JSONObject.requireLong(key: String): Long {
    if (!has(key) || isNull(key)) {
        throw IllegalArgumentException("Missing $key.")
    }
    return try {
        getLong(key)
    } catch (_: JSONException) {
        throw IllegalArgumentException("Invalid $key.")
    }
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return try {
        getString(key).trim().ifBlank { null }
    } catch (_: JSONException) {
        throw IllegalArgumentException("Invalid $key.")
    }
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return try {
        getLong(key)
    } catch (_: JSONException) {
        throw IllegalArgumentException("Invalid $key.")
    }
}

private const val EXPORT_FORMAT_VERSION = 1
private const val EXPORT_APP_NAME = "NASBox"
private const val PASSWORD_NOT_IMPORTED_MESSAGE = "Password not imported. Re-save this server."
private const val DEFAULT_SCHEDULE_TIME_MINUTES = 120
private const val DEFAULT_SCHEDULE_FREQUENCY = "DAILY"
private const val DEFAULT_SCHEDULE_DAYS_MASK = 127
private const val DEFAULT_SCHEDULE_DAY_OF_MONTH = 1
private const val DEFAULT_SCHEDULE_INTERVAL_HOURS = 24
