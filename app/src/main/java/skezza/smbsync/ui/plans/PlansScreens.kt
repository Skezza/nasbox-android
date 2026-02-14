package skezza.smbsync.ui.plans

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import skezza.smbsync.domain.plan.PlanSourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(
    viewModel: PlansViewModel,
    onAddPlan: () -> Unit,
    onEditPlan: (Long) -> Unit,
    onRunPlan: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val plans by viewModel.plans.collectAsState()
    val message by viewModel.message.collectAsState()
    val activeRunPlanIds by viewModel.activeRunPlanIds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRunPlanId by remember { mutableStateOf<Long?>(null) }

    val runPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val runPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val planId = pendingRunPlanId
        pendingRunPlanId = null
        if (granted && planId != null) {
            onRunPlan(planId)
        } else if (!granted) {
            viewModel.showMessage("Photo permission is required to run album backups.")
        }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text("Plans") },
                actions = {
                    IconButton(
                        modifier = Modifier.size(52.dp),
                        onClick = onAddPlan,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add plan")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        if (plans.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No plans yet. Add your first backup plan.")
                ElevatedButton(onClick = onAddPlan, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add plan", modifier = Modifier.padding(start = 8.dp))
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
                items(plans, key = { it.planId }) { plan ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditPlan(plan.planId) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(plan.name)
                            Text("Source: ${plan.sourceSummary}")
                            Text("Server: ${plan.serverName}")
                            if (plan.useAlbumTemplating) {
                                Text("Template: ${plan.template}")
                                Text("Filename: ${plan.filenamePattern}")
                            }
                            Text(if (plan.enabled) "Enabled" else "Disabled")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onEditPlan(plan.planId) }) { Text("Edit") }
                                Button(
                                    onClick = {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            runPermission,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            onRunPlan(plan.planId)
                                        } else {
                                            pendingRunPlanId = plan.planId
                                            runPermissionLauncher.launch(runPermission)
                                        }
                                    },
                                    enabled = plan.enabled && !activeRunPlanIds.contains(plan.planId),
                                ) {
                                    Text(if (activeRunPlanIds.contains(plan.planId)) "Running..." else "Run now")
                                }
                                Button(onClick = { viewModel.deletePlan(plan.planId) }) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Text("Delete", modifier = Modifier.padding(start = 6.dp))
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
fun PlanEditorScreen(
    viewModel: PlansViewModel,
    planId: Long?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val editorState by viewModel.editorState.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val hasMediaPermission by viewModel.hasMediaPermission.collectAsState()
    val isLoadingAlbums by viewModel.isLoadingAlbums.collectAsState()
    val editorScrollState = rememberScrollState()

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.updateEditorFolderPath(it.toString()) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setMediaPermissionGranted(granted)
    }

    LaunchedEffect(planId) {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        viewModel.setMediaPermissionGranted(granted)
        viewModel.loadPlanForEdit(planId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (planId == null) "New Plan" else "Edit Plan") },
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
                .padding(16.dp)
                .verticalScroll(editorScrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = editorState.name,
                onValueChange = viewModel::updateEditorName,
                label = { Text("Plan name") },
                isError = editorState.validation.nameError != null,
                supportingText = { editorState.validation.nameError?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SourceTypeSelector(
                selectedType = editorState.sourceType,
                onSelect = viewModel::updateEditorSourceType,
            )

            when (editorState.sourceType) {
                PlanSourceType.ALBUM -> {
                    if (!hasMediaPermission) {
                        Text("Photo permission is required to list albums.")
                        Button(onClick = { permissionLauncher.launch(permission) }) { Text("Grant media access") }
                    } else {
                        if (isLoadingAlbums) Text("Loading albums...")
                        if (albums.isEmpty()) Text("No albums found. Capture or import photos to create plans.")
                        AlbumSelector(
                            options = albums,
                            selectedAlbumId = editorState.selectedAlbumId,
                            error = editorState.validation.albumError,
                            onSelect = viewModel::updateEditorAlbum,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Include videos")
                            Switch(checked = editorState.includeVideos, onCheckedChange = viewModel::updateEditorIncludeVideos)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Use album templating")
                            Switch(checked = editorState.useAlbumTemplating, onCheckedChange = viewModel::updateEditorUseAlbumTemplating)
                        }
                    }
                }

                PlanSourceType.FOLDER -> {
                    OutlinedTextField(
                        value = editorState.folderPath,
                        onValueChange = viewModel::updateEditorFolderPath,
                        label = { Text("Folder URI/path") },
                        supportingText = { editorState.validation.folderPathError?.let { Text(it) } },
                        isError = editorState.validation.folderPathError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderLauncher.launch(null) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Text("Choose folder", modifier = Modifier.padding(start = 6.dp))
                        }
                        ElevatedButton(onClick = { viewModel.updateEditorFolderPath("/storage/emulated/0/DCIM") }) {
                            Text("Use DCIM")
                        }
                    }
                }

                PlanSourceType.FULL_DEVICE -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Info, contentDescription = null)
                                Text("Full phone backup")
                            }
                            Text("This plan will attempt to back up shared-storage folders (for example DCIM, Pictures, Movies, Download, Documents, Music).")
                            Text("Heads up: this can take a long time and use plenty of battery. Best run overnight while charging.")
                        }
                    }
                }
            }

            if (servers.isEmpty()) {
                Text("Add at least one server in Vault before creating a plan.")
            }

            ServerSelector(
                options = servers,
                selectedServerId = editorState.selectedServerId,
                error = editorState.validation.serverError,
                onSelect = viewModel::updateEditorServer,
            )

            if (editorState.sourceType == PlanSourceType.ALBUM && editorState.useAlbumTemplating) {
                OutlinedTextField(
                    value = editorState.directoryTemplate,
                    onValueChange = viewModel::updateEditorDirectoryTemplate,
                    label = { Text("Directory template") },
                    supportingText = {
                        Text(editorState.validation.templateError ?: "Example: {year}/{month}/{album}")
                    },
                    isError = editorState.validation.templateError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = editorState.filenamePattern,
                    onValueChange = viewModel::updateEditorFilenamePattern,
                    label = { Text("Filename pattern") },
                    supportingText = {
                        Text(editorState.validation.filenamePatternError ?: "Example: {timestamp}_{mediaId}.{ext}")
                    },
                    isError = editorState.validation.filenamePatternError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Enabled")
                Switch(checked = editorState.enabled, onCheckedChange = viewModel::updateEditorEnabled)
            }

            Button(onClick = { viewModel.savePlan(onNavigateBack) }) {
                Text(if (planId == null) "Create plan" else "Save changes")
            }
        }
    }
}

