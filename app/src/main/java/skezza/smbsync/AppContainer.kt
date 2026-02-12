package skezza.smbsync

import android.content.Context
import skezza.smbsync.data.db.DatabaseProvider
import skezza.smbsync.data.repository.DefaultServerRepository
import skezza.smbsync.data.repository.ServerRepository
import skezza.smbsync.data.security.AndroidKeystoreCredentialStore
import skezza.smbsync.data.security.CredentialStore

class AppContainer(context: Context) {
    private val database = DatabaseProvider.get(context)

    val serverRepository: ServerRepository = DefaultServerRepository(database.serverDao())
    val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(context)
}
