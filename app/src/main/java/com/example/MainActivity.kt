package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.focus.*
import com.example.ui.habits.HabitsScreen
import com.example.ui.habits.HabitsViewModel
import com.example.ui.navigation.Screen
import com.example.ui.navigation.bottomNavScreens
import com.example.ui.planner.PlannerScreen
import com.example.ui.planner.PlannerViewModel
import com.example.ui.sessions.SessionsScreen
import com.example.ui.sessions.SessionsViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.FocusTrackTheme
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.ui.usage.AppUsageDetailScreen
import com.example.ui.usage.AppUsageDetailViewModel
import com.example.ui.usage.AppUsageDetailViewModelFactory
import com.example.ui.usage.UsageScreen
import com.example.ui.usage.UsageViewModel

class MainActivity : ComponentActivity() {

    private val isPipModeState = mutableStateOf(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipModeState.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as FocusTrackApp
            val userSettings by app.preferencesRepository.userSettingsFlow.collectAsState(
                initial = com.example.data.preferences.UserSettings()
            )

            val isDarkTheme = when (userSettings.isDarkMode) {
                true -> true
                false -> false
                null -> isSystemInDarkTheme()
            }

            FocusTrackTheme(darkTheme = isDarkTheme) {
                val isInPip by isPipModeState
                val remainingSeconds by FocusTimerManager.remainingSeconds.collectAsState()
                val totalSeconds by FocusTimerManager.totalSeconds.collectAsState()
                val isTimerRunning by FocusTimerManager.isRunning.collectAsState()
                val isTimerPaused by FocusTimerManager.isPaused.collectAsState()

                if (isInPip) {
                    FocusPipOverlay(
                        remainingSeconds = remainingSeconds,
                        totalSeconds = totalSeconds,
                        isRunning = isTimerRunning,
                        isPaused = isTimerPaused,
                        onTogglePlayPause = {
                            if (isTimerPaused) FocusTimerManager.resumeTimer(this@MainActivity)
                            else FocusTimerManager.pauseTimer(this@MainActivity)
                        },
                        onStop = { FocusTimerManager.stopTimer(this@MainActivity) }
                    )
                } else {
                    FocusTrackMainApp(
                        initialTarget = intent.getStringExtra("NAVIGATE_TO")
                    )
                }
            }
        }
    }
}

