package skezza.smbsync.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.domain.smb.TestSmbConnectionUseCase
import skezza.smbsync.domain.vault.ServerInput
import skezza.smbsync.domain.vault.ServerValidationResult
import skezza.smbsync.domain.vault.ValidateServerInputUseCase

class VaultViewModel(
    private val serverRepository: ServerRepository,
    private val credentialStore: CredentialStore,
    private val testSmbConnectionUseCase: TestSmbConnectionUseCase,
    private val validateServerInput: ValidateServerInputUseCase = ValidateServerInputUseCase(),
) : ViewModel() {

    private val testingServerIds = MutableStateFlow<Set<Long>>(emptySet())

    val servers: StateFlow<List<ServerListItemUiState>> = serverRepository.observeServers()
        .combine(testingServerIds) { servers, testing ->
            servers.map {
                ServerListItemUiState(
                    serverId = it.serverId,
                    name = it.name,
                    endpoint = "${it.host}/${it.shareName}",
                    basePath = it.basePath,
                    lastTestStatus = it.lastTestStatus,
                    lastTestTimestampEpochMs = it.lastTestTimestampEpochMs,
                    lastTestLatencyMs = it.lastTestLatencyMs,
                    isTesting = testing.contains(it.serverId),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editorState = MutableStateFlow(ServerEditorUiState())
    val editorState: StateFlow<ServerEditorUiState> = _editorState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun loadServerForEdit(serverId: Long?) {
        if (serverId == null) {
            _editorState.value = ServerEditorUiState()
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
        _editorState.value = _editorState.value.updateField(field, value)
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
                    shareName = state.shareName.trim(),
                    basePath = state.basePath.trim(),
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
                _message.value = listOfNotNull(result.message, result.recoveryHint).joinToString(" ")
            }.onFailure {
                _message.value = "Connection test failed due to an unexpected error."
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
                _message.value = listOfNotNull(result.message, result.recoveryHint).joinToString(" ")
            }.onFailure {
                _message.value = "Connection test failed due to an unexpected error."
            }
            _editorState.value = _editorState.value.copy(isTestingConnection = false)
        }
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

    companion object {
        fun factory(
            serverRepository: ServerRepository,
            credentialStore: CredentialStore,
            testSmbConnectionUseCase: TestSmbConnectionUseCase,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return VaultViewModel(
                        serverRepository = serverRepository,
                        credentialStore = credentialStore,
                        testSmbConnectionUseCase = testSmbConnectionUseCase,
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
    USERNAME,
    PASSWORD,
}

data class ServerListItemUiState(
    val serverId: Long,
    val name: String,
    val endpoint: String,
    val basePath: String,
    val lastTestStatus: String? = null,
    val lastTestTimestampEpochMs: Long? = null,
    val lastTestLatencyMs: Long? = null,
    val isTesting: Boolean = false,
)

data class ServerEditorUiState(
    val editingServerId: Long? = null,
    val name: String = "",
    val host: String = "",
    val shareName: String = "",
    val basePath: String = "",
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
            ServerEditorField.USERNAME -> copy(username = value)
            ServerEditorField.PASSWORD -> copy(password = value)
        }
    }
}
