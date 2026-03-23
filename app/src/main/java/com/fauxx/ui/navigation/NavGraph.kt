package com.fauxx.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fauxx.ui.screens.DashboardScreen
import com.fauxx.ui.screens.LogScreen
import com.fauxx.ui.screens.ModulesScreen
import com.fauxx.ui.screens.SettingsScreen
import com.fauxx.ui.screens.TargetingScreen

/** Navigation destinations. */
sealed class Screen(val route: String, val label: String) {
    object Dashboard : Screen("dashboard", "Dashboard")
    object Targeting : Screen("targeting", "Targeting")
    object Modules : Screen("modules", "Modules")
    object Log : Screen("log", "Log")
    object Settings : Screen("settings", "Settings")
    object Onboarding : Screen("onboarding", "Onboarding")
}

private val bottomNavItems = listOf(
    Screen.Dashboard to Icons.Default.Dashboard,
    Screen.Targeting to Icons.Default.FilterList,
    Screen.Modules to Icons.Default.Widgets,
    Screen.Log to Icons.Default.History,
    Screen.Settings to Icons.Default.Settings
)

@Composable
fun FauxxNavGraph(showOnboarding: Boolean) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDest = navBackStackEntry?.destination
            val showNav = currentDest?.route != Screen.Onboarding.route

            if (showNav) {
                NavigationBar {
                    bottomNavItems.forEach { (screen, icon) ->
                        NavigationBarItem(
                            icon = { Icon(icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (showOnboarding) Screen.Onboarding.route else Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Onboarding.route) {
                com.fauxx.ui.screens.OnboardingScreen(
                    onFinish = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Targeting.route) { TargetingScreen() }
            composable(Screen.Modules.route) { ModulesScreen() }
            composable(Screen.Log.route) { LogScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
