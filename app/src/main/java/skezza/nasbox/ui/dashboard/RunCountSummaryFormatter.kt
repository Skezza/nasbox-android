package skezza.nasbox.ui.dashboard

internal fun runCountSummaryText(
    scannedCount: Int,
    uploadedCount: Int,
    skippedCount: Int,
    failedCount: Int,
    includeScanned: Boolean = true,
): String {
    val processedSummary = "Uploaded $uploadedCount · Skipped $skippedCount · Failed $failedCount"
    return if (includeScanned) {
        "Scanned $scannedCount · $processedSummary"
    } else {
        processedSummary
    }
}
