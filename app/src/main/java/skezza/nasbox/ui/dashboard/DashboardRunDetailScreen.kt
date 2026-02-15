package skezza.nasbox.ui.dashboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardRunDetailScreen(
    viewModel: DashboardRunDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var showAllUploaded by rememberSaveable { mutableStateOf(false) }
    var showTechnicalLogs by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run story") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val run = state.run
            if (run == null) {
                item { Text("Run not found.") }
            } else {
                item {
                    StorySummaryCard(
                        run = run,
                        currentFileLabel = state.currentFileLabel,
                        isActive = state.isActive,
                        lastAction = state.lastAction,
                    )
                }

                if (state.milestones.isNotEmpty()) {
                    item {
                        MilestonesCard(milestones = state.milestones)
                    }
                }

                item {
                    FileActivityCard(
                        activities = state.fileActivities,
                        showAllUploaded = showAllUploaded,
                        onToggleShowAllUploaded = { showAllUploaded = !showAllUploaded },
                    )
                }

                item {
                    TechnicalLogCard(
                        logs = state.rawLogs,
                        expanded = showTechnicalLogs,
                        onToggleExpanded = { showTechnicalLogs = !showTechnicalLogs },
                    )
                }
            }
        }
    }
}

@Composable
private fun StorySummaryCard(
    run: DashboardRunDetailSummary,
    currentFileLabel: String?,
    isActive: Boolean,
    lastAction: DashboardRunDetailLastAction?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "${run.planName} - ${runStatusLabel(run.status)} (${run.triggerSource.lowercase()})",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val processed = run.uploadedCount + run.skippedCount + run.failedCount
            val progress = if (run.scannedCount > 0) {
                (processed.toFloat() / run.scannedCount.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
            if (isActive) {
                Text(
                    "Current file: ${currentFileLabel ?: "Waiting for next file..."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lastAction?.let {
                Text(
                    "Last action: ${it.label} at ${formatTimestamp(it.timestampEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            run.summaryError?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MilestonesCard(milestones: List<DashboardRunDetailMilestone>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Milestones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            milestones.forEach { milestone ->
                Text(
                    "• ${milestone.label} - ${formatTimestamp(milestone.timestampEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FileActivityCard(
    activities: List<DashboardRunFileActivity>,
    showAllUploaded: Boolean,
    onToggleShowAllUploaded: () -> Unit,
) {
    val uploaded = activities.filter { it.status == DashboardRunFileStatus.UPLOADED }
    val nonUploaded = activities.filter { it.status != DashboardRunFileStatus.UPLOADED }
    val visibleUploaded = if (showAllUploaded) uploaded else uploaded.take(DEFAULT_VISIBLE_UPLOADED)
    val visibleRows = (nonUploaded + visibleUploaded).sortedByDescending { it.timestampEpochMs }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("File activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (visibleRows.isEmpty()) {
                Text("No file-level activity recorded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                visibleRows.forEach { activity ->
                    FileActivityRow(activity = activity)
                }
            }
            if (uploaded.size > DEFAULT_VISIBLE_UPLOADED) {
                OutlinedButton(
                    onClick = onToggleShowAllUploaded,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (showAllUploaded) {
                        Text("Show fewer uploaded files")
                    } else {
                        Text("Show all uploaded files (${uploaded.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun FileActivityRow(activity: DashboardRunFileActivity) {
    val statusColor = when (activity.status) {
        DashboardRunFileStatus.PROCESSING -> MaterialTheme.colorScheme.tertiary
        DashboardRunFileStatus.UPLOADED -> MaterialTheme.colorScheme.primary
        DashboardRunFileStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        DashboardRunFileStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    val statusText = when (activity.status) {
        DashboardRunFileStatus.PROCESSING -> "In progress"
        DashboardRunFileStatus.UPLOADED -> "Uploaded"
        DashboardRunFileStatus.SKIPPED -> "Skipped"
        DashboardRunFileStatus.FAILED -> "Failed"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(activity.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Text(
                formatTimestamp(activity.timestampEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            activity.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TechnicalLogCard(
    logs: List<DashboardRunDetailLogItem>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Technical log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onToggleExpanded) {
                    Text(if (expanded) "Hide raw events" else "Show raw events")
                }
            }
            if (!expanded) {
                Text(
                    "Raw event stream is hidden to keep this view focused.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (logs.isEmpty()) {
                Text("No raw events recorded.", style = MaterialTheme.typography.bodySmall)
            } else {
                logs.take(MAX_RAW_LOG_ROWS).forEach { log ->
                    val severityColor = if (log.severity.equals("ERROR", ignoreCase = true)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        "${formatTimestamp(log.timestampEpochMs)}  ${log.severity}",
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor,
                    )
                    Text(log.message, style = MaterialTheme.typography.bodySmall)
                    log.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (logs.size > MAX_RAW_LOG_ROWS) {
                    Text(
                        "Showing latest $MAX_RAW_LOG_ROWS raw events.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(epochMs))

private const val DEFAULT_VISIBLE_UPLOADED = 5
private const val MAX_RAW_LOG_ROWS = 120
