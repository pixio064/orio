package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object ScreenTime : Screen(
        route = "screen_time",
        title = "Screen Time",
        selectedIcon = Icons.Filled.HourglassBottom,
        unselectedIcon = Icons.Outlined.HourglassEmpty
    )

    object Sessions : Screen(
        route = "sessions",
        title = "Sessions",
        selectedIcon = Icons.Filled.MotionPhotosAuto,
        unselectedIcon = Icons.Outlined.MotionPhotosAuto
    )

    object Habits : Screen(
        route = "habits",
        title = "Habits",
        selectedIcon = Icons.Filled.NotificationsActive,
        unselectedIcon = Icons.Outlined.Notifications
    )

    object Planner : Screen(
        route = "planner",
        title = "Planner",
        selectedIcon = Icons.Filled.CalendarMonth,
        unselectedIcon = Icons.Outlined.CalendarMonth
    )

    object Science : Screen(
        route = "science",
        title = "Science",
        selectedIcon = Icons.Filled.Psychology,
        unselectedIcon = Icons.Outlined.Psychology
    )

    // Backwards compatibility aliases
    val Dashboard: ScreenTime get() = ScreenTime
    val Focus: Science get() = Science
}

val bottomNavScreens = listOf(
    Screen.ScreenTime,
    Screen.Sessions,
    Screen.Habits,
    Screen.Planner,
    Screen.Science
)