@Composable
private fun SourceTypeSelector(
    selectedType: PlanSourceType,
    onSelect: (PlanSourceType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedType == PlanSourceType.ALBUM,
            onClick = { onSelect(PlanSourceType.ALBUM) },
            label = { Text("Album", maxLines = 1) },
        )
        FilterChip(
            selected = selectedType == PlanSourceType.FOLDER,
            onClick = { onSelect(PlanSourceType.FOLDER) },
            label = { Text("Folder", maxLines = 1) },
        )
        FilterChip(
            selected = selectedType == PlanSourceType.FULL_DEVICE,
            onClick = { onSelect(PlanSourceType.FULL_DEVICE) },
            label = { Text("Phone", maxLines = 1) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSelector(
    options: List<skezza.smbsync.data.media.MediaAlbum>,
    selectedAlbumId: String?,
    error: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.bucketId == selectedAlbumId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected?.let { "${it.displayName} (${it.itemCount})" }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Album") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            singleLine = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { album ->
                DropdownMenuItem(
                    text = { Text("${album.displayName} (${album.itemCount})") },
                    onClick = {
                        onSelect(album.bucketId)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSelector(
    options: List<PlanServerOption>,
    selectedServerId: Long?,
    error: String?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.serverId == selectedServerId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected?.label.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Destination server") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            singleLine = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.label) },
                    onClick = {
                        onSelect(server.serverId)
                        expanded = false
                    },
                )
            }
        }
    }
}
