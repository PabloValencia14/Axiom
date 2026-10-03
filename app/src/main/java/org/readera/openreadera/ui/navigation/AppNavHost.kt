package org.readera.openreadera.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import org.readera.openreadera.OpenReadEraApplication
import org.readera.openreadera.ui.library.DocumentDetailsScreen
import org.readera.openreadera.ui.library.LibraryScreen
import org.readera.openreadera.ui.library.LibraryViewModel
import org.readera.openreadera.ui.reader.ReaderScreen
import org.readera.openreadera.ui.reader.ReaderViewModel
import org.readera.openreadera.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    openBookEvents: Flow<Long> = emptyFlow(),
    onOpenFile: (Uri) -> Unit = {},
    windowedMode: StateFlow<Boolean> = MutableStateFlow(false)
) {
    val isWindowed by windowedMode.collectAsState()
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as OpenReadEraApplication
    val scope = rememberCoroutineScope()

    LaunchedEffect(openBookEvents) {
        openBookEvents.collectLatest { bookId ->
            navController.navigate(Screen.Reader.createRoute(bookId)) {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = Screen.Library.route) {
        composable(Screen.Library.route) {
            val libraryViewModel = viewModel<LibraryViewModel>(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return LibraryViewModel(app.repository, app.storageScanner) as T
                    }
                }
            )

            LibraryScreen(
                viewModel = libraryViewModel,
                onOpenBookDetails = { bookId ->
                    navController.navigate(Screen.DocumentDetails.createRoute(bookId))
                },
                onOpenReader = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
                onOpenFile = onOpenFile,
                onOpenSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.DocumentDetails.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L
            DocumentDetailsScreen(
                bookId = bookId,
                repository = app.repository,
                onBack = { navController.popBackStack() },
                onRead = { id ->
                    navController.navigate(Screen.Reader.createRoute(id))
                }
            )
        }

        composable(
            route = Screen.Reader.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L
            val readerViewModel = viewModel<ReaderViewModel>(
                key = "reader_$bookId",
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return ReaderViewModel(
                            bookId = bookId,
                            repository = app.repository,
                            preferences = app.preferences,
                            engineManager = app.engineManager,
                            context = context
                        ) as T
                    }
                }
            )

            ReaderScreen(
                viewModel = readerViewModel,
                onBack = { navController.popBackStack() },
                onOpenDocumentDetails = {
                    navController.navigate(Screen.DocumentDetails.createRoute(bookId))
                },
                onOpenReader = { id -> navController.navigate(Screen.Reader.createRoute(id)) },
                onOpenAppSettings = { navController.navigate(Screen.Settings.route) },
                isWindowed = isWindowed
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                preferences = app.preferences,
                onBack = { navController.popBackStack() },
                onTriggerScan = {
                    scope.launch {
                        app.storageScanner.scanStorage()
                    }
                }
            )
        }
    }
}
