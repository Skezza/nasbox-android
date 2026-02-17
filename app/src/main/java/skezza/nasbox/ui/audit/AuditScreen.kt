package skezza.nasbox.ui.audit

import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import skezza.nasbox.domain.sync.RunExecutionMode
import skezza.nasbox.domain.sync.RunPhase
import skezza.nasbox.domain.sync.RunStatus
import skezza.nasbox.ui.common.runTitleLabel
import skezza.nasbox.ui.common.shouldHideEnumStatusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(
    viewModel: AuditViewModel,
    onBack: () -> Unit,
    onOpenRun: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.listUiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterRow(
                selected = state.selectedFilter,
                onSelected = viewModel::setFilter,
            )

            if (state.runs.isEmpty()) {
                Text("No runs match that filter.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.runs, key = { it.runId }) { run ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRun(run.runId) },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val runStatusText = statusLabel(run.status, run.phase, run.executionMode)
                                val hideRunStatusText = shouldHideEnumStatusLabel(
                                    status = run.status,
                                    statusLabel = runStatusText,
                                    summaryError = run.summaryError,
                                )
                                Text(
                                    runTitleLabel(run.serverName, run.planName, run.triggerSource),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (!hideRunStatusText) {
                                    Text(
                                        runStatusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "Uploaded ${run.uploadedCount} · Skipped ${run.skippedCount} · Failed ${run.failedCount}",
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
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditRunDetailScreen(
    viewModel: AuditViewModel,
    runId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.detailUiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val rawLogText = remember(state.logs) { buildRawAuditLogText(state.logs) }

    LaunchedEffect(runId) {
        viewModel.selectRun(runId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run audit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.run != null && rawLogText.isNotBlank()) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(rawLogText))
                                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("Copy log")
                        }
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        val run = state.run
        if (run == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Text("Run not found")
            }
        } else {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    runTitleLabel(run.serverName, run.planName, run.triggerSource),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    statusLabel(run.status, run.phase, run.executionMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rawLogText.isBlank()) {
                    Text("No log events recorded.", style = MaterialTheme.typography.bodySmall)
                } else {
                    SelectionContainer {
                        Text(
                            text = rawLogText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

private fun buildRawAuditLogText(logs: List<AuditLogItem>): String =
    logs.sortedBy { it.timestampEpochMs }
        .joinToString(separator = "\n") { log ->
            buildString {
                append(formatTimestamp(log.timestampEpochMs))
                append("  ")
                append(log.severity)
                append("  ")
                append(log.message)
                val detail = log.detail?.trim().orEmpty()
                if (detail.isNotEmpty()) {
                    append('\n')
                    append(detail)
                }
            }
        }

@Composable
private fun FilterRow(
    selected: AuditFilter,
    onSelected: (AuditFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = selected == AuditFilter.ALL,
                onClick = { onSelected(AuditFilter.ALL) },
                label = { Text("All") },
            )
            FilterChip(
                selected = selected == AuditFilter.SUCCESS,
                onClick = { onSelected(AuditFilter.SUCCESS) },
                label = { Text("Success") },
            )
            FilterChip(
                selected = selected == AuditFilter.PARTIAL,
                onClick = { onSelected(AuditFilter.PARTIAL) },
                label = { Text("Partial") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = selected == AuditFilter.FAILED,
                onClick = { onSelected(AuditFilter.FAILED) },
                label = { Text("Failed") },
            )
            FilterChip(
                selected = selected == AuditFilter.INTERRUPTED,
                onClick = { onSelected(AuditFilter.INTERRUPTED) },
                label = { Text("Interrupted") },
            )
            FilterChip(
                selected = selected == AuditFilter.CANCELED,
                onClick = { onSelected(AuditFilter.CANCELED) },
                label = { Text("Canceled") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = selected == AuditFilter.RUNNING,
                onClick = { onSelected(AuditFilter.RUNNING) },
                label = { Text("Running") },
            )
        }
    }
}

private fun statusLabel(
    status: String,
    phase: String,
    executionMode: String,
): String {
    val normalizedPhase = phase.uppercase(Locale.US)
    val normalizedMode = executionMode.uppercase(Locale.US)
    if (status.uppercase(Locale.US) == RunStatus.RUNNING) {
        return when (normalizedPhase) {
            RunPhase.WAITING_RETRY -> "Waiting for background window"
            RunPhase.FINISHING -> "Finishing"
            else -> if (normalizedMode == RunExecutionMode.BACKGROUND) {
                "Running (scheduled)"
            } else {
                "Running (manual)"
            }
        }
    }
    return when (status.uppercase(Locale.US)) {
        RunStatus.SUCCESS -> "Success"
        RunStatus.PARTIAL -> "Partial"
        RunStatus.FAILED -> "Failed"
        RunStatus.CANCEL_REQUESTED -> "Cancel requested"
        RunStatus.CANCELED -> "Canceled"
        RunStatus.INTERRUPTED -> "Interrupted"
        else -> status
    }
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(epochMs))
