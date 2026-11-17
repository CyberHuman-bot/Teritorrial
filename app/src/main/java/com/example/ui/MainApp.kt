package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.data.repository.AiAssistantRepository
import com.example.data.repository.AlpacaBootstrapEngine
import com.example.data.repository.ProfileRepository
import com.example.data.repository.SystemMonitorRepository
import com.example.firebase.authStateFlow
import com.example.ui.auth.AuthScreen
import com.example.ui.screens.CodeStudioScreen
import com.example.ui.screens.MonitorScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextMuted
import com.example.viewmodel.CodeStudioViewModel
import com.example.viewmodel.ProfilesViewModel
import com.example.viewmodel.TerminalMonitorViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

sealed class AppRoute(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    data object Terminal : AppRoute("terminal", "Terminal", Icons.Rounded.Terminal, Icons.Outlined.Terminal)
    data object Monitor : AppRoute("monitor", "Monitor", Icons.Rounded.MonitorHeart, Icons.Outlined.MonitorHeart)
    data object CodeStudio : AppRoute("code_studio", "Code AI", Icons.Rounded.Code, Icons.Outlined.Code)
    data object Profiles : AppRoute("profiles", "Profiles", Icons.Rounded.ManageAccounts, Icons.Outlined.ManageAccounts)
}

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedShellHost(currentUserId = user.uid)
        }
    }
}

@Composable
private fun AuthenticatedShellHost(currentUserId: String) {
    val context = LocalContext.current.applicationContext
    val systemMonitor = remember(context) { SystemMonitorRepository(context) }
    val shellEngine = remember(context, systemMonitor) { AlpacaBootstrapEngine(context, systemMonitor) }
    val aiRepository = remember { AiAssistantRepository() }

    val terminalViewModel: TerminalMonitorViewModel = viewModel(
        key = "terminal_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                TerminalMonitorViewModel(
                    currentUserId = currentUserId,
                    profileRepository = ProfileRepository(db),
                    shellEngine = shellEngine,
                    systemMonitor = systemMonitor,
                    aiRepository = aiRepository,
                )
            }
        }
    )

    val codeStudioViewModel: CodeStudioViewModel = viewModel(
        key = "studio_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                CodeStudioViewModel(
                    currentUserId = currentUserId,
                    profileRepository = ProfileRepository(db),
                    aiRepository = aiRepository,
                    shellEngine = shellEngine,
                )
            }
        }
    )

    val profilesViewModel: ProfilesViewModel = viewModel(
        key = "profiles_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                ProfilesViewModel(
                    currentUserId = currentUserId,
                    profileRepository = ProfileRepository(db),
                )
            }
        }
    )

    val profilesState by terminalViewModel.profilesState.collectAsStateWithLifecycle()
    val activeProfile by terminalViewModel.activeProfile.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppRoute.Terminal.route

    val destinations = remember {
        listOf(AppRoute.Terminal, AppRoute.Monitor, AppRoute.CodeStudio, AppRoute.Profiles)
    }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        if (useRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                NavigationRail(
                    containerColor = ObsidianSurface,
                ) {
                    destinations.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTo(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = CyanElectric,
                                selectedTextColor = CyanElectric,
                                indicatorColor = CyanElectric.copy(alpha = 0.16f),
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                            ),
                            modifier = Modifier.testTag("nav_rail_${dest.route}"),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).imePadding()) {
                    AppNavHost(
                        navController = navController,
                        terminalViewModel = terminalViewModel,
                        codeStudioViewModel = codeStudioViewModel,
                        profilesViewModel = profilesViewModel,
                        profilesState = profilesState,
                        activeProfile = activeProfile,
                        onNavigateTo = ::navigateTo,
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = ObsidianBg,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(
                        containerColor = ObsidianSurface,
                        windowInsets = WindowInsets.navigationBars,
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label,
                                    )
                                },
                                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CyanElectric,
                                    selectedTextColor = CyanElectric,
                                    indicatorColor = CyanElectric.copy(alpha = 0.16f),
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted,
                                ),
                                modifier = Modifier.testTag("nav_bar_${dest.route}"),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                ) {
                    AppNavHost(
                        navController = navController,
                        terminalViewModel = terminalViewModel,
                        codeStudioViewModel = codeStudioViewModel,
                        profilesViewModel = profilesViewModel,
                        profilesState = profilesState,
                        activeProfile = activeProfile,
                        onNavigateTo = ::navigateTo,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    terminalViewModel: TerminalMonitorViewModel,
    codeStudioViewModel: CodeStudioViewModel,
    profilesViewModel: ProfilesViewModel,
    profilesState: com.example.data.model.UiState<List<com.example.data.model.TerminalProfile>>,
    activeProfile: com.example.data.model.TerminalProfile?,
    onNavigateTo: (String) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = AppRoute.Terminal.route,
    ) {
        composable(AppRoute.Terminal.route) {
            TerminalScreen(
                viewModel = terminalViewModel,
                onNavigateToMonitor = { onNavigateTo(AppRoute.Monitor.route) },
            )
        }
        composable(AppRoute.Monitor.route) {
            MonitorScreen(
                viewModel = terminalViewModel,
                onRunCommandInTerminal = { cmd ->
                    terminalViewModel.executeCommand(cmd)
                    onNavigateTo(AppRoute.Terminal.route)
                },
            )
        }
        composable(AppRoute.CodeStudio.route) {
            CodeStudioScreen(
                viewModel = codeStudioViewModel,
                activeProfile = activeProfile,
                onDeployAndRunScript = { title, code ->
                    terminalViewModel.deployAndRunScript(title, code)
                    onNavigateTo(AppRoute.Terminal.route)
                },
                onDeployScriptOnly = { title, code ->
                    terminalViewModel.deployScriptOnly(title, code)
                },
            )
        }
        composable(AppRoute.Profiles.route) {
            ProfilesScreen(
                profilesState = profilesState,
                activeProfile = activeProfile,
                profilesViewModel = profilesViewModel,
                onRerunHistoryCommand = { cmd ->
                    terminalViewModel.executeCommand(cmd)
                    onNavigateTo(AppRoute.Terminal.route)
                },
            )
        }
    }
}
