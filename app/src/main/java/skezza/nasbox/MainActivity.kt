package skezza.nasbox

import android.app.ActivityManager
import android.graphics.BitmapFactory
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
        val openRunId = intent?.getLongExtra(EXTRA_OPEN_RUN_ID, -1L)?.takeIf { it > 0L }
        val appContainer = AppContainer(applicationContext)
        lifecycleScope.launch {
            appContainer.reconcileSchedulesOnStartup()
            appContainer.reconcileRunsOnStartup()
        }
        applyRecentsIcon()
        enableEdgeToEdge()
        setContent {
            NasBoxTheme {
                NasBoxApp(
                    appContainer = appContainer,
                    openRunId = openRunId,
                )
            }
        }
    }

    private fun applyRecentsIcon() {
        val label = getString(R.string.app_name)
        val iconBitmap = BitmapFactory.decodeResource(resources, R.drawable.nasbox_icon_activity_opaque_1024)
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(label, iconBitmap))
    }

    companion object {
        const val EXTRA_OPEN_RUN_ID = "open_run_id"
    }
}
