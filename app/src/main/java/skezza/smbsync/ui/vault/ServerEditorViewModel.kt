package skezza.smbsync.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.domain.vault.ServerFormInput
import skezza.smbsync.domain.vault.ServerValidationResult
import skezza.smbsync.domain.vault.validateServerForm

data class ServerEditorUiState(
    val name: String = "",
    val host: String = "",
    val shareName: String = "",
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    val validation: ServerValidationResult = ServerValidationResult(),
    val isSaving: Boolean = false,
    val saveCompleted: Boolean = false,
)

class ServerEditorViewModel(
    private val serverId: Long?,
    private val serverRepository: ServerRepository,
    private val credentialStore: CredentialStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ServerEditorUiState())
    val uiState: StateFlow<ServerEditorUiState> = _uiState.asStateFlow()

    init {
        if (serverId != null) {
            viewModelScope.launch {
                serverRepository.getServer(serverId)?.let { server ->
                    _uiState.update {
                        it.copy(
                            name = server.name,
                            host = server.host,
                            shareName = server.shareName,
                            basePath = server.basePath,
                            username = server.username,
                            password = credentialStore.loadSecret(server.credentialAlias).orEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }
    fun onHostChange(value: String) = _uiState.update { it.copy(host = value) }
    fun onShareNameChange(value: String) = _uiState.update { it.copy(shareName = value) }
    fun onBasePathChange(value: String) = _uiState.update { it.copy(basePath = value) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }

    fun save() {
        val current = _uiState.value
        val validation = validateServerForm(
            ServerFormInput(
                name = current.name,
                host = current.host,
                shareName = current.shareName,
                basePath = current.basePath,
                username = current.username,
                password = current.password,
            ),
        )
        if (!validation.isValid) {
            _uiState.update { it.copy(validation = validation) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, validation = validation) }
            val existing = serverId?.let { serverRepository.getServer(it) }
            val alias = existing?.credentialAlias ?: "server-${UUID.randomUUID()}"
            credentialStore.saveSecret(alias = alias, secret = current.password)

            val entity = ServerEntity(
                serverId = existing?.serverId ?: 0,
                name = current.name.trim(),
                host = current.host.trim(),
                shareName = current.shareName.trim(),
                basePath = current.basePath.trim(),
                username = current.username.trim(),
                credentialAlias = alias,
                lastTestStatus = existing?.lastTestStatus,
                lastTestTimestampEpochMs = existing?.lastTestTimestampEpochMs,
            )
            if (existing == null) {
                serverRepository.createServer(entity)
            } else {
                serverRepository.updateServer(entity)
            }
            _uiState.update { it.copy(isSaving = false, saveCompleted = true) }
        }
    }

    companion object {
        fun factory(
            serverId: Long?,
            serverRepository: ServerRepository,
            credentialStore: CredentialStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ServerEditorViewModel(serverId, serverRepository, credentialStore) as T
                }
            }
    }
}
