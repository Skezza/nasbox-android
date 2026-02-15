package skezza.nasbox.domain.sync

import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository

class MarkRunInterruptedUseCase(
    private val runRepository: RunRepository,
    private val runLogRepository: RunLogRepository,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    suspend operator fun invoke(runId: Long): Boolean {
        val run = runRepository.getRun(runId) ?: return false
        if (!run.status.equals(RunStatus.RUNNING, ignoreCase = true)) {
            return false
        }

        val now = nowEpochMs()
        val summary = run.summaryError ?: DEFAULT_SUMMARY
        runRepository.updateRun(
            run.copy(
                status = RunStatus.INTERRUPTED,
                finishedAtEpochMs = now,
                heartbeatAtEpochMs = now,
                summaryError = summary,
                phase = RunPhase.TERMINAL,
                continuationCursor = null,
                lastProgressAtEpochMs = maxOf(run.lastProgressAtEpochMs, now),
            ),
        )
        runLogRepository.createLog(
            RunLogEntity(
                runId = runId,
                timestampEpochMs = now,
                severity = "ERROR",
                message = "Run marked as interrupted",
                detail = "No heartbeat received for an extended period.",
            ),
        )
        return true
    }

    companion object {
        private const val DEFAULT_SUMMARY = "Run interrupted before completion."
    }
}
