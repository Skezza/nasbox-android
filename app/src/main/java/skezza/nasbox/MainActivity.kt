package skezza.nasbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import skezza.nasbox.ui.navigation.NasBoxApp
import skezza.nasbox.ui.theme.NasBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = AppContainer(applicationContext)
        lifecycleScope.launch {
            appContainer.reconcileSchedulesOnStartup()
            appContainer.reconcileStaleActiveRunsUseCase()
        }
        enableEdgeToEdge()
        setContent {
            NasBoxTheme {
                NasBoxApp(appContainer = appContainer)
            }
        }
    }
}
