package com.example.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.history.HistoryScreen
import com.example.ui.offline.OfflinePacksScreen
import com.example.ui.translator.TranslatorScreen
import com.example.ui.translator.TranslatorViewModel
import kotlinx.coroutines.launch

const val ROUTE_TRANSLATOR = "translator"
const val ROUTE_OFFLINE_PACKS = "offline_packs"
const val ROUTE_HISTORY = "history"

private data class NavDestinationItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val topDestinations = listOf(
    NavDestinationItem(
        route = ROUTE_TRANSLATOR,
        labelRes = R.string.nav_translator,
        selectedIcon = Icons.Rounded.Translate,
        unselectedIcon = Icons.Outlined.Translate,
        testTag = "nav_translator"
    ),
    NavDestinationItem(
        route = ROUTE_OFFLINE_PACKS,
        labelRes = R.string.nav_offline_packs,
        selectedIcon = Icons.Rounded.CloudDownload,
        unselectedIcon = Icons.Outlined.CloudDownload,
        testTag = "nav_offline_packs"
    ),
    NavDestinationItem(
        route = ROUTE_HISTORY,
        labelRes = R.string.nav_history,
        selectedIcon = Icons.Rounded.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_history"
    )
)

@Composable
fun SuaraLingoApp(
    viewModel: TranslatorViewModel = viewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    val favoriteList by viewModel.favoriteList.collectAsStateWithLifecycle()

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        viewModel.stopRecordingIfActive()
    }

    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navigateTo: (String) -> Unit = { route ->
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

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp
                    ) {
                        topDestinations.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        topDestinations.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_TRANSLATOR,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_TRANSLATOR) {
                        TranslatorScreen(
                            uiState = uiState,
                            onToggleRecording = viewModel::toggleRecording,
                            onSelectSourceLanguage = viewModel::setSourceLanguage,
                            onSelectTargetLanguage = viewModel::setTargetLanguage,
                            onSwapLanguages = viewModel::swapLanguages,
                            onToggleOfflineMode = viewModel::toggleOfflineMode,
                            onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                            onToggleManualInput = viewModel::toggleManualInputMode,
                            onManualTextChanged = viewModel::onManualTextChanged,
                            onSubmitManualTranslation = viewModel::submitManualTranslation,
                            onClearText = viewModel::clearTranscripts,
                            onSpeakSource = viewModel::speakSourceText,
                            onSpeakTarget = viewModel::speakTranslatedText,
                            onToggleFavorite = viewModel::toggleCurrentFavorite,
                            onDownloadMissingPair = viewModel::downloadMissingCurrentPair,
                            onDismissError = viewModel::dismissError,
                            onShowSnackbar = showMessage,
                            onToggleAutoUpdateCheck = viewModel::setAutoUpdateCheck,
                            onSaveRepoAndCheckUpdate = viewModel::saveRepoAndCheckForUpdate,
                            onCheckUpdateNow = { viewModel.checkForUpdateNow() },
                            onDownloadAppUpdate = viewModel::downloadAvailableUpdate,
                            onCancelAppUpdateDownload = viewModel::cancelUpdateDownload,
                            onDismissUpdateBanner = viewModel::dismissUpdateBanner,
                            canInstallApk = viewModel::canInstallApk,
                            onOpenInstallPermissionSettings = viewModel::openInstallPermissionSettings,
                            onInstallApk = viewModel::launchApkInstaller
                        )
                    }

                    composable(ROUTE_OFFLINE_PACKS) {
                        OfflinePacksScreen(
                            downloadedCodes = uiState.downloadedModelCodes,
                            downloadingCodes = uiState.downloadingModelCodes,
                            isDeviceOnline = uiState.isDeviceOnline,
                            errorMessage = uiState.errorMessage,
                            onDownloadPack = viewModel::downloadLanguagePack,
                            onDeletePack = viewModel::deleteLanguagePack,
                            onDismissError = viewModel::dismissError
                        )
                    }

                    composable(ROUTE_HISTORY) {
                        HistoryScreen(
                            allHistory = historyList,
                            favorites = favoriteList,
                            onToggleFavorite = viewModel::toggleRecordFavorite,
                            onDeleteRecord = viewModel::deleteHistoryRecord,
                            onClearAll = viewModel::clearAllHistory,
                            onSpeakText = viewModel::speakRecord,
                            onShowSnackbar = showMessage
                        )
                    }
                }
            }
        }
    }
}
