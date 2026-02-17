package skezza.nasbox.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import skezza.nasbox.AppContainer

class RunNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_RUN) return
        val runId = intent.getLongExtra(EXTRA_RUN_ID, -1L)
        if (runId <= 0L) return

        val appContext = context.applicationContext
        val providedPlanId = intent.getLongExtra(EXTRA_PLAN_ID, -1L)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContainer = AppContainer(appContext)
                val run = appContainer.runRepository.getRun(runId)
                val planId = providedPlanId.takeIf { it > 0L } ?: run?.planId ?: -1L
                appContainer.stopRunUseCase(runId)
                if (planId > 0L) {
                    appContainer.enqueuePlanRunUseCase.cancelQueuedPlanRunWork(planId)
                }
                RunWorkerNotifications.cancelProgressNotification(
                    context = appContext,
                    runId = runId,
                    planId = planId.takeIf { it > 0L } ?: 0L,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP_RUN = "skezza.nasbox.action.STOP_RUN"
        const val EXTRA_RUN_ID = "extra_run_id"
        const val EXTRA_PLAN_ID = "extra_plan_id"
    }
}
