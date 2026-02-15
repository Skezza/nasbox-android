package skezza.nasbox.domain.sync

import java.util.Locale
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository

class ReconcileStaleActiveRunsUseCase(
    private val runRepository: RunRepository,
    private val runLogRepository: RunLogRepository,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val staleCancelRequestedAfterMs: Long = DEFAULT_STALE_CANCEL_REQUESTED_AFTER_MS,
    private val staleRunningAfterMs: Long = DEFAULT_STALE_RUNNING_AFTER_MS,
) {

    suspend operator fun invoke(limit: Int = MAX_RECONCILE_RUNS): ReconcileStaleActiveRunsResult {
        val now = nowEpochMs()
        val candidates = runRepository.latestRunsByStatuses(limit, ACTIVE_STATUSES)
        var canceledCount = 0
        var interruptedCount = 0

        candidates.forEach { run ->
            if (run.finishedAtEpochMs != null) return@forEach

            when (run.status.trim().uppercase(Locale.US)) {
                RunStatus.CANCEL_REQUESTED -> {
                    if (now - run.heartbeatAtEpochMs < staleCancelRequestedAfterMs) return@forEach
                    runRepository.updateRun(
                        run.copy(
                            status = RunStatus.CANCELED,
                            finishedAtEpochMs = now,
                            heartbeatAtEpochMs = now,
                            summaryError = run.summaryError ?: DEFAULT_CANCELED_SUMMARY,
                        ),
                    )
                    runLogRepository.createLog(
                        RunLogEntity(
                            runId = run.runId,
                            timestampEpochMs = now,
                            severity = "INFO",
                            message = "Run finalized as canceled",
                            detail = "Cancellation request became stale with no worker heartbeat.",
                        ),
                    )
                    canceledCount += 1
                }

                RunStatus.RUNNING -> {
                    if (now - run.heartbeatAtEpochMs < staleRunningAfterMs) return@forEach
                    runRepository.updateRun(
                        run.copy(
                            status = RunStatus.INTERRUPTED,
                            finishedAtEpochMs = now,
                            heartbeatAtEpochMs = now,
                            summaryError = run.summaryError ?: DEFAULT_INTERRUPTED_SUMMARY,
                        ),
                    )
                    runLogRepository.createLog(
                        RunLogEntity(
                            runId = run.runId,
                            timestampEpochMs = now,
                            severity = "ERROR",
                            message = "Run marked as interrupted",
                            detail = "No heartbeat received for an extended period.",
                        ),
                    )
                    interruptedCount += 1
                }
            }
        }

        return ReconcileStaleActiveRunsResult(
            canceledCount = canceledCount,
            interruptedCount = interruptedCount,
        )
    }

    companion object {
        // Keep RUNNING conservative for long single-file uploads; allow faster cleanup for stuck stop requests.
        private const val DEFAULT_STALE_CANCEL_REQUESTED_AFTER_MS = 15L * 60L * 1000L
        private const val DEFAULT_STALE_RUNNING_AFTER_MS = 60L * 60L * 1000L
        private const val MAX_RECONCILE_RUNS = 200
        private const val DEFAULT_CANCELED_SUMMARY = "Run canceled by user."
        private const val DEFAULT_INTERRUPTED_SUMMARY = "Run interrupted before completion."
        private val ACTIVE_STATUSES = setOf(
            RunStatus.RUNNING,
            RunStatus.CANCEL_REQUESTED,
        )
    }
}

data class ReconcileStaleActiveRunsResult(
    val canceledCount: Int,
    val interruptedCount: Int,
)
