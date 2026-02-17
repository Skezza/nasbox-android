package skezza.nasbox.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import skezza.nasbox.MainActivity
import skezza.nasbox.R
import skezza.nasbox.domain.sync.RunProgressSnapshot

internal object RunWorkerNotifications {
    const val RUNNING_CHANNEL_ID = "nasbox.backup.running"
    const val ISSUE_CHANNEL_ID = "nasbox.backup.issue"
    private const val FOREGROUND_NOTIFICATION_ID_BASE = 401
    private const val ISSUE_NOTIFICATION_ID_BASE = 2_000

    fun createForegroundInfo(
        context: Context,
        planId: Long,
        planName: String? = null,
    ): ForegroundInfo {
        return createForegroundInfo(
            context = context,
            snapshot = RunProgressSnapshot(
                runId = 0L,
                planId = planId,
                status = "RUNNING",
                phase = "RUNNING",
                scannedCount = 0,
                uploadedCount = 0,
                skippedCount = 0,
                failedCount = 0,
            ),
            planName = planName,
        )
    }

    fun createForegroundInfo(
        context: Context,
        snapshot: RunProgressSnapshot,
        planName: String? = null,
    ): ForegroundInfo {
        ensureChannel(
            context = context,
            channelId = RUNNING_CHANNEL_ID,
            name = "Backup Running",
            importance = NotificationManager.IMPORTANCE_LOW,
        )
        val notificationId = foregroundNotificationId(snapshot.runId, snapshot.planId)
        val notification = buildProgressNotification(
            context = context,
            snapshot = snapshot,
            planName = planName,
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    fun cancelProgressNotification(
        context: Context,
        runId: Long,
        planId: Long,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.cancel(foregroundNotificationId(runId, planId))
        }
    }

    private fun buildProgressNotification(
        context: Context,
        snapshot: RunProgressSnapshot,
        planName: String?,
    ): Notification {
        val processed = snapshot.uploadedCount + snapshot.skippedCount + snapshot.failedCount
        val maxForProgress = snapshot.scannedCount.coerceAtLeast(0)
        val progressValue = if (maxForProgress > 0) {
            processed.coerceIn(0, maxForProgress)
        } else {
            0
        }
        val runId = snapshot.runId.takeIf { it > 0L }
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (runId != null) {
                putExtra(MainActivity.EXTRA_OPEN_RUN_ID, runId)
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            foregroundNotificationId(snapshot.runId, snapshot.planId),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resolvedJobName = planName?.trim()?.takeIf { it.isNotBlank() } ?: "Job #${snapshot.planId}"
        val contentText = "Uploaded ${snapshot.uploadedCount}, Skipped ${snapshot.skippedCount}, " +
            "Failed ${snapshot.failedCount}"

        val builder = NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nasbox)
            .setContentTitle("$resolvedJobName backup in progress")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (maxForProgress > 0) {
            builder.setProgress(maxForProgress, progressValue, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        runId?.let {
            val stopIntent = Intent(context, RunNotificationActionReceiver::class.java).apply {
                action = RunNotificationActionReceiver.ACTION_STOP_RUN
                putExtra(RunNotificationActionReceiver.EXTRA_RUN_ID, it)
                putExtra(RunNotificationActionReceiver.EXTRA_PLAN_ID, snapshot.planId)
            }
            val stopPendingIntent = PendingIntent.getBroadcast(
                context,
                foregroundNotificationId(snapshot.runId, snapshot.planId) + STOP_ACTION_REQUEST_OFFSET,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Stop", stopPendingIntent)
        }
        return builder.build().apply {
            flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }
    }

    private fun foregroundNotificationId(@Suppress("UNUSED_PARAMETER") runId: Long, planId: Long): Int {
        val seed = planId
        return FOREGROUND_NOTIFICATION_ID_BASE + ((seed and 0x7fffffff) % 50_000).toInt()
    }

    fun postIssueNotification(
        context: Context,
        title: String,
        message: String,
        notificationIdSeed: Long,
        runId: Long? = null,
    ) {
        ensureChannel(
            context = context,
            channelId = ISSUE_CHANNEL_ID,
            name = "Backup Issues",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (runId != null && runId > 0L) {
                putExtra(MainActivity.EXTRA_OPEN_RUN_ID, runId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (notificationIdSeed and 0x7fffffff).toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationId = ISSUE_NOTIFICATION_ID_BASE + ((notificationIdSeed and 0x7fffffff) % 10_000).toInt()
        val notification = NotificationCompat.Builder(context, ISSUE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nasbox)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Open app to resume", pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(notificationId, notification)
        }
    }

    private fun ensureChannel(
        context: Context,
        channelId: String,
        name: String,
        importance: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(channelId)
        if (existing == null) {
            manager.createNotificationChannel(NotificationChannel(channelId, name, importance))
        }
    }

    private const val STOP_ACTION_REQUEST_OFFSET = 70_000
}
