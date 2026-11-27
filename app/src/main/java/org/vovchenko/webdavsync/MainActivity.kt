package org.vovchenko.webdavsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.vovchenko.webdavsync.sync.control.SyncRequestToken
import javax.inject.Inject
import org.vovchenko.webdavsync.ui.accounts.AccountsScreen
import org.vovchenko.webdavsync.ui.accounts.AddAccountScreen
import org.vovchenko.webdavsync.ui.folders.FoldersScreen
import org.vovchenko.webdavsync.ui.folders.addfolder.AddEditFolderPairScreen
import org.vovchenko.webdavsync.ui.navigation.TabBackStackPolicy
import org.vovchenko.webdavsync.ui.navigation.TabLeaveInterceptor
import org.vovchenko.webdavsync.ui.overview.OverviewScreen
import org.vovchenko.webdavsync.ui.settings.AboutScreen
import org.vovchenko.webdavsync.ui.settings.BackupRestoreScreen
import org.vovchenko.webdavsync.ui.settings.SettingsMenuScreen
import org.vovchenko.webdavsync.ui.settings.SynchronizationSettingsScreen
import org.vovchenko.webdavsync.ui.settings.SystemSettingsScreen
import org.vovchenko.webdavsync.ui.theme.WebDavSyncTheme

private enum class BottomDestination(val route: String, val label: String) {
    Overview("overview", "Overview"),
    Folders("folders", "Folders"),
    Settings("settings", "Settings"),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** When true, Overview runs [org.vovchenko.webdavsync.ui.overview.OverviewViewModel.requestSync] (widget + mobile warn). */
    private val pendingSyncRequest = MutableStateFlow(false)

    @Inject lateinit var syncRequestToken: SyncRequestToken