@Composable
fun FocusTrackMainApp(initialTarget: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()

    // Request Notification Permission for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Graceful handling */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (initialTarget == "planner") {
            navController.navigate(Screen.Planner.route)
        } else if (initialTarget == "sessions") {
            navController.navigate(Screen.Sessions.route)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isTopLevelDestination = bottomNavScreens.any { it.route == currentRoute }

    // Shared ViewModels
    val dashboardViewModel: DashboardViewModel = viewModel()
    val sessionsViewModel: SessionsViewModel = viewModel()
    val usageViewModel: UsageViewModel = viewModel()
    val plannerViewModel: PlannerViewModel = viewModel()
    val habitsViewModel: HabitsViewModel = viewModel()
    val focusViewModel: FocusViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    val isTimerRunning by FocusTimerManager.isRunning.collectAsState()
    val isTimerPaused by FocusTimerManager.isPaused.collectAsState()
    val remainingSeconds by FocusTimerManager.remainingSeconds.collectAsState()
    val totalSeconds by FocusTimerManager.totalSeconds.collectAsState()
    val isFloatingWidgetVisible by FocusTimerManager.isFloatingWidgetVisible.collectAsState()

    val isFocusScreen = currentRoute == Screen.Science.route || currentRoute == "focus"

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (isTopLevelDestination) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = NavigationBarDefaults.Elevation,
                        modifier = Modifier.testTag("bottom_navigation_bar")
                    ) {
                        bottomNavScreens.forEach { screen ->
                            val selected = currentRoute == screen.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                        contentDescription = screen.title
                                    )
                                },
                                label = { Text(screen.title) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color(0xFFCCFBF1), // Light cyan rounded pill highlight
                                    selectedIconColor = Color(0xFF0D9488),
                                    selectedTextColor = Color(0xFF0D9488),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag("nav_item_${screen.route}")
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.ScreenTime.route,
                modifier = Modifier.padding(
                    bottom = if (isTopLevelDestination) innerPadding.calculateBottomPadding() else 0.dp
                )
            ) {
                // 1. Screen Time
                composable(Screen.ScreenTime.route) {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onNavigateToUsage = { navController.navigate("usage") },
                        onNavigateToPlanner = { navController.navigate(Screen.Planner.route) },
                        onNavigateToFocus = { navController.navigate(Screen.Science.route) },
                        onOpenSettings = { navController.navigate("settings") },
                        onNavigateToAppDetail = { pkg -> navController.navigate("app_detail/$pkg") }
                    )
                }
                // Alias for dashboard
                composable("dashboard") {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onNavigateToUsage = { navController.navigate("usage") },
                        onNavigateToPlanner = { navController.navigate(Screen.Planner.route) },
                        onNavigateToFocus = { navController.navigate(Screen.Science.route) },
                        onOpenSettings = { navController.navigate("settings") },
                        onNavigateToAppDetail = { pkg -> navController.navigate("app_detail/$pkg") }
                    )
                }

                // 2. Sessions Dashboard
                composable(Screen.Sessions.route) {
                    SessionsScreen(
                        viewModel = sessionsViewModel,
                        onNavigateToAppDetail = { pkg -> navController.navigate("app_detail/$pkg") }
                    )
                }

                // 3. Habits
                composable(Screen.Habits.route) {
                    HabitsScreen(viewModel = habitsViewModel)
                }

                // 4. Planner
                composable(Screen.Planner.route) {
                    PlannerScreen(viewModel = plannerViewModel)
                }

                // 5. Science
                composable(Screen.Science.route) {
                    FocusScreen(viewModel = focusViewModel)
                }
                // Alias for focus
                composable("focus") {
                    FocusScreen(viewModel = focusViewModel)
                }

                // Sub-destinations
                composable("usage") {
                    UsageScreen(
                        viewModel = usageViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToAppDetail = { pkg -> navController.navigate("app_detail/$pkg") }
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                // Dedicated App Usage Detail & Hourly Timeline Screen
                composable(
                    route = "app_detail/{packageName}",
                    arguments = listOf(navArgument("packageName") { type = NavType.StringType })
                ) { backStackEntry ->
                    val pkg = backStackEntry.arguments?.getString("packageName") ?: ""
                    val detailViewModel: AppUsageDetailViewModel = viewModel(
                        factory = AppUsageDetailViewModelFactory(pkg),
                        key = "detail_$pkg"
                    )
                    AppUsageDetailScreen(
                        viewModel = detailViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }

        // In-App Floating Mini-Timer Widget (when user is browsing other screens while timer is running)
        AnimatedVisibility(
            visible = isTimerRunning && (!isFocusScreen || isFloatingWidgetVisible),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    bottom = if (isTopLevelDestination) 90.dp else 24.dp,
                    end = 8.dp
                )
        ) {
            FocusInAppMiniWidget(
                remainingSeconds = remainingSeconds,
                totalSeconds = totalSeconds,
                isRunning = isTimerRunning,
                isPaused = isTimerPaused,
                onExpand = {
                    FocusTimerManager.dismissFloatingWidget()
                    navController.navigate(Screen.Science.route) {
                        launchSingleTop = true
                    }
                },
                onTogglePlayPause = {
                    if (isTimerPaused) FocusTimerManager.resumeTimer(context)
                    else FocusTimerManager.pauseTimer(context)
                },
                onDismiss = {
                    FocusTimerManager.dismissFloatingWidget()
                }
            )
        }
    }
}
