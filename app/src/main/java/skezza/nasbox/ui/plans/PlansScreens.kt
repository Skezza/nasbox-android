package skezza.nasbox.ui.plans

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import skezza.nasbox.domain.plan.PlanSourceType
import skezza.nasbox.domain.schedule.PLAN_MAX_INTERVAL_HOURS
import skezza.nasbox.domain.schedule.PlanScheduleFrequency
import skezza.nasbox.domain.schedule.PlanScheduleWeekday
import skezza.nasbox.domain.schedule.formatMinutesOfDay
import skezza.nasbox.domain.schedule.formatPlanScheduleSummary
import skezza.nasbox.domain.schedule.isDaySelected
import skezza.nasbox.ui.common.ErrorHint
import skezza.nasbox.ui.common.LoadState
import skezza.nasbox.ui.common.StateCard

private const val RUN_NOW_SOFT_DISABLE_MS = 10_000L

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
    val planListState by viewModel.planListUiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val activeRunPlanIds by viewModel.activeRunPlanIds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var pendingRunPlanId by remember { mutableStateOf<Long?>(null) }
    var pendingRunPermissions by remember { mutableStateOf<List<String>>(emptyList()) }
    var softDisabledRunPlanIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun softDisableRunButton(planId: Long) {
        softDisabledRunPlanIds = softDisabledRunPlanIds + planId
        coroutineScope.launch {
            delay(RUN_NOW_SOFT_DISABLE_MS)
            softDisabledRunPlanIds = softDisabledRunPlanIds - planId
        }
    }

    val runPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val planId = pendingRunPlanId
        pendingRunPlanId = null
        val denied = pendingRunPermissions.filterNot { grants[it] == true }
        pendingRunPermissions = emptyList()
        if (denied.isEmpty() && planId != null) {
            onRunPlan(planId)
        } else if (denied.isNotEmpty()) {
            viewModel.showMessage("Required media permission was denied for this job source.")
        }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text("Jobs") },
                actions = {
                    IconButton(
                        modifier = Modifier.size(52.dp),
                        onClick = onAddPlan,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add job")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        when {
            planListState.loadState == LoadState.Loading -> {
                StateCard(
                    title = "Loading jobs",
                    description = "Loading saved jobs and servers.",
                    progressContent = {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    },
                )
            }
            planListState.loadState is LoadState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    ErrorHint(
                        message = planListState.errorMessage
                            ?: "Unable to load jobs. Check storage permissions or network.",
                    )
                    StateCard(
                        title = "Jobs unavailable",
                        description = "Check storage permissions or network, then return to this screen.",
                    )
                }
            }
            planListState.plans.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    StateCard(
                        title = "No jobs yet",
                        description = "Create a job to start transferring data.",
                        actionLabel = "Add job",
                        onAction = onAddPlan,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(planListState.plans, key = { it.planId }) { plan ->
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
                                Text("Schedule: ${plan.scheduleSummary}")
                                Text(if (plan.enabled) "Enabled" else "Disabled")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val isRunActive = activeRunPlanIds.contains(plan.planId)
                                    val isRunSoftDisabled = softDisabledRunPlanIds.contains(plan.planId)
                                    Button(onClick = { onEditPlan(plan.planId) }) { Text("Edit") }
                                    Button(
                                        onClick = {
                                            if (activeRunPlanIds.contains(plan.planId) ||
                                                softDisabledRunPlanIds.contains(plan.planId)
                                            ) {
                                                return@Button
                                            }
                                            softDisableRunButton(plan.planId)
                                            val requiredPermissions = requiredRunPermissions(
                                                sourceType = plan.sourceType,
                                                includeVideos = plan.includeVideos,
                                            )
                                            val deniedPermissions = requiredPermissions.filter { permission ->
                                                ContextCompat.checkSelfPermission(
                                                    context,
                                                    permission,
                                                ) != PackageManager.PERMISSION_GRANTED
                                            }
                                            if (deniedPermissions.isEmpty()) {
                                                onRunPlan(plan.planId)
                                            } else {
                                                pendingRunPlanId = plan.planId
                                                pendingRunPermissions = deniedPermissions
                                                runPermissionLauncher.launch(deniedPermissions.toTypedArray())
                                            }
                                        },
                                        enabled = plan.enabled && !isRunActive && !isRunSoftDisabled,
                                    ) {
                                        Text(
                                            when {
                                                isRunActive -> "Running..."
                                                isRunSoftDisabled -> "Starting..."
                                                else -> "Run now"
                                            },
                                        )
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
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.updateEditorFolderPath(it.toString())
        }
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (planId == null) "New Job" else "Edit Job") },
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
                .imePadding()
                .verticalScroll(editorScrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = editorState.name,
                onValueChange = viewModel::updateEditorName,
                label = { Text("Job name") },
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
                        Text("Photo access is required to list albums.")
                        Button(onClick = { permissionLauncher.launch(permission) }) { Text("Grant photo access") }
                    } else {
                        if (isLoadingAlbums) Text("Loading albums...")
                        if (albums.isEmpty()) Text("No albums found. Capture or import photos to create jobs.")
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
                        Text("Use album template")
                            Switch(checked = editorState.useAlbumTemplating, onCheckedChange = viewModel::updateEditorUseAlbumTemplating)
                        }
                    }
                }

                PlanSourceType.FOLDER -> {
                    OutlinedTextField(
                        value = editorState.folderPath,
                        onValueChange = viewModel::updateEditorFolderPath,
                    label = { Text("Folder path") },
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
                            Text("Use DCIM folder")
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
                            Text("This job backs up all shared storage (DCIM, Documents, Downloads, etc.).")
                        }
                    }
                }
            }

            if (servers.isEmpty()) {
                Text("Add at least one server before creating a job.")
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show progress notification")
                Switch(
                    checked = editorState.progressNotificationEnabled,
                    onCheckedChange = viewModel::updateEditorProgressNotificationEnabled,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto-run")
                Switch(
                    checked = editorState.scheduleEnabled,
                    onCheckedChange = viewModel::updateEditorScheduleEnabled,
                )
            }

            if (editorState.scheduleEnabled) {
            Text("Schedule presets")
                SchedulePresetChips(onSelect = viewModel::applySchedulePreset)

            Text("Frequency")
                RecurrenceSelector(
                    selectedFrequency = editorState.scheduleFrequency,
                    onSelect = viewModel::updateEditorScheduleFrequency,
                )

                when (editorState.scheduleFrequency) {
                    PlanScheduleFrequency.DAILY -> {
                        ScheduleTimeButton(
                            scheduleTimeMinutes = editorState.scheduleTimeMinutes,
                            onPicked = viewModel::updateEditorScheduleTimeMinutes,
                        )
                    }

                    PlanScheduleFrequency.WEEKLY -> {
                        WeekdaySelector(
                            daysMask = editorState.scheduleDaysMask,
                            onToggle = viewModel::toggleEditorScheduleWeekday,
                        )
                        ScheduleTimeButton(
                            scheduleTimeMinutes = editorState.scheduleTimeMinutes,
                            onPicked = viewModel::updateEditorScheduleTimeMinutes,
                        )
                    }

                    PlanScheduleFrequency.MONTHLY -> {
                        MonthlyDaySelector(
                            dayOfMonth = editorState.scheduleDayOfMonth,
                            onSelect = viewModel::updateEditorScheduleDayOfMonth,
                        )
                        Text("If a selected day is missing, the schedule falls back to the month's last day.")
                        ScheduleTimeButton(
                            scheduleTimeMinutes = editorState.scheduleTimeMinutes,
                            onPicked = viewModel::updateEditorScheduleTimeMinutes,
                        )
                    }

                    PlanScheduleFrequency.INTERVAL_HOURS -> {
                        IntervalHoursStepper(
                            intervalHours = editorState.scheduleIntervalHours,
                            onChange = viewModel::updateEditorScheduleIntervalHours,
                        )
                    }
                }
            }

            val scheduleHint = formatPlanScheduleSummary(
                enabled = editorState.scheduleEnabled,
                frequency = editorState.scheduleFrequency,
                scheduleTimeMinutes = editorState.scheduleTimeMinutes,
                scheduleDaysMask = editorState.scheduleDaysMask,
                scheduleDayOfMonth = editorState.scheduleDayOfMonth,
                scheduleIntervalHours = editorState.scheduleIntervalHours,
            )
            Text("Schedule: $scheduleHint")

            Spacer(modifier = Modifier.weight(1f, fill = true))

            Button(onClick = { viewModel.savePlan(onNavigateBack) }) {
                Text(if (planId == null) "Create job" else "Save job")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun requiredRunPermissions(
    sourceType: PlanSourceType,
    includeVideos: Boolean,
): List<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    return when (sourceType) {
        PlanSourceType.ALBUM -> buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            if (includeVideos) add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        PlanSourceType.FOLDER -> emptyList()
        PlanSourceType.FULL_DEVICE -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    }
}

@Composable
private fun SchedulePresetChips(onSelect: (PlanSchedulePreset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SchedulePresetChip(
            label = "Nightly",
            onClick = { onSelect(PlanSchedulePreset.NIGHTLY) },
        )
        SchedulePresetChip(
            label = "Workdays",
            onClick = { onSelect(PlanSchedulePreset.WORKDAYS) },
        )
        SchedulePresetChip(
            label = "Weekend",
            onClick = { onSelect(PlanSchedulePreset.WEEKEND) },
        )
        SchedulePresetChip(
            label = "Every 6h",
            onClick = { onSelect(PlanSchedulePreset.EVERY_6_HOURS) },
        )
    }
}

@Composable
private fun SchedulePresetChip(
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
    )
}

