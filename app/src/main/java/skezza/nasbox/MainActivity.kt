package skezza.nasbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import skezza.nasbox.ui.navigation.NasBoxApp
import skezza.nasbox.ui.theme.NasBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = AppContainer(applicationContext)
        enableEdgeToEdge()
        setContent {
            NasBoxTheme {
                NasBoxApp(appContainer = appContainer)
            }
        }
    }
}
