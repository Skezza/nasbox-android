package skezza.smbsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun RootPlaceholderScreen(
    title: String,
    description: String,
    primaryActionLabel: String,
    primaryActionIcon: ImageVector,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String,
    secondaryActionIcon: ImageVector,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        ElevatedButton(onClick = onPrimaryAction) {
            Icon(imageVector = primaryActionIcon, contentDescription = null)
            Text(text = primaryActionLabel, modifier = Modifier.padding(start = 8.dp))
        }
        Button(onClick = onSecondaryAction) {
            Icon(imageVector = secondaryActionIcon, contentDescription = null)
            Text(text = secondaryActionLabel, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun EditorPlaceholderScreen(
    title: String,
    description: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onNavigateBack) {
            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
            Text(text = "Back", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
