package skezza.nasbox.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class RunCountSummaryFormatterTest {

    @Test
    fun includesScannedWhenRequested() {
        val summary = runCountSummaryText(
            scannedCount = 12,
            uploadedCount = 8,
            skippedCount = 3,
            failedCount = 1,
            includeScanned = true,
        )

        assertEquals("Scanned 12 · Uploaded 8 · Skipped 3 · Failed 1", summary)
    }

    @Test
    fun omitsScannedWhenRequested() {
        val summary = runCountSummaryText(
            scannedCount = 12,
            uploadedCount = 8,
            skippedCount = 3,
            failedCount = 1,
            includeScanned = false,
        )

        assertEquals("Uploaded 8 · Skipped 3 · Failed 1", summary)
    }
}
