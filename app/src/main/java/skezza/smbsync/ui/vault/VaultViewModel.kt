package skezza.smbsync.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.domain.vault.ServerInput
import skezza.smbsync.domain.vault.ServerValidationResult
import skezza.smbsync.domain.vault.ValidateServerInputUseCase

class VaultViewModel(
    private val serverRepository: ServerRepository,
    private val credentialStore: CredentialStore,
    private val validateServerInput: ValidateServerInputUseCase = ValidateServerInputUseCase(),
) : ViewModel() {

    val servers: StateFlow<List<ServerListItemUiState>> = serverRepository.observeServers()
        .map { servers ->
            servers.map {
                ServerListItemUiState(
                    serverId = it.serverId,
                    name = it.name,
                    endpoint = "${it.host}/${it.shareName}",
                    basePath = it.basePath,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editorState = MutableStateFlow(ServerEditorUiState())
    val editorState: StateFlow<ServerEditorUiState> = _editorState.asStateFlow()

    fun loadServerForEdit(serverId: Long?) {
        if (serverId == null) {
            _editorState.value = ServerEditorUiState()
            return
        }

        viewModelScope.launch {
            val server = serverRepository.getServer(serverId) ?: return@launch
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
            _editorState.value = ServerEditorUiState()
            onSuccess()
        }
    }

    fun deleteServer(serverId: Long) {
        viewModelScope.launch {
            val existing = serverRepository.getServer(serverId) ?: return@launch
            credentialStore.deletePassword(existing.credentialAlias)
            serverRepository.deleteServer(serverId)
        }
    }

    private fun newAlias(): String = "vault/${UUID.randomUUID()}"

    companion object {
        fun factory(
            serverRepository: ServerRepository,
            credentialStore: CredentialStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return VaultViewModel(serverRepository, credentialStore) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
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
