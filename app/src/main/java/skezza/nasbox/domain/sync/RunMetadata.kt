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

object RunExecutionMode {
    const val FOREGROUND = "FOREGROUND"
    const val BACKGROUND = "BACKGROUND"
}

object RunPhase {
    const val RUNNING = "RUNNING"
    const val WAITING_RETRY = "WAITING_RETRY"
    const val FINISHING = "FINISHING"
    const val TERMINAL = "TERMINAL"
}
