package skezza.smbsync.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val discoveryState by viewModel.discoveryState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscoveryDialog by remember { mutableStateOf(false) }

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
                    IconButton(onClick = {
                        showDiscoveryDialog = true
                        viewModel.discoverServers()
                    }) {
                        Icon(Icons.Default.TravelExplore, contentDescription = "Discover servers")
                    }
                    IconButton(onClick = onAddServer) {
                        Icon(Icons.Default.Add, contentDescription = "Add server")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        if (showDiscoveryDialog) {
            DiscoveryDialog(
                state = discoveryState,
                onDismiss = {
                    showDiscoveryDialog = false
                    viewModel.clearDiscoveryState()
                },
                onRefresh = viewModel::discoverServers,
                onUseServer = { discovered ->
                    viewModel.setDiscoverySelection(discovered)
                    showDiscoveryDialog = false
                    viewModel.clearDiscoveryState()
                    onAddServer()
                },
            )
        }

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
    val browserState by viewModel.browserState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showBrowserDialog by remember { mutableStateOf(false) }

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
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
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
                helperText = "Optional for root-level validation; can be in host as smb://host/share",
            ) {
                viewModel.updateEditorField(ServerEditorField.SHARE, it)
            }
            ServerField("Base path", state.basePath, state.validation.basePathError) {
                viewModel.updateEditorField(ServerEditorField.BASE_PATH, it)
            }
            ServerField("Username", state.username, state.validation.usernameError) {
                viewModel.updateEditorField(ServerEditorField.USERNAME, it)
            }
            ServerField(
                label = "Password",
                value = state.password,
                error = state.validation.passwordError,
                isPassword = true,
            ) {
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

            ElevatedButton(onClick = {
                showBrowserDialog = true
                viewModel.browseSharesFromEditor()
            }) {
                Text("Browse shares & folders")
            }
        }

        if (showBrowserDialog) {
            SmbBrowserDialog(
                browserState = browserState,
                editorState = state,
                onDismiss = {
                    showBrowserDialog = false
                    viewModel.clearBrowserState()
                },
                onRefreshShares = viewModel::browseSharesFromEditor,
                onSelectShare = { share -> viewModel.browseFoldersInShare(share, "") },
                onOpenFolder = { share, path -> viewModel.browseFoldersInShare(share, path) },
                onApplySelection = { share, path -> viewModel.applyBrowserSelection(share, path) },
            )
        }
    }
}

@Composable
private fun ServerField(
    label: String,
    value: String,
    error: String?,
    helperText: String? = null,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        supportingText = {
            when {
                error != null -> Text(error)
                !helperText.isNullOrBlank() -> Text(helperText)
            }
        },
    )
}

@Composable
private fun SmbBrowserDialog(
    browserState: SmbBrowserUiState,
    editorState: ServerEditorUiState,
    onDismiss: () -> Unit,
    onRefreshShares: () -> Unit,
    onSelectShare: (String) -> Unit,
    onOpenFolder: (String, String) -> Unit,
    onApplySelection: (String, String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a share home") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Explore your SMB space, then apply a share and optional folder to prefill this server.")
                if (browserState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (browserState.errorMessage != null) {
                    Text(browserState.errorMessage)
                }

                Text("Shares")
                LazyColumn(
                    modifier = Modifier.heightIn(max = 130.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(browserState.shares, key = { it }) { share ->
                        AssistChip(
                            onClick = { onSelectShare(share) },
                            label = { Text(if (share == browserState.selectedShare) "✓ $share" else share) },
                        )
                    }
                }

                if (browserState.selectedShare.isNotBlank()) {
                    HorizontalDivider()
                    Text("Folders in ${browserState.selectedShare}")
                    val pathSegments = browserState.selectedPath.split('/').filter { it.isNotBlank() }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { onOpenFolder(browserState.selectedShare, "") }, label = { Text("/") })
                        var cumulative = ""
                        pathSegments.forEach { segment ->
                            cumulative = if (cumulative.isBlank()) segment else "$cumulative/$segment"
                            AssistChip(
                                onClick = { onOpenFolder(browserState.selectedShare, cumulative) },
                                label = { Text(segment) },
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 170.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(browserState.folders, key = { it }) { folder ->
                            val nextPath = listOf(browserState.selectedPath, folder)
                                .filter { it.isNotBlank() }
                                .joinToString("/")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenFolder(browserState.selectedShare, nextPath) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("📁 $folder")
                                TextButton(onClick = { onApplySelection(browserState.selectedShare, nextPath) }) {
                                    Text("Use")
                                }
                            }
                        }
                    }
                }
                val selectedShare = browserState.selectedShare.ifBlank { editorState.shareName }
                val selectedPath = browserState.selectedPath.ifBlank { editorState.basePath }
                if (selectedShare.isNotBlank()) {
                    Text("Will use: //${editorState.host}/$selectedShare/$selectedPath")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val share = browserState.selectedShare.ifBlank { editorState.shareName }
                    if (share.isNotBlank()) {
                        onApplySelection(share, browserState.selectedPath)
                    }
                    onDismiss()
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefreshShares, enabled = !browserState.isLoading) {
                    Text("Refresh")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}

@Composable
private fun DiscoveryDialog(
    state: DiscoveryUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onUseServer: (skezza.smbsync.data.discovery.DiscoveredSmbServer) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discover SMB Servers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scanning local Wi-Fi subnet for devices with SMB port 445 open.")
                if (state.isScanning) {
                    Text("Scanning...")
                }
                if (state.errorMessage != null) {
                    Text(state.errorMessage)
                }
                if (!state.isScanning && state.servers.isEmpty() && state.errorMessage == null) {
                    Text("No SMB servers discovered.")
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.servers, key = { it.ipAddress }) { server ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUseServer(server) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                if (server.host.equals(server.ipAddress, ignoreCase = true)) {
                                    Text(server.ipAddress)
                                } else {
                                    Text(server.host)
                                    Text(server.ipAddress)
                                }
                            }
                            TextButton(onClick = { onUseServer(server) }) {
                                Text("Use")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !state.isScanning) {
                Text(if (state.isScanning) "Scanning" else "Scan again")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
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
