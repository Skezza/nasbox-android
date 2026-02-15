package skezza.nasbox.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import skezza.nasbox.AppContainer
import skezza.nasbox.R
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.domain.sync.RunTriggerSource

class RunPlanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val appContainer = AppContainer(appContext)

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(KEY_PLAN_ID, -1L)
        if (planId <= 0L) return Result.failure()

        val triggerSource = inputData.getString(KEY_TRIGGER_SOURCE) ?: RunTriggerSource.MANUAL
        return runCatching {
            setForeground(createForegroundInfo(planId))
            val result = appContainer.runPlanBackupUseCase(planId, triggerSource)
            if (triggerSource == RunTriggerSource.SCHEDULED && result.status in FAILURE_STATUSES) {
                postOutcomeNotification(
                    title = "Scheduled backup needs attention",
                    message = listOfNotNull(
                        "Plan #$planId finished ${result.status.lowercase()}",
                        result.summaryError,
                    ).joinToString(" - "),
                    notificationId = (OUTCOME_NOTIFICATION_ID_BASE + result.runId).toInt(),
                )
            }
            Result.success()
        }.getOrElse { error ->
            // If the worker fails before run finalization (for example foreground restrictions),
            // close any orphaned active rows so dashboard state stays accurate.
            runCatching {
                appContainer.reconcileStaleActiveRunsUseCase(forceFinalizeActive = true)
            }
            if (triggerSource == RunTriggerSource.SCHEDULED) {
                postOutcomeNotification(
                    title = "Scheduled backup failed to start",
                    message = error.message ?: "Unexpected worker error.",
                    notificationId = OUTCOME_NOTIFICATION_ID_BASE + planId.toInt(),
                )
            }
            Result.retry()
        }
    }

    private fun createForegroundInfo(planId: Long): ForegroundInfo {
        ensureChannel(RUNNING_CHANNEL_ID, "Scheduled Backups")
        val notification = NotificationCompat.Builder(applicationContext, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Backup running")
            .setContentText("Plan #$planId is backing up files.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun postOutcomeNotification(
        title: String,
        message: String,
        notificationId: Int,
    ) {
        ensureChannel(OUTCOME_CHANNEL_ID, "Backup Alerts")
        val notification = NotificationCompat.Builder(applicationContext, OUTCOME_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(notificationId, notification)
        }
    }

    private fun ensureChannel(channelId: String, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(channelId)
        if (existing == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    companion object {
        const val KEY_PLAN_ID = "run_plan_id"
        const val KEY_TRIGGER_SOURCE = "run_trigger_source"

        private const val RUNNING_CHANNEL_ID = "nasbox.backup.running"
        private const val OUTCOME_CHANNEL_ID = "nasbox.backup.outcome"
        private const val FOREGROUND_NOTIFICATION_ID = 401
        private const val OUTCOME_NOTIFICATION_ID_BASE = 2_000
        private val FAILURE_STATUSES = setOf(RunStatus.FAILED, RunStatus.PARTIAL, RunStatus.INTERRUPTED)
    }
}