@Composable
private fun RecurrenceSelector(
    selectedFrequency: PlanScheduleFrequency,
    onSelect: (PlanScheduleFrequency) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlanScheduleFrequency.entries.forEach { frequency ->
            FilterChip(
                selected = selectedFrequency == frequency,
                onClick = { onSelect(frequency) },
                label = { Text(frequencyLabel(frequency), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun WeekdaySelector(
    daysMask: Int,
    onToggle: (PlanScheduleWeekday) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlanScheduleWeekday.entries.forEach { weekday ->
            FilterChip(
                selected = isDaySelected(daysMask, weekday),
                onClick = { onToggle(weekday) },
                label = { Text(weekday.shortLabel, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun MonthlyDaySelector(
    dayOfMonth: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Day of month")
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$dayOfMonth")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            (1..31).forEach { value ->
                DropdownMenuItem(
                    text = { Text(value.toString()) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun IntervalHoursStepper(
    intervalHours: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Every")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onChange(intervalHours - 1) },
                enabled = intervalHours > 1,
            ) {
                Text("-")
            }
            Text("${intervalHours}h", modifier = Modifier.padding(top = 10.dp))
            OutlinedButton(
                onClick = { onChange(intervalHours + 1) },
                enabled = intervalHours < PLAN_MAX_INTERVAL_HOURS,
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun ScheduleTimeButton(
    scheduleTimeMinutes: Int,
    onPicked: (Int) -> Unit,
) {
    val context = LocalContext.current
    ElevatedButton(
        onClick = {
            TimePickerDialog(
                context,
                { _, selectedHour, selectedMinute ->
                    onPicked(selectedHour * 60 + selectedMinute)
                },
                scheduleTimeMinutes / 60,
                scheduleTimeMinutes % 60,
                true,
            ).show()
        },
    ) {
        Text("Time: ${formatMinutesOfDay(scheduleTimeMinutes)}")
    }
}

private fun frequencyLabel(frequency: PlanScheduleFrequency): String = when (frequency) {
    PlanScheduleFrequency.DAILY -> "Daily"
    PlanScheduleFrequency.WEEKLY -> "Weekly"
    PlanScheduleFrequency.MONTHLY -> "Monthly"
    PlanScheduleFrequency.INTERVAL_HOURS -> "Every X hours"
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
    options: List<skezza.nasbox.data.media.MediaAlbum>,
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
