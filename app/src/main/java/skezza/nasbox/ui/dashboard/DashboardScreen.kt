package skezza.nasbox.ui.dashboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import skezza.nasbox.domain.sync.RunExecutionMode
import skezza.nasbox.domain.sync.RunPhase
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.ui.common.ErrorHint
import skezza.nasbox.ui.common.LoadState
import skezza.nasbox.ui.common.StateCard
import skezza.nasbox.ui.common.runTitleLabel
import skezza.nasbox.ui.common.shouldHideEnumStatusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenAudit: () -> Unit,
    onOpenRunDetail: (Long) -> Unit,
    onClearRecentRun: (Long) -> Unit,
    onClearAllRecentRuns: () -> Unit,
    onOpenCurrentRunDetail: (Long) -> Unit,
    onRestartRun: (Long, Long, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var pendingStopRunId by remember { mutableStateOf<Long?>(null) }

        pendingStopRunId?.let { runId ->
            AlertDialog(
                onDismissRequest = { pendingStopRunId = null },
                title = { Text("Stop run?") },
                text = { Text("Run stops after the current upload completes.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.requestStopRun(runId)
                        pendingStopRunId = null
                    },
                ) {
                    Text("Stop run")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStopRunId = null }) {
                    Text("Keep running")
                }
            },
        )
    }

    val isInitialLoading = state.loadState == LoadState.Loading && !state.hasLoadedData
    var showLoadingCard by remember { mutableStateOf(false) }

    LaunchedEffect(isInitialLoading) {
        if (!isInitialLoading) {
            showLoadingCard = false
            return@LaunchedEffect
        }
        showLoadingCard = false
        delay(250)
        if (isInitialLoading) {
            showLoadingCard = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showLoadingCard) {
                item {
                    StateCard(
                        title = "Loading dashboard",
                        description = "Checking vault health and recent runs.",
                        progressContent = {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        },
                    )
                }
            }
            val errorMessage = state.errorMessage
            if (state.loadState is LoadState.Error && errorMessage != null) {
                item {
                    ErrorHint(message = errorMessage)
                }
            }
            if (state.hasLoadedData) {
                item {
                    RunHealthCard(
                        runHealth = state.runHealth,
                        onOpenRunDetail = onOpenRunDetail,
                        onRestartRun = onRestartRun,
                    )
                }
                if (state.currentRuns.isNotEmpty()) {
                    item {
                        CurrentRunsCard(
                            runs = state.currentRuns,
                            stoppingRunIds = state.stoppingRunIds,
                            onOpenCurrentRunDetail = onOpenCurrentRunDetail,
                            onRequestStop = { runId -> pendingStopRunId = runId },
                        )
                    }
                } else {
                    item {
                        NextScheduledRunCard(nextRun = state.nextScheduledRun)
                    }
                }
                item {
                    RecentRunsCard(
                        runs = state.recentRuns,
                        onOpenRunDetail = onOpenRunDetail,
                        onClearRecentRun = onClearRecentRun,
                        onClearAllRecentRuns = onClearAllRecentRuns,
                        onOpenAudit = onOpenAudit,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunHealthCard(
    runHealth: DashboardRunHealth,
    onOpenRunDetail: (Long) -> Unit,
    onRestartRun: (Long, Long, String?) -> Unit,
) {
    val headlineColor = when (runHealth.level) {
        RunHealthLevel.CRITICAL -> MaterialTheme.colorScheme.error
        RunHealthLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        RunHealthLevel.HEALTHY -> MaterialTheme.colorScheme.primary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Backup Health", style = MaterialTheme.typography.labelLarge)
            Text(
                runHealth.title,
                style = MaterialTheme.typography.titleMedium,
                color = headlineColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                runHealth.detail,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (runHealth.issues.isEmpty()) {
                runHealth.lastSuccessAtEpochMs?.let {
                    Text(
                        "Last success ${formatRelativePast(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    runHealth.issues.forEach { issue ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val issueStatusText = statusLabel(issue.status)
                            val hideIssueStatusText = shouldHideEnumStatusLabel(
                                status = issue.status,
                                statusLabel = issueStatusText,
                                summaryError = issue.summaryError,
                            )
                            val failureSummary = if (issue.streakLength == 1) {
                                "1 failure"
                            } else {
                                "${issue.streakLength} consecutive failures"
                            }
                            Text(
                                if (hideIssueStatusText) {
                                    "${issue.planName} • $failureSummary"
                                } else {
                                    "${issue.planName} - $issueStatusText • $failureSummary"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Last update ${formatRelativePast(issue.lastUpdatedAtEpochMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            issue.summaryError?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onOpenRunDetail(issue.runId) }) {
                                    Text("View run")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onRestartRun(issue.planId, issue.runId, issue.continuationCursor)
                                    },
                                ) {
                                    Text("Restart")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextScheduledRunCard(nextRun: DashboardNextScheduledRun?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Next scheduled job", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (nextRun == null) {
                Text(
                    "No schedules are enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Enable a schedule to automate backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    nextRun.planName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatTimestamp(nextRun.nextRunAtEpochMs)} (${formatRelativeFuture(nextRun.nextRunAtEpochMs)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    nextRun.scheduleSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nextRun.additionalScheduledPlans > 0) {
                    Text(
                        "+${nextRun.additionalScheduledPlans} more scheduled job(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentRunsCard(
    runs: List<DashboardCurrentRun>,
    stoppingRunIds: Set<Long>,
    onOpenCurrentRunDetail: (Long) -> Unit,
    onRequestStop: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Active runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            runs.forEach { run ->
                val processed = run.uploadedCount + run.skippedCount + run.failedCount
                val progress = if (run.scannedCount > 0) {
                    (processed.toFloat() / run.scannedCount.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                val isStopping = run.runId in stoppingRunIds
                val cancelRequested = run.status.uppercase(Locale.US) == RunStatus.CANCEL_REQUESTED
                val stopEnabled = !isStopping && !cancelRequested
                val stopLabel = when {
                    isStopping -> "Requesting stop..."
                    cancelRequested -> "Stop requested"
                    else -> "Stop run"
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCurrentRunDetail(run.runId) },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            runTitleLabel(null, run.planName, run.triggerSource),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            currentPhaseLabel(run),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress == null) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                gapSize = 0.dp,
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                        }
                        Text(
                            runCountSummaryText(
                                scannedCount = run.scannedCount,
                                uploadedCount = run.uploadedCount,
                                skippedCount = run.skippedCount,
                                failedCount = run.failedCount,
                                includeScanned = false,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Started ${formatTimestamp(run.startedAtEpochMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        run.summaryError?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = stopEnabled,
                                onClick = { onRequestStop(run.runId) },
                            ) {
                                Text(stopLabel)
                            }
                            OutlinedButton(onClick = { onOpenCurrentRunDetail(run.runId) }) {
                                Text("Run details")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRunsCard(
    runs: List<DashboardRecentRun>,
    onOpenRunDetail: (Long) -> Unit,
    onClearRecentRun: (Long) -> Unit,
    onClearAllRecentRuns: () -> Unit,
    onOpenAudit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Recent runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (runs.isEmpty()) {
                Text("No completed runs yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                runs.forEach { run ->
                    val statusText = statusLabel(run.status)
                    val hideStatusText = shouldHideEnumStatusLabel(
                        status = run.status,
                        statusLabel = statusText,
                        summaryError = run.summaryError,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            runTitleLabel(null, run.planName, run.triggerSource),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!hideStatusText) {
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            runCountSummaryText(
                                scannedCount = 0,
                                uploadedCount = run.uploadedCount,
                                skippedCount = run.skippedCount,
                                failedCount = run.failedCount,
                                includeScanned = false,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Started ${formatTimestamp(run.startedAtEpochMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        run.finishedAtEpochMs?.let {
                            Text(
                                "Finished ${formatTimestamp(it)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        run.summaryError?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { onOpenRunDetail(run.runId) }) {
                                Text("View run")
                            }
                            OutlinedButton(onClick = { onClearRecentRun(run.runId) }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ElevatedButton(
                    onClick = onOpenAudit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Audit")
                }
                OutlinedButton(
                    onClick = onClearAllRecentRuns,
                    modifier = Modifier.weight(1f),
                    enabled = runs.isNotEmpty(),
                ) {
                    Text("Clear all")
                }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status.uppercase(Locale.US)) {
    RunStatus.SUCCESS -> "Success"
    RunStatus.PARTIAL -> "Partial"
    RunStatus.FAILED -> "Failed"
    RunStatus.RUNNING -> "Running"
    RunStatus.CANCEL_REQUESTED -> "Cancel requested"
    RunStatus.CANCELED -> "Canceled"
    RunStatus.INTERRUPTED -> "Interrupted"
    else -> status
}

private fun currentPhaseLabel(run: DashboardCurrentRun): String {
    val phase = run.phase.uppercase(Locale.US)
    val executionMode = run.executionMode.uppercase(Locale.US)
    return when (phase) {
        RunPhase.WAITING_RETRY -> "Waiting for background window"
        RunPhase.FINISHING -> "Finishing"
        RunPhase.RUNNING -> {
            if (executionMode == RunExecutionMode.BACKGROUND) {
                "Running (scheduled)"
            } else {
                "Running (manual)"
            }
        }
        else -> statusLabel(run.status)
    }
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(epochMs))

private fun formatRelativeFuture(
    targetEpochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
): String {
    val remainingMs = targetEpochMs - nowEpochMs
    if (remainingMs <= 0L) return "now"
    val totalMinutes = remainingMs / (60L * 1000L)
    if (totalMinutes == 0L) return "in <1m"
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> "in ${days}d ${hours}h"
        hours > 0L -> "in ${hours}h ${minutes}m"
        else -> "in ${minutes}m"
    }
}

private fun formatRelativePast(
    targetEpochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
): String {
    val elapsedMs = nowEpochMs - targetEpochMs
    if (elapsedMs <= 0L) return "just now"
    val totalMinutes = elapsedMs / (60L * 1000L)
    if (totalMinutes == 0L) return "just now"
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> "${days}d ${hours}h ago"
        hours > 0L -> "${hours}h ${minutes}m ago"
        else -> "${minutes}m ago"
    }
}
