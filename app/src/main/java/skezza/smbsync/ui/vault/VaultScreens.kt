package skezza.smbsync.ui.vault

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onAddServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val servers by viewModel.servers.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                actions = {
                    IconButton(onClick = onAddServer) {
                        Icon(Icons.Default.Add, contentDescription = "Add server")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No servers yet. Add one to start building your vault.")
                ElevatedButton(onClick = onAddServer, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add server", modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.serverId }) { server ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditServer(server.serverId) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(server.name)
                            Text(server.endpoint)
                            Text("Base: ${server.basePath}")
                            Text(server.connectionStatusLabel())
                            if (server.lastTestStatus == "FAILED" && !server.lastTestErrorMessage.isNullOrBlank()) {
                                Text("Last error: ${server.lastTestErrorMessage}")
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Row {
                                    IconButton(onClick = { onEditServer(server.serverId) }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit server")
                                    }
                                    IconButton(onClick = { viewModel.deleteServer(server.serverId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete server")
                                    }
                                }
                                ElevatedButton(
                                    onClick = { viewModel.testServerConnection(server.serverId) },
                                    enabled = !server.isTesting,
                                ) {
                                    Icon(Icons.Default.NetworkCheck, contentDescription = null)
                                    Text(
                                        if (server.isTesting) "Testing..." else "Test",
                                        modifier = Modifier.padding(start = 8.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(
    viewModel: VaultViewModel,
    serverId: Long?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.editorState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(serverId) {
        viewModel.loadServerForEdit(serverId)
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (serverId == null) "Add Server" else "Edit Server") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ServerField("Name", state.name, state.validation.nameError) {
                viewModel.updateEditorField(ServerEditorField.NAME, it)
            }
            ServerField(
                label = "Host",
                value = state.host,
                error = state.validation.hostError,
                helperText = "Examples: quanta.local or smb://quanta.local/photos",
            ) {
                viewModel.updateEditorField(ServerEditorField.HOST, it)
            }
            ServerField(
                label = "Share",
                value = state.shareName,
                error = state.validation.shareNameError,
                helperText = "Can be left blank if host is smb://host/share",
            ) {
                viewModel.updateEditorField(ServerEditorField.SHARE, it)
            }
            ServerField("Base path", state.basePath, state.validation.basePathError) {
                viewModel.updateEditorField(ServerEditorField.BASE_PATH, it)
            }
            ServerField("Username", state.username, state.validation.usernameError) {
                viewModel.updateEditorField(ServerEditorField.USERNAME, it)
            }
            ServerField("Password", state.password, state.validation.passwordError) {
                viewModel.updateEditorField(ServerEditorField.PASSWORD, it)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveServer(onNavigateBack) }) {
                    Text(if (serverId == null) "Create server" else "Save changes")
                }
                ElevatedButton(
                    onClick = viewModel::testEditorConnection,
                    enabled = !state.isTestingConnection,
                ) {
                    Text(if (state.isTestingConnection) "Testing..." else "Test connection")
                }
            }
        }
    }
}

@Composable
private fun ServerField(
    label: String,
    value: String,
    error: String?,
    helperText: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        modifier = Modifier.fillMaxWidth(),
        supportingText = {
            when {
                error != null -> Text(error)
                !helperText.isNullOrBlank() -> Text(helperText)
            }
        },
    )
}

private fun ServerListItemUiState.connectionStatusLabel(): String {
    return when (lastTestStatus) {
        "SUCCESS" -> "Connection: Passed${lastTestLatencyMs?.let { " (${it}ms)" } ?: ""}"
        "FAILED" -> "Connection: Failed"
        else -> "Connection: Not tested"
    }
}
