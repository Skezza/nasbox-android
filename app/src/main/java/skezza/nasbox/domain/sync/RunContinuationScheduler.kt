package skezza.nasbox.domain.sync

interface RunContinuationScheduler {
    suspend fun enqueueContinuation(planId: Long, runId: Long, continuationCursor: String)
}
