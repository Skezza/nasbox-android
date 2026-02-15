package skezza.nasbox.domain.sync

object RunStatus {
    const val RUNNING = "RUNNING"
    const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
    const val CANCELED = "CANCELED"
    const val SUCCESS = "SUCCESS"
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"
}

object RunTriggerSource {
    const val MANUAL = "MANUAL"
    const val SCHEDULED = "SCHEDULED"
}
