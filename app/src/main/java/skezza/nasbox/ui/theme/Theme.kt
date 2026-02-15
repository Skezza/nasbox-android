package skezza.nasbox.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val NasBoxDarkColorScheme = darkColorScheme(
    primary = NasBoxSignal,
    onPrimary = NasBoxFog,
    primaryContainer = NasBoxMarine,
    onPrimaryContainer = NasBoxFog,
    secondary = NasBoxMarine,
    onSecondary = NasBoxFog,
    secondaryContainer = NasBoxInk,
    onSecondaryContainer = NasBoxFog,
    tertiary = NasBoxSun,
    onTertiary = NasBoxInk,
    background = NasBoxInk,
    onBackground = NasBoxFog,
    surface = NasBoxCharcoal,
    onSurface = NasBoxFog,
    surfaceVariant = NasBoxSlate,
    outline = NasBoxSlate,
)

private val NasBoxLightColorScheme = lightColorScheme(
    primary = NasBoxMarine,
    onPrimary = NasBoxFog,
    primaryContainer = NasBoxSignal,
    onPrimaryContainer = NasBoxInk,
    secondary = NasBoxSignal,
    onSecondary = NasBoxCharcoal,
    secondaryContainer = NasBoxFog,
    onSecondaryContainer = NasBoxInk,
    tertiary = NasBoxSun,
    onTertiary = NasBoxCharcoal,
    background = NasBoxFog,
    onBackground = NasBoxInk,
    surface = NasBoxFog,
    onSurface = NasBoxInk,
    surfaceVariant = NasBoxSlate,
    outline = NasBoxSlate,
)

@Composable
fun NasBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> NasBoxDarkColorScheme
        else -> NasBoxLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
