package org.vovchenko.webdavsync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSyncRequest.value = syncRequestToken.accepts(intent)
        enableEdgeToEdge()
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
    var triggerOverviewSync by remember { mutableStateOf(false) }

    LaunchedEffect(pendingSyncRequest) {
        if (pendingSyncRequest) {
            navController.navigate(BottomDestination.Overview.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            triggerOverviewSync = true
            onPendingSyncConsumed()
        }
    }

    Scaffold(
        bottomBar = { WebDavSyncBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomDestination.Overview.route,
            modifier = Modifier.padding(innerPadding),
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
                )
            }
            composable(
                "folders/edit/{folderPairId}",
                arguments = listOf(navArgument("folderPairId") { type = NavType.LongType }),
            ) {
                AddEditFolderPairScreen(
                    onBack = { navController.popBackStack() },
                    onAddAccountClick = { navController.navigate("accounts/add") },
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
private fun WebDavSyncBottomBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        BottomDestination.entries.forEach { destination ->
            val selected = currentRoute != null &&
                (currentRoute == destination.route || currentRoute.startsWith("${destination.route}/"))
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(imageVector = destination.icon(), contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

private fun BottomDestination.icon() = when (this) {
    BottomDestination.Overview -> Icons.Filled.Home
    BottomDestination.Folders -> Icons.Filled.Folder
    BottomDestination.Settings -> Icons.Filled.Settings
}
