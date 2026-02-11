package skezza.smbsync.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
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
import skezza.smbsync.di.AppContainer
import skezza.smbsync.ui.screens.EditorPlaceholderScreen
import skezza.smbsync.ui.screens.RootPlaceholderScreen
import skezza.smbsync.ui.vault.ServerEditorScreen
import skezza.smbsync.ui.vault.ServerEditorViewModel
import skezza.smbsync.ui.vault.VaultScreen
import skezza.smbsync.ui.vault.VaultViewModel

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_PLANS = "plans"
private const val ROUTE_VAULT = "vault"
private const val ROUTE_SERVER_EDITOR = "serverEditor"
private const val ROUTE_RUN_DETAIL = "runDetail"

private const val SERVER_ID_ARG = "serverId"
private const val ROUTE_SERVER_EDITOR_PATTERN = "$ROUTE_SERVER_EDITOR?$SERVER_ID_ARG={$SERVER_ID_ARG}"

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
    val appContainer = remember { AppContainer(navController.context.applicationContext) }
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
                    primaryActionIcon = Icons.Default.Dashboard,
                    onPrimaryAction = { navController.navigate(ROUTE_RUN_DETAIL) },
                    secondaryActionLabel = "Open Plans",
                    secondaryActionIcon = Icons.Default.Folder,
                    onSecondaryAction = { navController.navigate(ROUTE_PLANS) },
                )
            }
            composable(ROUTE_PLANS) {
                EditorPlaceholderScreen(
                    title = "Plans",
                    description = "Plan management remains in a placeholder state until Phase 4.",
                    onNavigateBack = { navController.navigate(ROUTE_DASHBOARD) },
                )
            }
            composable(ROUTE_VAULT) {
                val viewModel: VaultViewModel = viewModel(
                    factory = VaultViewModel.factory(
                        serverRepository = appContainer.serverRepository,
                        credentialStore = appContainer.credentialStore,
                    ),
                )
                VaultScreen(
                    viewModel = viewModel,
                    onAddServer = { navController.navigate(ROUTE_SERVER_EDITOR) },
                    onEditServer = { serverId ->
                        navController.navigate("$ROUTE_SERVER_EDITOR?$SERVER_ID_ARG=$serverId")
                    },
                )
            }
            composable(
                route = ROUTE_SERVER_EDITOR_PATTERN,
                arguments = listOf(
                    navArgument(SERVER_ID_ARG) {
                        nullable = true
                        defaultValue = null
                        type = NavType.LongType
                    },
                ),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getLong(SERVER_ID_ARG)
                val viewModel: ServerEditorViewModel = viewModel(
                    key = "server-editor-$serverId",
                    factory = ServerEditorViewModel.factory(
                        serverId = serverId,
                        serverRepository = appContainer.serverRepository,
                        credentialStore = appContainer.credentialStore,
                    ),
                )
                ServerEditorScreen(
                    viewModel = viewModel,
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
