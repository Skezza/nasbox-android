package skezza.nasbox.ui.navigation

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
import androidx.compose.foundation.layout.WindowInsets
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
import skezza.nasbox.AppContainer
import skezza.nasbox.ui.audit.AuditRunDetailScreen
import skezza.nasbox.ui.audit.AuditScreen
import skezza.nasbox.ui.audit.AuditViewModel
import skezza.nasbox.ui.dashboard.DashboardRunDetailScreen
import skezza.nasbox.ui.dashboard.DashboardRunDetailViewModel
import skezza.nasbox.ui.dashboard.DashboardScreen
import skezza.nasbox.ui.dashboard.DashboardViewModel
import skezza.nasbox.ui.plans.PlanEditorScreen
import skezza.nasbox.ui.plans.PlansScreen
import skezza.nasbox.ui.plans.PlansViewModel
import skezza.nasbox.ui.vault.ServerEditorScreen
import skezza.nasbox.ui.vault.VaultScreen
import skezza.nasbox.ui.vault.VaultViewModel

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_PLANS = "plans"
private const val ROUTE_VAULT = "vault"
private const val ROUTE_SERVER_EDITOR = "serverEditor"
private const val ROUTE_PLAN_EDITOR = "planEditor"
private const val ROUTE_AUDIT = "audit"
private const val ROUTE_AUDIT_RUN = "auditRun"
private const val ROUTE_DASHBOARD_RUN = "dashboardRun"

private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD(route = ROUTE_DASHBOARD, label = "Dashboard", icon = Icons.Default.Dashboard),
    PLANS(route = ROUTE_PLANS, label = "Jobs", icon = Icons.Default.Folder),
    VAULT(route = ROUTE_VAULT, label = "Servers", icon = Icons.Default.Lock),
}

@Composable
fun NasBoxApp(
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
            discoverSmbServersUseCase = appContainer.discoverSmbServersUseCase,
            browseSmbDestinationUseCase = appContainer.browseSmbDestinationUseCase,
        ),
    )
    val plansViewModel: PlansViewModel = viewModel(
        factory = PlansViewModel.factory(
            planRepository = appContainer.planRepository,
            serverRepository = appContainer.serverRepository,
            listMediaAlbumsUseCase = appContainer.listMediaAlbumsUseCase,
            enqueuePlanRunUseCase = appContainer.enqueuePlanRunUseCase,
            planScheduleCoordinator = appContainer.planScheduleCoordinator,
        ),
    )
    val dashboardViewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.factory(
            planRepository = appContainer.planRepository,
            serverRepository = appContainer.serverRepository,
            runRepository = appContainer.runRepository,
            stopRunUseCase = appContainer.stopRunUseCase,
            reconcileStaleActiveRunsUseCase = appContainer.reconcileStaleActiveRunsUseCase,
        ),
    )
    val auditViewModel: AuditViewModel = viewModel(
        factory = AuditViewModel.factory(
            planRepository = appContainer.planRepository,
            runRepository = appContainer.runRepository,
            runLogRepository = appContainer.runLogRepository,
        ),
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                DashboardScreen(
                    viewModel = dashboardViewModel,
                    onOpenAudit = { navController.navigate(ROUTE_AUDIT) },
                    onOpenRunAudit = { runId -> navController.navigate("$ROUTE_DASHBOARD_RUN/$runId") },
                    onOpenCurrentRunDetail = { runId -> navController.navigate("$ROUTE_DASHBOARD_RUN/$runId") },
                )
            }
            composable(
                route = "$ROUTE_DASHBOARD_RUN/{runId}",
                arguments = listOf(navArgument("runId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val runId = backStackEntry.arguments?.getLong("runId") ?: 0L
                val runDetailViewModel: DashboardRunDetailViewModel = viewModel(
                    key = "dashboardRunDetail-$runId",
                    factory = DashboardRunDetailViewModel.factory(
                        runId = runId,
                        planRepository = appContainer.planRepository,
                        runRepository = appContainer.runRepository,
                        runLogRepository = appContainer.runLogRepository,
                    ),
                )
                DashboardRunDetailScreen(
                    viewModel = runDetailViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_PLANS) {
                PlansScreen(
                    viewModel = plansViewModel,
                    onAddPlan = { navController.navigate(ROUTE_PLAN_EDITOR) },
                    onEditPlan = { planId -> navController.navigate("$ROUTE_PLAN_EDITOR/$planId") },
                    onRunPlan = { planId -> plansViewModel.runPlanNow(planId) },
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
            composable(ROUTE_PLAN_EDITOR) {
                PlanEditorScreen(
                    viewModel = plansViewModel,
                    planId = null,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_PLAN_EDITOR/{planId}",
                arguments = listOf(navArgument("planId") { type = NavType.LongType }),
            ) { backStackEntry ->
                PlanEditorScreen(
                    viewModel = plansViewModel,
                    planId = backStackEntry.arguments?.getLong("planId"),
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
            composable(ROUTE_AUDIT) {
                AuditScreen(
                    viewModel = auditViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenRun = { runId -> navController.navigate("$ROUTE_AUDIT_RUN/$runId") },
                )
            }
            composable(
                route = "$ROUTE_AUDIT_RUN/{runId}",
                arguments = listOf(navArgument("runId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val runId = backStackEntry.arguments?.getLong("runId") ?: 0L
                AuditRunDetailScreen(
                    viewModel = auditViewModel,
                    runId = runId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
private fun NasBoxAppPreview() {
    MaterialTheme {
        // Preview intentionally omitted for container-backed app setup.
    }
}
