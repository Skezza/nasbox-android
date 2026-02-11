package skezza.smbsync.di

import android.content.Context
import skezza.smbsync.data.db.DatabaseProvider
import skezza.smbsync.data.repository.DefaultServerRepository
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.CredentialStore
import skezza.smbsync.data.security.KeystoreCredentialStore

class AppContainer(context: Context) {
    private val database = DatabaseProvider.get(context)

    val serverRepository: ServerRepository = DefaultServerRepository(database.serverDao())
    val credentialStore: CredentialStore = KeystoreCredentialStore(context)
}
