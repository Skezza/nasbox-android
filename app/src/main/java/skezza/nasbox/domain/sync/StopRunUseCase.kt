package skezza.nasbox.domain.sync

import java.util.Locale
import skezza.nasbox.data.db.RunLogEntity
import skezza.nasbox.data.repository.RunLogRepository
import skezza.nasbox.data.repository.RunRepository

class StopRunUseCase(
    private val runRepository: RunRepository,
    private val runLogRepository: RunLogRepository,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    suspend operator fun invoke(runId: Long): StopRunResult {
        val run = runRepository.getRun(runId) ?: return StopRunResult.NotFound
        val normalizedStatus = run.status.trim().uppercase(Locale.US)
        return when (normalizedStatus) {
            RunStatus.CANCEL_REQUESTED,
            RunStatus.CANCELED,
            -> StopRunResult.AlreadyRequested
            RunStatus.RUNNING -> {
                val now = nowEpochMs()
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
                        runId = runId,
                        timestampEpochMs = now,
                        severity = "INFO",
                        message = "Run canceled by user",
                        detail = "User requested stop from dashboard.",
                    ),
                )
                StopRunResult.Requested
            }

            else -> StopRunResult.NotActive
        }
    }

    companion object {
        private const val DEFAULT_CANCELED_SUMMARY = "Run canceled by user."
    }
}

sealed interface StopRunResult {
    data object Requested : StopRunResult
    data object AlreadyRequested : StopRunResult
    data object NotActive : StopRunResult
    data object NotFound : StopRunResult
}
