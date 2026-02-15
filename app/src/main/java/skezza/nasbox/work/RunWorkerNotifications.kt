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

internal object RunWorkerNotifications {
    const val RUNNING_CHANNEL_ID = "nasbox.backup.running"
    const val ISSUE_CHANNEL_ID = "nasbox.backup.issue"
    const val FOREGROUND_NOTIFICATION_ID = 401
    private const val ISSUE_NOTIFICATION_ID_BASE = 2_000

    fun createForegroundInfo(
        context: Context,
        planId: Long,
    ): ForegroundInfo {
        ensureChannel(
            context = context,
            channelId = RUNNING_CHANNEL_ID,
            name = "Backup Running",
            importance = NotificationManager.IMPORTANCE_LOW,
        )
        val notification = NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Open app to resume now", pendingIntent)
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
}
