package skezza.smbsync.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import skezza.smbsync.AppContainer
import skezza.smbsync.ui.screens.EditorPlaceholderScreen
import skezza.smbsync.ui.screens.RootPlaceholderScreen
import skezza.smbsync.ui.vault.ServerEditorScreen
import skezza.smbsync.ui.vault.VaultScreen
import skezza.smbsync.ui.vault.VaultViewModel

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_PLANS = "plans"
private const val ROUTE_VAULT = "vault"
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
fun SMBSyncApp(
    appContainer: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val vaultViewModel: VaultViewModel = viewModel(
        factory = VaultViewModel.factory(
            serverRepository = appContainer.serverRepository,
            credentialStore = appContainer.credentialStore,
            testSmbConnectionUseCase = appContainer.testSmbConnectionUseCase,
        ),
    )

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
                        label = { Text(destination.label) },
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
                    secondaryActionIcon = Icons.Default.Folder,
                    onSecondaryAction = { navController.navigate(ROUTE_PLANS) },
                )
            }
            composable(ROUTE_PLANS) {
                RootPlaceholderScreen(
                    title = "Plans",
                    description = "Plan list placeholder. CRUD and media/server binding will be implemented in later phases.",
                    primaryActionLabel = "Coming in Phase 4",
                    primaryActionIcon = Icons.Default.Folder,
                    onPrimaryAction = {},
                    secondaryActionLabel = "Open Vault",
                    secondaryActionIcon = Icons.Default.Lock,
                    onSecondaryAction = { navController.navigate(ROUTE_VAULT) },
                )
            }
            composable(ROUTE_VAULT) {
                VaultScreen(
                    viewModel = vaultViewModel,
                    onAddServer = { navController.navigate(ROUTE_SERVER_EDITOR) },
                    onEditServer = { serverId -> navController.navigate("$ROUTE_SERVER_EDITOR/$serverId") },
                )
            }
            composable(ROUTE_SERVER_EDITOR) {
                ServerEditorScreen(
                    viewModel = vaultViewModel,
                    serverId = null,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_SERVER_EDITOR/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            ) { backStackEntry ->
                ServerEditorScreen(
                    viewModel = vaultViewModel,
                    serverId = backStackEntry.arguments?.getLong("serverId"),
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

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
private fun SMBSyncAppPreview() {
    MaterialTheme {
        // Preview intentionally omitted for container-backed app setup.
    }
}
