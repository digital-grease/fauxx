package com.fauxx.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fauxx.ui.screens.DashboardScreen
import com.fauxx.ui.screens.LogScreen
import com.fauxx.ui.screens.ModulesScreen
import com.fauxx.ui.screens.AboutScreen
import com.fauxx.ui.screens.SettingsScreen
import com.fauxx.ui.screens.TargetingScreen

private const val README_URL = "https://github.com/digital-grease/fauxx#readme"
private const val ISSUES_URL = "https://github.com/digital-grease/fauxx/issues/new"

/** Navigation destinations. */
sealed class Screen(val route: String, val label: String) {
    object Dashboard : Screen("dashboard", "Dashboard")
    object Targeting : Screen("targeting", "Targeting")
    object Modules : Screen("modules", "Modules")
    object Log : Screen("log", "Log")
    object Settings : Screen("settings", "Settings")
    object Onboarding : Screen("onboarding", "Onboarding")
    object About : Screen("about", "About & Privacy")
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
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val showHelp = currentRoute != Screen.Onboarding.route

        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = if (showOnboarding) Screen.Onboarding.route else Screen.Dashboard.route
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
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onNavigateToAbout = { navController.navigate(Screen.About.route) }
                    )
                }
                composable(Screen.About.route) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
            }

            if (showHelp) {
                HelpMenuButton(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun HelpMenuButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = "Help",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Help") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
                onClick = {
                    expanded = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(README_URL)))
                }
            )
            DropdownMenuItem(
                text = { Text("Contact us") },
                leadingIcon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                onClick = {
                    expanded = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                }
            )
        }
    }
}
