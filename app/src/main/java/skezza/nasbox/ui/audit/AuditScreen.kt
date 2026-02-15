package skezza.nasbox.ui.audit

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import skezza.nasbox.domain.sync.RunStatus

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
                Text("No runs match this filter.")
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
                                Text(
                                    "${run.planName} - ${statusLabel(run.status)} (${run.triggerSource.lowercase()})",
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
    val expandedLogs = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(runId) {
        viewModel.selectRun(runId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run Detail") },
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "${run.planName} - ${statusLabel(run.status)} (${run.triggerSource.lowercase()})",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Uploaded ${run.uploadedCount}, skipped ${run.skippedCount}, failed ${run.failedCount}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Started ${formatTimestamp(run.startedAtEpochMs)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            run.finishedAtEpochMs?.let {
                                Text("Finished ${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall)
                            }
                            run.summaryError?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (state.logs.isEmpty()) {
                    item { Text("No log events recorded.") }
                } else {
                    items(state.logs, key = { it.logId }) { log ->
                        val expanded = expandedLogs[log.logId] == true
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedLogs[log.logId] = !expanded
                                },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        formatTimestamp(log.timestampEpochMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val severityColor = if (log.severity.equals("ERROR", ignoreCase = true)) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(log.severity, style = MaterialTheme.typography.labelSmall, color = severityColor)
                                }
                                Text(log.message, style = MaterialTheme.typography.bodyMedium)
                                val detail = log.detail?.trim().orEmpty()
                                if (detail.isNotEmpty()) {
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
                selected = selected == AuditFilter.RUNNING,
                onClick = { onSelected(AuditFilter.RUNNING) },
                label = { Text("Running") },
            )
        }
    }
}

private fun statusLabel(status: String): String = when (status.uppercase(Locale.US)) {
    RunStatus.SUCCESS -> "Success"
    RunStatus.PARTIAL -> "Partial"
    RunStatus.FAILED -> "Failed"
    RunStatus.RUNNING -> "Running"
    RunStatus.INTERRUPTED -> "Interrupted"
    else -> status
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(epochMs))
