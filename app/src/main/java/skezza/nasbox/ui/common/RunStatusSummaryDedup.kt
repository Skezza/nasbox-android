package skezza.nasbox.ui.common

import java.util.Locale
import skezza.nasbox.domain.sync.RunStatus

internal fun shouldHideEnumStatusLabel(
    status: String,
    statusLabel: String,
    summaryError: String?,
): Boolean {
    val normalizedSummary = normalizeStatusText(summaryError)
    if (normalizedSummary.isBlank()) return false

    val normalizedStatusLabel = normalizeStatusText(statusLabel)
    if (normalizedSummary == normalizedStatusLabel) return true

    return when (status.uppercase(Locale.US)) {
        RunStatus.CANCELED -> normalizedSummary == "canceled" ||
            normalizedSummary == "cancelled" ||
            normalizedSummary.startsWith("run canceled") ||
            normalizedSummary.startsWith("run cancelled") ||
            normalizedSummary.startsWith("canceled by") ||
            normalizedSummary.startsWith("cancelled by")
        RunStatus.INTERRUPTED -> normalizedSummary == "interrupted" ||
            normalizedSummary.startsWith("run interrupted")
        RunStatus.CANCEL_REQUESTED -> normalizedSummary == "cancel requested" ||
            normalizedSummary.startsWith("cancel requested") ||
            normalizedSummary.startsWith("run cancel requested")
        else -> false
    }
}

private fun normalizeStatusText(value: String?): String = value
    ?.trim()
    ?.lowercase(Locale.US)
    ?.replace(Regex("""[^a-z0-9\s]"""), " ")
    ?.replace(Regex("""\s+"""), " ")
    ?.trim()
    .orEmpty()
