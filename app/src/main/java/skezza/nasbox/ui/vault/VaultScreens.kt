package skezza.nasbox.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

import skezza.nasbox.ui.common.ErrorHint
import skezza.nasbox.ui.common.LoadState
import skezza.nasbox.ui.common.StateCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onAddServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val serverListState by viewModel.serverListUiState.collectAsState()
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text("Servers") },
                actions = {
                    IconButton(
                        modifier = Modifier.size(52.dp),
                        onClick = onAddServer,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add server")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    showDiscoveryDialog = true
                    viewModel.discoverServers()
                },
            ) {
                Icon(Icons.Default.TravelExplore, contentDescription = "Discover servers")
            }
        },
        floatingActionButtonPosition = FabPosition.End,
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

        when {
            serverListState.loadState == LoadState.Loading -> {
                StateCard(
                    title = "Loading servers",
                    description = "Checking configured destinations and discovery status.",
                    progressContent = {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    },
                )
            }
            serverListState.loadState is LoadState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    ErrorHint(
                        message = serverListState.errorMessage
                            ?: "Unable to load servers. Check SMB credentials or Wi-Fi, then reopen.",
                    )
                    StateCard(
                        title = "Servers unavailable",
                        description = "Verify SMB credentials or Wi-Fi, then reopen.",
                    )
                }
            }
            serverListState.servers.isEmpty() -> {
                Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp)
                    .offset(y = (-48).dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    StateCard(
                        title = "No servers yet",
                        description = "Add your first server or share, or press Discover in the bottom right to scan for SMB hosts.",
                        actionLabel = "Add server",
                        onAction = onAddServer,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(serverListState.servers, key = { it.serverId }) { server ->
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
                                Text("Share: ${server.endpoint}")
                                Text("Path: ${server.basePath.ifBlank { "/" }}")
                                Text(server.onlineStatusLabel())
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
                                        Text("Test", modifier = Modifier.padding(start = 8.dp),
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
    val browseState by viewModel.browseState.collectAsState()
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

    if (browseState.isVisible) {
        BrowseDestinationDialog(
            state = browseState,
            onDismiss = viewModel::closeBrowseDestination,
            onRefresh = viewModel::refreshBrowseDestination,
            onSelectShare = viewModel::selectBrowseShare,
            onOpenDirectory = viewModel::openBrowseDirectory,
            onNavigateBreadcrumb = viewModel::navigateBrowseBreadcrumb,
            onUseLocation = viewModel::applyBrowseSelection,
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (serverId == null) "Add server" else "Edit server") },
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ServerField(
                fieldName = "Name",
                value = state.name,
                error = state.validation.nameError,
                helperText = "Name (e.g., Home NAS).",
            ) {
                viewModel.updateEditorField(ServerEditorField.NAME, it)
            }
            ServerField(
                fieldName = "Host",
                value = state.host,
                error = state.validation.hostError,
                helperText = "Host (e.g. smb://example.local).",
            ) {
                viewModel.updateEditorField(ServerEditorField.HOST, it)
            }
            ServerField(
                fieldName = "Share",
                value = state.shareName,
                error = state.validation.shareNameError,
                helperText = "Share (smb://host/{share}).",
            ) {
                viewModel.updateEditorField(ServerEditorField.SHARE, it)
            }
            ServerField(
                fieldName = "Base path",
                value = state.basePath,
                error = state.validation.basePathError,
                helperText = "Base path (optional subfolder).",
            ) {
                viewModel.updateEditorField(ServerEditorField.BASE_PATH, it)
            }
            ElevatedButton(
                onClick = viewModel::openBrowseDestination,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.TravelExplore, contentDescription = null)
                Text("Browse share and folder", modifier = Modifier.padding(start = 8.dp))
            }
            ServerField(
                fieldName = "Domain / workgroup",
                value = state.domain,
                error = null,
                helperText = "Domain (optional, e.g., WORKGROUP).",
            ) {
                viewModel.updateEditorField(ServerEditorField.DOMAIN, it)
            }
            ServerField(
                fieldName = "Username",
                value = state.username,
                error = state.validation.usernameError,
                helperText = "Username (required for authenticated access).",
            ) {
                viewModel.updateEditorField(ServerEditorField.USERNAME, it)
            }
            ServerField(
                fieldName = "Password",
                value = state.password,
                error = state.validation.passwordError,
                isPassword = true,
                helperText = "Password (leave blank for guest access).",
            ) {
                viewModel.updateEditorField(ServerEditorField.PASSWORD, it)
            }

            Spacer(modifier = Modifier.weight(1f, fill = true))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveServer(onNavigateBack) }) {
                    Text(if (serverId == null) "Create server" else "Save server")
                }
                ElevatedButton(
                    onClick = viewModel::testEditorConnection,
                    enabled = !state.isTestingConnection,
                ) {
                    Text(if (state.isTestingConnection) "Testing..." else "Test connection")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ServerField(
    fieldName: String,
    value: String,
    error: String?,
    helperText: String? = null,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(fieldName) },
        isError = error != null,
        singleLine = true,
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
private fun DiscoveryDialog(
    state: DiscoveryUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onUseServer: (skezza.nasbox.data.discovery.DiscoveredSmbServer) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discover SMB servers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isScanning && state.servers.isEmpty()) {
                    Text("Scanning...")
                }
                if (state.errorMessage != null) {
                    Text(state.errorMessage)
                }
                if (!state.isScanning && state.servers.isEmpty() && state.errorMessage == null) {
                    Text("No SMB servers found.")
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.servers, key = { it.ipAddress }) { server ->
                        val displayHost = server.host.lowercase()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUseServer(server) }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                if (displayHost.equals(server.ipAddress, ignoreCase = true)) {
                                    Text(server.ipAddress, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    Text(displayHost, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(server.ipAddress, maxLines = 1, overflow = TextOverflow.Ellipsis)
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


@Composable
private fun BrowseDestinationDialog(
    state: SmbBrowseUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelectShare: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateBreadcrumb: (Int) -> Unit,
    onUseLocation: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browse SMB destination") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                if (state.selectedShare.isBlank()) {
                    Text("Select a share")
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BreadcrumbButton(label = "/", onClick = { onNavigateBreadcrumb(-1) })
                        state.breadcrumbs.forEachIndexed { index, segment ->
                            BreadcrumbButton(
                                label = if (segment.isBlank()) state.selectedShare else segment,
                                onClick = { onNavigateBreadcrumb(index) },
                            )
                        }
                    }
                }
                val shouldShowLoadingLabel = state.isLoading && when {
                    state.selectedShare.isBlank() -> state.shares.isEmpty()
                    else -> state.directories.isEmpty()
                }
                var showLoadingLabel by remember { mutableStateOf(false) }
                LaunchedEffect(shouldShowLoadingLabel) {
                    if (!shouldShowLoadingLabel) {
                        showLoadingLabel = false
                        return@LaunchedEffect
                    }
                    delay(200)
                    if (shouldShowLoadingLabel) {
                        showLoadingLabel = true
                    }
                }
                if (showLoadingLabel) {
                    Text("Loading...")
                }
                if (!state.errorMessage.isNullOrBlank()) {
                    Text(state.errorMessage)
                }
                if (!state.isLoading && state.errorMessage.isNullOrBlank() && state.selectedShare.isBlank() && state.shares.isEmpty()) {
                    Text("No shares returned yet. Tap Refresh.")
                }
                if (!state.isLoading && state.errorMessage.isNullOrBlank() && state.selectedShare.isNotBlank() && state.directories.isEmpty()) {
                    Text("No folders found here.")
                }
                val browseEntries = if (state.selectedShare.isBlank()) {
                    state.shares.map { BrowseListItem.Share(it) }
                } else {
                    val pathSegments = state.currentPath.split('/').filter { it.isNotBlank() }
                    val breadcrumbIndex = if (pathSegments.isEmpty()) -1 else pathSegments.size - 1
                    buildList<BrowseListItem> {
                        add(BrowseListItem.UpDirectory(breadcrumbIndex))
                        addAll(state.directories.map { BrowseListItem.Directory(it) })
                    }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(browseEntries, key = { it.key }) { entry ->
                        when (entry) {
                            is BrowseListItem.Share -> {
                                BrowseListEntryRow(
                                    label = entry.name,
                                    onRowClick = { onSelectShare(entry.name) },
                                    onActionClick = { onSelectShare(entry.name) },
                                )
                            }
                            is BrowseListItem.Directory -> {
                                BrowseListEntryRow(
                                    label = entry.name,
                                    onRowClick = { onOpenDirectory(entry.name) },
                                    onActionClick = { onOpenDirectory(entry.name) },
                                )
                            }
                            is BrowseListItem.UpDirectory -> {
                                BrowseListEntryRow(
                                    label = "/",
                                    modifier = Modifier.semantics { contentDescription = "Up Directory" },
                                    onRowClick = { onNavigateBreadcrumb(entry.breadcrumbIndex) },
                                    onActionClick = { onNavigateBreadcrumb(entry.breadcrumbIndex) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Text("Refresh")
                }
                TextButton(onClick = onUseLocation, enabled = state.selectedShare.isNotBlank()) {
                    Text("Use location")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

private fun ServerListItemUiState.onlineStatusLabel(): String {
    val latencySuffix = lastTestLatencyMs?.let { " (${it}ms)" } ?: ""
    return when (lastTestStatus) {
        "SUCCESS" -> "Online: Yes$latencySuffix"
        else -> "Online: No$latencySuffix"
    }
}

@Composable
private fun BrowseListEntryRow(
    label: String,
    modifier: Modifier = Modifier,
    actionLabel: String = "Open",
    onRowClick: () -> Unit,
    onActionClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            actionLabel,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .widthIn(min = 64.dp)
                .clickable(onClick = onActionClick)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BreadcrumbButton(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private sealed class BrowseListItem(val key: String) {
    data class Share(val name: String) : BrowseListItem("share:$name")
    data class Directory(val name: String) : BrowseListItem("dir:$name")
    data class UpDirectory(val breadcrumbIndex: Int) : BrowseListItem("up-directory")
}
