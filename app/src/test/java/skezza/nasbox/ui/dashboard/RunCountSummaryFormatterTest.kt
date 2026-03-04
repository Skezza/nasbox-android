package skezza.nasbox.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import skezza.nasbox.domain.sync.RunPhase

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

    @Test
    fun formatsVerificationProgressWhenVerifying() {
        val summary = runCountSummaryText(
            scannedCount = 12,
            uploadedCount = 8,
            skippedCount = 0,
            failedCount = 1,
            phase = RunPhase.VERIFYING,
            includeScanned = true,
        )

        assertEquals("Verifying 9/12 · Failed 1", summary)
    }
}
