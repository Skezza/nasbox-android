package skezza.smbsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import skezza.smbsync.ui.navigation.SMBSyncApp
import skezza.smbsync.ui.theme.SMBSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SMBSyncTheme {
                SMBSyncApp()
            }
        }
    }
}
