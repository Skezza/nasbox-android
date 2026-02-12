package skezza.smbsync.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.MutableCreationExtras
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test
import skezza.smbsync.data.db.ServerEntity
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore

class VaultViewModelFactoryTest {

    @Test
    fun factoryCreateWithClassAndExtras_returnsVaultViewModel() {
        val factory = VaultViewModel.factory(FakeServerRepository(), FakeCredentialStore())

        val vmFromClass = factory.create(VaultViewModel::class.java)
        val vmFromExtras = factory.create(VaultViewModel::class.java, MutableCreationExtras())

        assertTrue(vmFromClass is VaultViewModel)
        assertTrue(vmFromExtras is VaultViewModel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun factoryRejectsUnknownViewModelClass() {
        val factory = VaultViewModel.factory(FakeServerRepository(), FakeCredentialStore())
        factory.create(UnknownViewModel::class.java)
    }

    private class FakeServerRepository : ServerRepository {
        override fun observeServers(): Flow<List<ServerEntity>> = flowOf(emptyList())
        override suspend fun getServer(serverId: Long): ServerEntity? = null
        override suspend fun createServer(server: ServerEntity): Long = 1L
        override suspend fun updateServer(server: ServerEntity) = Unit
        override suspend fun deleteServer(serverId: Long) = Unit
    }

    private class FakeCredentialStore : CredentialStore {
        override suspend fun savePassword(alias: String, password: String) = Unit
        override suspend fun loadPassword(alias: String): String? = null
        override suspend fun deletePassword(alias: String) = Unit
    }

    private class UnknownViewModel : ViewModel()
}
