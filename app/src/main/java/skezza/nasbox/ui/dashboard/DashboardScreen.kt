package skezza.nasbox.ui.dashboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import skezza.nasbox.domain.sync.RunStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenAudit: () -> Unit,
    onOpenRunAudit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

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
            item {
                VaultHealthCard(state = state)
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
            Text("Vault health", style = MaterialTheme.typography.labelLarge)
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
                Text("No runs yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                runs.forEach { run ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        run.summaryError?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = { onOpenRunAudit(run.runId) }) {
                            Text("View run")
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
    RunStatus.INTERRUPTED -> "Interrupted"
    else -> status
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(epochMs))