    /**
     * The foreground sync notification is shown only when this is granted. A denial
     * does not cancel WorkManager; [org.vovchenko.webdavsync.sync.worker.SyncWorker]
     * still calls setForeground.
     */
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Sync does not wait on the result. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSyncRequest.value = syncRequestToken.accepts(intent)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            WebDavSyncTheme {
                val pending by pendingSyncRequest.collectAsState()
                WebDavSyncAppContent(
                    pendingSyncRequest = pending,
                    onPendingSyncConsumed = { pendingSyncRequest.value = false },
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (syncRequestToken.accepts(intent)) {
            pendingSyncRequest.value = true
        }
    }

    companion object {
        /** Widget Sync while "warn on mobile data" is on — open Overview and run the same check. */
        const val EXTRA_REQUEST_SYNC = "org.vovchenko.webdavsync.EXTRA_REQUEST_SYNC"
    }
}

@Composable
private fun WebDavSyncAppContent(
    pendingSyncRequest: Boolean = false,
    onPendingSyncConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val tabLeaveInterceptor = remember { TabLeaveInterceptor() }
    var triggerOverviewSync by remember { mutableStateOf(false) }

    LaunchedEffect(pendingSyncRequest) {
        if (pendingSyncRequest) {
            // Drop Settings details, but do not pop a form that still needs save or discard.
            if (tabLeaveInterceptor.onAttemptLeave == null) {
                navController.collapseSettingsDetails()
            }
            navController.navigateToTab(BottomDestination.Overview)
            triggerOverviewSync = true
            onPendingSyncConsumed()
        }
    }

    Scaffold(
        bottomBar = { WebDavSyncBottomBar(navController, tabLeaveInterceptor) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomDestination.Overview.route,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(BottomDestination.Overview.route) {
                OverviewScreen(
                    requestSyncOnStart = triggerOverviewSync,
                    onRequestSyncOnStartConsumed = { triggerOverviewSync = false },
                )
            }
            composable(BottomDestination.Folders.route) {
                FoldersScreen(
                    onAddFolderClick = { navController.navigate("folders/add") },
                    onEditFolderClick = { id -> navController.navigate("folders/edit/$id") },
                    onManageAccountsClick = { navController.navigate("accounts") },
                )
            }
            composable("folders/add") {
                AddEditFolderPairScreen(
                    onBack = { navController.popBackStack() },
                    onAddAccountClick = { navController.navigate("accounts/add") },
                    tabLeaveInterceptor = tabLeaveInterceptor,
                )
            }
            composable(
                "folders/edit/{folderPairId}",
                arguments = listOf(navArgument("folderPairId") { type = NavType.LongType }),
            ) {
                AddEditFolderPairScreen(
                    onBack = { navController.popBackStack() },
                    onAddAccountClick = { navController.navigate("accounts/add") },
                    tabLeaveInterceptor = tabLeaveInterceptor,
                )
            }
            composable("accounts") {
                AccountsScreen(
                    onBack = { navController.popBackStack() },
                    onAddAccountClick = { navController.navigate("accounts/add") },
                )
            }
            composable("accounts/add") {
                AddAccountScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    tabLeaveInterceptor = tabLeaveInterceptor,
                )
            }
            composable(BottomDestination.Settings.route) {
                SettingsMenuScreen(
                    onSynchronizationClick = { navController.navigate("settings/sync") },
                    onSystemClick = { navController.navigate("settings/system") },
                    onBackupRestoreClick = { navController.navigate("settings/backup") },
                    onAboutClick = { navController.navigate("settings/about") },
                    onAccountsClick = { navController.navigate("accounts") },
                )
            }
            composable("settings/sync") {
                SynchronizationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/system") {
                SystemSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/backup") {
                BackupRestoreScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun WebDavSyncBottomBar(
    navController: NavController,
    tabLeaveInterceptor: TabLeaveInterceptor,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        BottomDestination.entries.forEach { destination ->
            val selected = currentRoute != null &&
                (currentRoute == destination.route || currentRoute.startsWith("${destination.route}/"))
            NavigationBarItem(
                selected = selected,
                onClick = {
                    val proceed = { navController.finishTabSwitch(destination, tabLeaveInterceptor) }
                    val leave = tabLeaveInterceptor.onAttemptLeave
                    if (leave != null) {
                        leave(proceed)
                    } else {
                        if (currentRoute == TabBackStackPolicy.ADD_ACCOUNT_ROUTE) {
                            navController.popBackStack()
                        }
                        proceed()
                    }
                },
                icon = { Icon(imageVector = destination.icon(), contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

/**
 * Settings details are popped before the tab switch saves state. Otherwise
 * [navigateToTab] would restore Configuration (or whichever item was open) on the next visit.
 * A clean pop does not save those entries.
 */
private fun NavController.collapseSettingsDetails() {
    val routes = currentBackStack.value.map { it.destination.route }
    if (TabBackStackPolicy.shouldCollapseToSettingsMenu(routes)) {
        popBackStack(TabBackStackPolicy.SETTINGS_ROUTE, inclusive = false)
    }
}

/**
 * After the visible form has popped itself, a folder editor uncovered underneath
 * still has to accept save or discard. [TabLeaveInterceptor.deferredProceed] waits
 * until that editor is composed again.
 */
private fun NavController.finishTabSwitch(
    destination: BottomDestination,
    tabLeaveInterceptor: TabLeaveInterceptor,
) {
    collapseSettingsDetails()
    val top = currentBackStackEntry?.destination?.route
    if (TabBackStackPolicy.isFolderEditor(top)) {
        tabLeaveInterceptor.deferredProceed = { navigateToTab(destination) }
    } else {
        navigateToTab(destination)
    }
}

private fun NavController.navigateToTab(destination: BottomDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun BottomDestination.icon() = when (this) {
    BottomDestination.Overview -> Icons.Filled.Home
    BottomDestination.Folders -> Icons.Filled.Folder
    BottomDestination.Settings -> Icons.Filled.Settings
}
