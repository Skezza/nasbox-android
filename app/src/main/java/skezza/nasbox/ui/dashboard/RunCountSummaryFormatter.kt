package skezza.nasbox.ui.dashboard

import java.util.Locale
import skezza.nasbox.domain.sync.RunPhase

internal fun runCountSummaryText(
    scannedCount: Int,
    uploadedCount: Int,
    skippedCount: Int,
    failedCount: Int,
    phase: String? = null,
    includeScanned: Boolean = true,
): String {
    if (phase?.uppercase(Locale.US) == RunPhase.VERIFYING) {
        val total = scannedCount.coerceAtLeast(0)
        val processed = (uploadedCount + failedCount).coerceAtMost(total)
        return if (includeScanned) {
            "Verifying $processed/$total · Failed $failedCount"
        } else {
            "Verified $uploadedCount · Failed $failedCount"
        }
    }
    val processedSummary = "Uploaded $uploadedCount · Skipped $skippedCount · Failed $failedCount"
    return if (includeScanned) {
        "Scanned $scannedCount · $processedSummary"
    } else {
        processedSummary
    }
}
