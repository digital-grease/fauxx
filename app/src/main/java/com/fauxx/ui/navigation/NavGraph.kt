package com.fauxx.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fauxx.R
import com.fauxx.locale.SupportedLocale
import com.fauxx.ui.screens.DashboardScreen
import com.fauxx.ui.viewmodels.LanguagePickerViewModel
import com.fauxx.ui.screens.FaqScreen
import com.fauxx.ui.screens.LogScreen
import com.fauxx.ui.screens.ModulesScreen
import com.fauxx.ui.screens.AboutScreen
import com.fauxx.ui.screens.SettingsScreen
import com.fauxx.ui.screens.TargetingScreen

private const val README_URL = "https://github.com/digital-grease/fauxx#readme"
// Opens the issue form picker (crash / bug / feature / question / other). Lands users
// on the structured forms rather than a blank-issue editor — also enabled because
// .github/ISSUE_TEMPLATE/config.yml sets blank_issues_enabled: false.
private const val ISSUES_URL = "https://github.com/digital-grease/fauxx/issues/new/choose"

/** Navigation destinations. */
sealed class Screen(val route: String, @androidx.annotation.StringRes val labelRes: Int) {
    object Dashboard : Screen("dashboard", R.string.nav_dashboard)
    object Targeting : Screen("targeting", R.string.nav_targeting)
    object Modules : Screen("modules", R.string.nav_modules)
    object Log : Screen("log", R.string.nav_log)
    object Settings : Screen("settings", R.string.nav_settings)
    object Onboarding : Screen("onboarding", R.string.nav_onboarding)
    object About : Screen("about", R.string.about_title)
    object Faq : Screen("faq", R.string.faq_title)
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
                            icon = { Icon(icon, contentDescription = stringResource(screen.labelRes)) },
                            label = { Text(stringResource(screen.labelRes)) },
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
                            // popBackStack() returns true if there was a screen below (edit
                            // mode came from Targeting). Returns false on first-launch
                            // onboarding when this is the only entry — fall through to
                            // Dashboard in that case.
                            if (!navController.popBackStack()) {
                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                                }
                            }
                        }
                    )
                }
                composable(Screen.Dashboard.route) { DashboardScreen() }
                composable(Screen.Targeting.route) {
                    TargetingScreen(
                        onEditProfile = { navController.navigate(Screen.Onboarding.route) }
                    )
                }
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
                composable(Screen.Faq.route) {
                    FaqScreen(onBack = { navController.popBackStack() })
                }
            }

            if (showHelp) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    LanguagePickerButton()
                    HelpMenuButton(navController = navController)
                }
            }
        }
    }
}

@Composable
private fun HelpMenuButton(
    navController: NavController,
    modifier: Modifier = Modifier
) {
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
                text = { Text(stringResource(R.string.help_menu_faq)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
                onClick = {
                    expanded = false
                    // launchSingleTop prevents stacking duplicates if the user taps FAQ
                    // repeatedly from different screens.
                    navController.navigate(Screen.Faq.route) { launchSingleTop = true }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.help_menu_help)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
                onClick = {
                    expanded = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(README_URL)))
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.help_menu_contact)) },
                leadingIcon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                onClick = {
                    expanded = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                }
            )
        }
    }
}

/**
 * Quick-access language picker rendered to the left of the help-menu button. Lets the
 * user switch app language without traversing Settings — useful for users who land in
 * the app on a non-preferred locale and want to switch immediately.
 *
 * State + persistence delegated to [LanguagePickerViewModel] (which is in turn a thin
 * wrapper over [com.fauxx.locale.LocaleManager]). The Settings screen's own picker
 * shares the same `LocaleManager.userOverrideFlow`, so both stay in sync. Selecting an
 * unshipped locale is a no-op in the VM, matching Settings' gating; the dropdown still
 * renders those entries so users see the planned-locale list, but they're disabled
 * with a "(coming soon)" suffix.
 */
@Composable
private fun LanguagePickerButton(
    modifier: Modifier = Modifier,
    viewModel: LanguagePickerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = stringResource(R.string.language_picker_content_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // System-default row: clears the override so the app follows the device locale.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_language_system_default)) },
                leadingIcon = {
                    if (state.userOverride == null) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    }
                },
                onClick = {
                    expanded = false
                    viewModel.setLanguage(null)
                }
            )
            SupportedLocale.values().forEach { locale ->
                val shipped = locale in state.shippedLocales
                DropdownMenuItem(
                    text = {
                        val suffix = if (!shipped) {
                            " (" + stringResource(R.string.settings_language_coming_soon) + ")"
                        } else ""
                        Text(locale.displayName + suffix)
                    },
                    leadingIcon = {
                        if (state.userOverride == locale) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    enabled = shipped,
                    onClick = {
                        expanded = false
                        viewModel.setLanguage(locale)
                    }
                )
            }
        }
    }
}
