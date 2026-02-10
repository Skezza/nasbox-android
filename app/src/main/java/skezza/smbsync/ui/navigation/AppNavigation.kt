package skezza.smbsync.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import skezza.smbsync.ui.screens.EditorPlaceholderScreen
import skezza.smbsync.ui.screens.RootPlaceholderScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_PLANS = "plans"
private const val ROUTE_VAULT = "vault"
private const val ROUTE_PLAN_EDITOR = "planEditor"
private const val ROUTE_SERVER_EDITOR = "serverEditor"
private const val ROUTE_RUN_DETAIL = "runDetail"

private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD(route = ROUTE_DASHBOARD, label = "Dashboard", icon = Icons.Default.Dashboard),
    PLANS(route = ROUTE_PLANS, label = "Plans", icon = Icons.Default.Folder),
    VAULT(route = ROUTE_VAULT, label = "Vault", icon = Icons.Default.Lock),
}

@Composable
fun SMBSyncApp(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry = navController.currentBackStackEntryAsState().value
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_DASHBOARD,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ROUTE_DASHBOARD) {
                RootPlaceholderScreen(
                    title = "Dashboard",
                    description = "Mission control placeholder. Run summaries and health indicators will be added in later phases.",
                    primaryActionLabel = "Run detail",
                    primaryActionIcon = Icons.Default.PlayArrow,
                    onPrimaryAction = { navController.navigate(ROUTE_RUN_DETAIL) },
                    secondaryActionLabel = "Open Plans",
                    secondaryActionIcon = Icons.Default.Edit,
                    onSecondaryAction = { navController.navigate(ROUTE_PLANS) },
                )
            }
            composable(ROUTE_PLANS) {
                RootPlaceholderScreen(
                    title = "Plans",
                    description = "Plan list placeholder. CRUD and media/server binding will be implemented in later phases.",
                    primaryActionLabel = "New plan",
                    primaryActionIcon = Icons.Default.Add,
                    onPrimaryAction = { navController.navigate(ROUTE_PLAN_EDITOR) },
                    secondaryActionLabel = "Open Vault",
                    secondaryActionIcon = Icons.Default.Storage,
                    onSecondaryAction = { navController.navigate(ROUTE_VAULT) },
                )
            }
            composable(ROUTE_VAULT) {
                RootPlaceholderScreen(
                    title = "Vault",
                    description = "Server list placeholder. Secure credential handling and connection checks will be implemented in later phases.",
                    primaryActionLabel = "Add server",
                    primaryActionIcon = Icons.Default.Add,
                    onPrimaryAction = { navController.navigate(ROUTE_SERVER_EDITOR) },
                    secondaryActionLabel = "Back to Dashboard",
                    secondaryActionIcon = Icons.Default.Dashboard,
                    onSecondaryAction = { navController.navigate(ROUTE_DASHBOARD) },
                )
            }
            composable(ROUTE_PLAN_EDITOR) {
                EditorPlaceholderScreen(
                    title = "Plan Editor",
                    description = "Plan editor route is wired and ready for persistence-backed fields in Phase 1+.",
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_SERVER_EDITOR) {
                EditorPlaceholderScreen(
                    title = "Server Editor",
                    description = "Server editor route is wired and ready for secure credential integration in Phase 2.",
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_RUN_DETAIL) {
                EditorPlaceholderScreen(
                    title = "Run Detail",
                    description = "Optional run detail route is wired for future run metrics and diagnostic logs.",
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SMBSyncAppPreview() {
    MaterialTheme {
        SMBSyncApp()
    }
}
