package skezza.nasbox.ui.vault

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import skezza.nasbox.data.db.ServerEntity
import skezza.nasbox.data.discovery.DiscoveredSmbServer
import skezza.nasbox.data.repository.ServerRepository
import skezza.nasbox.data.security.CredentialStore
import skezza.nasbox.domain.discovery.DiscoverSmbServersUseCase
import skezza.nasbox.domain.smb.BrowseSmbDestinationUseCase
import skezza.nasbox.domain.smb.SmbBrowseResult
import skezza.nasbox.domain.smb.TestSmbConnectionUseCase
import skezza.nasbox.ui.common.LoadState
import skezza.nasbox.domain.vault.ServerInput
import skezza.nasbox.domain.vault.ServerValidationResult
import skezza.nasbox.domain.vault.ValidateServerInputUseCase

class VaultViewModel(
    private val serverRepository: ServerRepository,
    private val credentialStore: CredentialStore,
    private val testSmbConnectionUseCase: TestSmbConnectionUseCase,
    private val discoverSmbServersUseCase: DiscoverSmbServersUseCase,
    private val browseSmbDestinationUseCase: BrowseSmbDestinationUseCase,
    private val validateServerInput: ValidateServerInputUseCase = ValidateServerInputUseCase(),
) : ViewModel() {

    private val testingServerIds = MutableStateFlow<Set<Long>>(emptySet())
    private var pendingDiscoverySelection: DiscoveredSmbServer? = null
    private var pendingAutoBrowse: Boolean = false

    private val serverItemsFlow = serverRepository.observeServers()
        .combine(testingServerIds) { servers, testing ->
            servers.map {
                ServerListItemUiState(
                    serverId = it.serverId,
                    name = it.name,
                    endpoint = formatEndpoint(it.host, it.shareName),
                    basePath = it.basePath,
                    lastTestStatus = it.lastTestStatus,
                    lastTestTimestampEpochMs = it.lastTestTimestampEpochMs,
                    lastTestLatencyMs = it.lastTestLatencyMs,
                    lastTestErrorMessage = it.lastTestErrorMessage,
                    isTesting = testing.contains(it.serverId),
                )
            }
        }

    val serverListUiState: StateFlow<ServerListUiState> = serverItemsFlow
        .map { ServerListUiState(loadState = LoadState.Success, servers = it) }
        .onStart { emit(ServerListUiState(loadState = LoadState.Loading)) }
        .catch {
            emit(
                ServerListUiState(
                    loadState = LoadState.Error(VAULT_SERVER_ERROR_MESSAGE),
                    errorMessage = VAULT_SERVER_ERROR_MESSAGE,
                ),
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ServerListUiState(loadState = LoadState.Loading),
        )

    private val _editorState = MutableStateFlow(ServerEditorUiState())
    val editorState: StateFlow<ServerEditorUiState> = _editorState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _discoveryState = MutableStateFlow(DiscoveryUiState())
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    private val _browseState = MutableStateFlow(SmbBrowseUiState())
    val browseState: StateFlow<SmbBrowseUiState> = _browseState.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun loadServerForEdit(serverId: Long?) {
        if (serverId == null) {
            val discovery = pendingDiscoverySelection
            pendingDiscoverySelection = null
            val nextState = if (discovery == null) {
                ServerEditorUiState()
            } else {
                val normalizedHost = discovery.host.trim().ifBlank { discovery.ipAddress }.lowercase()
                ServerEditorUiState(
                    name = normalizedHost,
                    host = discovery.ipAddress,
                    shareName = "",
                    basePath = "backup",
                    domain = "",
                )
            }
            _editorState.value = nextState
            if (pendingAutoBrowse) {
                pendingAutoBrowse = false
                openBrowseDestination()
            }
            clearMessage()
            return
        }

        viewModelScope.launch {
            runCatching {
                val server = serverRepository.getServer(serverId)
                    ?: throw IllegalStateException("Server not found.")
                val existingPassword = credentialStore.loadPassword(server.credentialAlias).orEmpty()
                _editorState.value = ServerEditorUiState(
                    editingServerId = server.serverId,
                    name = server.name,
                    host = server.host,
                    shareName = server.shareName,
                    basePath = server.basePath,
                    domain = server.domain,
                    username = server.username,
                    password = existingPassword,
                    validation = ServerValidationResult(),
                )
                clearMessage()
            }.onFailure {
                _message.value = "Unable to load server details. Please try again."
            }
        }
    }

    fun updateEditorField(field: ServerEditorField, value: String) {
        val updated = _editorState.value.updateField(field, value)
        _editorState.value = updated
        if (pendingAutoBrowse && updated.host.isNotBlank() && updated.username.isNotBlank() && updated.password.isNotBlank()) {
            pendingAutoBrowse = false
            openBrowseDestination()
        }
    }

    fun saveServer(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val state = _editorState.value
            val input = ServerInput(
                name = state.name,
                host = state.host,
                shareName = state.shareName,
                basePath = state.basePath,
                username = state.username,
                password = state.password,
            )
            val validation = validateServerInput(input)
            if (!validation.isValid) {
                _editorState.value = state.copy(validation = validation)
                return@launch
            }

            runCatching {
                val credentialAlias = if (state.editingServerId != null) {
                    serverRepository.getServer(state.editingServerId)?.credentialAlias ?: newAlias()
                } else {
                    newAlias()
                }

                credentialStore.savePassword(credentialAlias, state.password)

                val entity = ServerEntity(
                    serverId = state.editingServerId ?: 0,
                    name = state.name.trim(),
                    host = state.host.trim(),
                    shareName = normalizeShare(state.shareName),
                    basePath = state.basePath.trim(),
                    domain = state.domain.trim(),
                    username = state.username.trim(),
                    credentialAlias = credentialAlias,
                )

                if (state.editingServerId == null) {
                    serverRepository.createServer(entity)
                } else {
                    serverRepository.updateServer(entity)
                }
            }.onSuccess {
                _editorState.value = ServerEditorUiState()
                clearMessage()
                onSuccess()
            }.onFailure {
                _message.value = "Unable to save server. Ensure the name is unique and try again."
            }
        }
    }

    fun testServerConnection(serverId: Long) {
        viewModelScope.launch {
            testingServerIds.value = testingServerIds.value + serverId
            runCatching {
                val result = testSmbConnectionUseCase.testPersistedServer(serverId)
            }.onFailure {
            }
            testingServerIds.value = testingServerIds.value - serverId
        }
    }

    fun testEditorConnection() {
        viewModelScope.launch {
            val current = _editorState.value
            val validation = validateServerInput(
                ServerInput(
                    name = current.name,
                    host = current.host,
                    shareName = current.shareName,
                    basePath = current.basePath,
                    username = current.username,
                    password = current.password,
                ),
            )
            if (!validation.isValid) {
                _editorState.value = current.copy(validation = validation)
                _message.value = "Fix validation errors before testing connection."
                return@launch
            }

            _editorState.value = current.copy(isTestingConnection = true)
            runCatching {
                val result = testSmbConnectionUseCase.testDraftServer(
                    host = current.host,
                    shareName = current.shareName,
                    username = current.username,
                    password = current.password,
                )
                _message.value = listOfNotNull(result.message, result.recoveryHint, result.technicalDetail).joinToString(" ")
            }.onFailure {
                _message.value = "Connection test failed due to an unexpected error."
            }
            _editorState.value = _editorState.value.copy(isTestingConnection = false)
        }
    }

    fun openBrowseDestination() {
        val editor = _editorState.value
        if (editor.host.isBlank()) {
            _message.value = "Enter host before browsing destination."
            return
        }
        val normalizedShare = normalizeShare(editor.shareName)
        val normalizedBasePath = browseSmbDestinationUseCase.normalizePath(editor.basePath)
        if (editor.username.isBlank() || editor.password.isBlank()) {
            viewModelScope.launch {
                when (val result = browseSmbDestinationUseCase.listShares(editor.host, "", "", editor.domain.trim())) {
                    is SmbBrowseResult.Success -> {
                        if (result.data.isNotEmpty()) {
                            _browseState.value = SmbBrowseUiState(
                                isVisible = true,
                                isLoading = false,
                                mode = BrowseMode.SHARES,
                                shares = result.data,
                                selectedShare = "",
                                currentPath = "",
                                errorMessage = null,
                            )
                        } else {
                            _message.value = "Guest access returned no shares. Enter credentials to browse."
                        }
                    }

                    is SmbBrowseResult.Failure -> {
                        Log.w(
                            TAG,
                            "openBrowseDestination guestFailure host=${editor.host.trim()} message=${result.message} detail=${result.technicalDetail}",
                        )
                        _message.value = "Guest access failed. Enter credentials to browse."
                    }
                }
            }
            return
        }

        _browseState.value = SmbBrowseUiState(
            isVisible = true,
            isLoading = true,
            selectedShare = normalizedShare,
            currentPath = normalizedBasePath,
        )

        viewModelScope.launch {
            if (normalizedShare.isBlank()) {
                loadBrowseShares()
            } else {
                loadBrowseDirectories(normalizedShare, normalizedBasePath)
            }
        }
    }

    fun closeBrowseDestination() {
        _browseState.value = SmbBrowseUiState()
    }

    fun refreshBrowseDestination() {
        val current = _browseState.value
        if (!current.isVisible) return
        viewModelScope.launch {
            if (current.selectedShare.isBlank()) {
                loadBrowseShares()
            } else {
                loadBrowseDirectories(current.selectedShare, current.currentPath)
            }
        }
    }

    fun selectBrowseShare(shareName: String) {
        viewModelScope.launch {
            loadBrowseDirectories(shareName, "")
        }
    }

    fun openBrowseDirectory(directoryName: String) {
        val state = _browseState.value
        if (state.selectedShare.isBlank()) return
        val nextPath = if (state.currentPath.isBlank()) directoryName else "${state.currentPath}/$directoryName"
        viewModelScope.launch {
            loadBrowseDirectories(state.selectedShare, nextPath)
        }
    }

    fun navigateBrowseBreadcrumb(index: Int) {
        val state = _browseState.value
        if (state.selectedShare.isBlank()) return
        val normalized = browseSmbDestinationUseCase.normalizePath(state.currentPath)
        val segments = normalized.split('/').filter { it.isNotBlank() }
        val nextPath = when {
            index < 0 -> null // jump back to share list
            index <= 0 -> ""
            index - 1 < segments.size -> segments.take(index).joinToString("/")
            else -> normalized
        }
        viewModelScope.launch {
            if (nextPath == null) {
                _browseState.value = _browseState.value.copy(
                    mode = BrowseMode.SHARES,
                    selectedShare = "",
                    currentPath = "",
                    directories = emptyList(),
                    errorMessage = null,
                )
                loadBrowseShares()
            } else {
                loadBrowseDirectories(state.selectedShare, nextPath)
            }
        }
    }

    fun applyBrowseSelection() {
        val state = _browseState.value
        if (state.selectedShare.isBlank()) {
            _message.value = "Select a share to apply destination."
            return
        }
        _editorState.value = _editorState.value.copy(
            shareName = normalizeShare(state.selectedShare),
            basePath = browseSmbDestinationUseCase.normalizePath(state.currentPath),
        )
        _browseState.value = SmbBrowseUiState()
    }

    private suspend fun loadBrowseShares() {
        val editor = _editorState.value
        _browseState.value = _browseState.value.copy(isLoading = true, errorMessage = null)
        when (val result = browseSmbDestinationUseCase.listShares(editor.host, editor.username, editor.password, editor.domain.trim())) {
            is SmbBrowseResult.Success -> {
                _browseState.value = _browseState.value.copy(
                    isLoading = false,
                    mode = BrowseMode.SHARES,
                    shares = result.data,
                    directories = emptyList(),
                    errorMessage = if (result.data.isEmpty()) {
                        "Connected, but no shares were returned. Check permissions and hidden share visibility."
                    } else {
                        null
                    },
                )
            }

            is SmbBrowseResult.Failure -> {
                Log.w(TAG, "loadBrowseShares failure host=${editor.host.trim()} message=${result.message} detail=${result.technicalDetail}")
                _browseState.value = _browseState.value.copy(isLoading = false, errorMessage = joinBrowseMessage(result))
            }
        }
    }

    private suspend fun loadBrowseDirectories(shareName: String, path: String) {
        val editor = _editorState.value
        val normalizedShare = normalizeShare(shareName)
        val normalizedPath = browseSmbDestinationUseCase.normalizePath(path)
        _browseState.value = _browseState.value.copy(
            isLoading = true,
            mode = BrowseMode.FOLDERS,
            selectedShare = normalizedShare,
            currentPath = normalizedPath,
            errorMessage = null,
        )
        when (
            val result = browseSmbDestinationUseCase.listDirectories(
                host = editor.host,
                shareName = normalizedShare,
                path = normalizedPath,
                username = editor.username,
                password = editor.password,
            )
        ) {
            is SmbBrowseResult.Success -> {
                _browseState.value = _browseState.value.copy(
                    isLoading = false,
                    mode = BrowseMode.FOLDERS,
                    selectedShare = normalizedShare,
                    currentPath = normalizedPath,
                    directories = result.data,
                    errorMessage = if (result.data.isEmpty()) "No folders found in this location." else null,
                )
            }

            is SmbBrowseResult.Failure -> {
                Log.w(TAG, "loadBrowseDirectories failure host=${editor.host.trim()} share=$normalizedShare path=$normalizedPath message=${result.message} detail=${result.technicalDetail}")
                _browseState.value = _browseState.value.copy(isLoading = false, errorMessage = joinBrowseMessage(result))
            }
        }
    }

    private fun joinBrowseMessage(result: SmbBrowseResult.Failure): String =
        listOfNotNull(result.message, result.recoveryHint, result.technicalDetail).joinToString(" ")

    fun discoverServers() {
        viewModelScope.launch {
            _discoveryState.value = _discoveryState.value.copy(
                isScanning = true,
                servers = emptyList(),
                errorMessage = null,
            )
            try {
                discoverSmbServersUseCase().collect { servers ->
                    _discoveryState.value = _discoveryState.value.copy(
                        isScanning = true,
                        servers = servers,
                        errorMessage = null,
                    )
                }
                val servers = _discoveryState.value.servers
                _discoveryState.value = _discoveryState.value.copy(
                    isScanning = false,
                    errorMessage = null,
                )
                if (servers.isEmpty()) {
                    _message.value = "No SMB servers discovered. On Android emulators, LAN/mDNS discovery is often blocked by NAT. Try on a physical device or enter smb://quanta.local/<share> manually."
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _discoveryState.value = _discoveryState.value.copy(
                    isScanning = false,
                    errorMessage = "Failed to scan the local network.",
                )
            }
        }
    }

    fun setDiscoverySelection(server: DiscoveredSmbServer) {
        pendingDiscoverySelection = server
        pendingAutoBrowse = true
    }

    fun clearDiscoveryState() {
        _discoveryState.value = DiscoveryUiState()
    }

    fun deleteServer(serverId: Long) {
        viewModelScope.launch {
            runCatching {
                val existing = serverRepository.getServer(serverId) ?: return@launch
                credentialStore.deletePassword(existing.credentialAlias)
                serverRepository.deleteServer(serverId)
            }.onFailure {
                _message.value = "Unable to delete server. Please try again."
            }
        }
    }

    private fun newAlias(): String = "vault/${UUID.randomUUID()}"

    private fun normalizeShare(value: String): String = value.trim().trim('/').trim('\\')

    private fun formatEndpoint(host: String, shareName: String): String =
        if (shareName.isBlank()) host else "$host/$shareName"

    companion object {
        private const val TAG = "NasBoxBrowse"

        fun factory(
            serverRepository: ServerRepository,
            credentialStore: CredentialStore,
            testSmbConnectionUseCase: TestSmbConnectionUseCase,
            discoverSmbServersUseCase: DiscoverSmbServersUseCase,
            browseSmbDestinationUseCase: BrowseSmbDestinationUseCase,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return VaultViewModel(
                        serverRepository = serverRepository,
                        credentialStore = credentialStore,
                        testSmbConnectionUseCase = testSmbConnectionUseCase,
                        discoverSmbServersUseCase = discoverSmbServersUseCase,
                        browseSmbDestinationUseCase = browseSmbDestinationUseCase,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                return create(modelClass)
            }
        }
    }
}

enum class ServerEditorField {
    NAME,
    HOST,
    SHARE,
    BASE_PATH,
    DOMAIN,
    USERNAME,
    PASSWORD,
}

enum class BrowseMode {
    SHARES,
    FOLDERS,
}

data class ServerListItemUiState(
    val serverId: Long,
    val name: String,
    val endpoint: String,
    val basePath: String,
    val lastTestStatus: String? = null,
    val lastTestTimestampEpochMs: Long? = null,
    val lastTestLatencyMs: Long? = null,
    val lastTestErrorMessage: String? = null,
    val isTesting: Boolean = false,
)

data class ServerEditorUiState(
    val editingServerId: Long? = null,
    val name: String = "",
    val host: String = "",
    val shareName: String = "",
    val basePath: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val validation: ServerValidationResult = ServerValidationResult(),
    val isTestingConnection: Boolean = false,
) {
    fun updateField(field: ServerEditorField, value: String): ServerEditorUiState {
        return when (field) {
            ServerEditorField.NAME -> copy(name = value)
            ServerEditorField.HOST -> copy(host = value)
            ServerEditorField.SHARE -> copy(shareName = value)
            ServerEditorField.BASE_PATH -> copy(basePath = value)
            ServerEditorField.DOMAIN -> copy(domain = value)
            ServerEditorField.USERNAME -> copy(username = value)
            ServerEditorField.PASSWORD -> copy(password = value)
        }
    }
}

data class DiscoveryUiState(
    val isScanning: Boolean = false,
    val servers: List<DiscoveredSmbServer> = emptyList(),
    val errorMessage: String? = null,
)

data class SmbBrowseUiState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val mode: BrowseMode = BrowseMode.SHARES,
    val shares: List<String> = emptyList(),
    val selectedShare: String = "",
    val currentPath: String = "",
    val directories: List<String> = emptyList(),
    val errorMessage: String? = null,
) {
    val breadcrumbs: List<String>
        get() = listOf("") + currentPath.split('/').filter { it.isNotBlank() }
}

data class ServerListUiState(
    val loadState: LoadState = LoadState.Idle,
    val servers: List<ServerListItemUiState> = emptyList(),
    val errorMessage: String? = null,
)

private const val VAULT_SERVER_ERROR_MESSAGE = "Unable to load servers. Check SMB credentials or network."
