package skezza.smbsync.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore

data class VaultUiState(
    val servers: List<ServerEntity> = emptyList(),
)

class VaultViewModel(
    private val serverRepository: ServerRepository,
    private val credentialStore: CredentialStore,
) : ViewModel() {
    val uiState: StateFlow<VaultUiState> = serverRepository
        .observeServers()
        .map { VaultUiState(servers = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VaultUiState(),
        )

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            serverRepository.deleteServer(server.serverId)
            credentialStore.deleteSecret(server.credentialAlias)
        }
    }

    companion object {
        fun factory(
            serverRepository: ServerRepository,
            credentialStore: CredentialStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VaultViewModel(serverRepository, credentialStore) as T
                }
            }
    }
}
