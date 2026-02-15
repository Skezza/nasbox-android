package skezza.nasbox.ui.dashboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import skezza.nasbox.domain.sync.RunExecutionMode
import skezza.nasbox.domain.sync.RunPhase
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.ui.common.ErrorHint
import skezza.nasbox.ui.common.LoadState
import skezza.nasbox.ui.common.StateCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenAudit: () -> Unit,
    onOpenRunAudit: (Long) -> Unit,
    onOpenCurrentRunDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var pendingStopRunId by remember { mutableStateOf<Long?>(null) }

    pendingStopRunId?.let { runId ->
        AlertDialog(
            onDismissRequest = { pendingStopRunId = null },
            title = { Text("Stop run?") },
            text = { Text("This run will stop after the current item finishes uploading.") },
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

    Scaffold(
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
            if (state.loadState == LoadState.Loading) {
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
            item {
                VaultHealthCard(state = state)
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
                    onOpenAudit = onOpenAudit,
                    onOpenRunAudit = onOpenRunAudit,
                )
            }
        }
    }
}

@Composable
private fun VaultHealthCard(state: DashboardUiState) {
    val healthColor = when (state.vaultHealth.level) {
        VaultHealthLevel.HEALTHY -> MaterialTheme.colorScheme.primary
        VaultHealthLevel.NEEDS_TEST -> MaterialTheme.colorScheme.tertiary
        VaultHealthLevel.ATTENTION -> MaterialTheme.colorScheme.error
        VaultHealthLevel.NOT_CONFIGURED -> MaterialTheme.colorScheme.outline
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Backup health", style = MaterialTheme.typography.labelLarge)
            Text(
                text = state.vaultHealth.title,
                style = MaterialTheme.typography.titleMedium,
                color = healthColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = state.vaultHealth.detail, style = MaterialTheme.typography.bodyMedium)
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
            Text("Next scheduled run", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (nextRun == null) {
                Text(
                    "No scheduled jobs are enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Enable a schedule in Jobs to automate backups.",
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
                    val suffix = if (nextRun.additionalScheduledPlans == 1) "" else "s"
                    Text(
                        "+${nextRun.additionalScheduledPlans} more scheduled job$suffix",
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
            Text("Current runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                            "${run.planName} - ${currentPhaseLabel(run)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            modeBadge(run.triggerSource, run.executionMode),
                            style = MaterialTheme.typography.labelSmall,
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
                            "Scanned ${run.scannedCount}, uploaded ${run.uploadedCount}, skipped ${run.skippedCount}, failed ${run.failedCount}",
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
                                Text("Live detail")
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
    onOpenAudit: () -> Unit,
    onOpenRunAudit: (Long) -> Unit,
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${run.planName} - ${statusLabel(run.status)} (${modeBadge(run.triggerSource, run.executionMode)})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Uploaded ${run.uploadedCount}, skipped ${run.skippedCount}, failed ${run.failedCount}",
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
                        OutlinedButton(onClick = { onOpenRunAudit(run.runId) }) {
                            Text("Run story")
                        }
                    }
                }
            }
            ElevatedButton(onClick = onOpenAudit, modifier = Modifier.fillMaxWidth()) {
                Text("Open Audit")
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
                "Running (background)"
            } else {
                "Running (manual foreground)"
            }
        }
        else -> statusLabel(run.status)
    }
}

private fun modeBadge(
    triggerSource: String,
    executionMode: String,
): String {
    return "${triggerSource.lowercase(Locale.US)}/${executionMode.lowercase(Locale.US)}"
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
