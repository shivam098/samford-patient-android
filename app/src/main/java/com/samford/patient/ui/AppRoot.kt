package com.samford.patient.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.samford.patient.AppContainer
import com.samford.patient.R
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.LocalSampleMode
import com.samford.patient.ui.home.HomeScreen
import com.samford.patient.ui.labs.LabResultScreen
import com.samford.patient.ui.lock.LockScreen
import com.samford.patient.ui.records.RecordsScreen
import com.samford.patient.ui.records.VisitScreen
import com.samford.patient.ui.settings.ServerScreen
import com.samford.patient.ui.settings.SettingsScreen
import com.samford.patient.ui.signin.SignInScreen
import com.samford.patient.ui.signin.WelcomeScreen
import com.samford.patient.ui.viewer.DocumentViewerScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

sealed interface RootState {
    data object Loading : RootState
    data object SignedOut : RootState
    data class Main(val sample: Boolean) : RootState
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun SamfordAppRoot(container: AppContainer, onLanguageChanged: () -> Unit) {
    val rootFlow = remember(container) {
        combine(container.tokens.session, container.settings.settings) { session, settings ->
            when {
                settings.sampleMode -> RootState.Main(sample = true)
                session != null -> RootState.Main(sample = false)
                else -> RootState.SignedOut
            }
        }
    }
    val root by rootFlow.collectAsStateWithLifecycle(initialValue = RootState.Loading)
    val locked by container.appLock.locked.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalSampleMode provides ((root as? RootState.Main)?.sample == true),
    ) {
        Box(Modifier.fillMaxSize()) {
            when (val state = root) {
                RootState.Loading -> Unit
                RootState.SignedOut -> AuthNavigation(container)
                is RootState.Main -> MainNavigation(container, onLanguageChanged)
            }
            if (locked && root is RootState.Main) LockScreen()
        }
    }
}

@Composable
private fun AuthNavigation(container: AppContainer) {
    val nav = rememberNavController()
    PendingServerLink(container, nav)
    NavHost(nav, startDestination = WelcomeRoute) {
        composable<WelcomeRoute> {
            WelcomeScreen(
                onSignIn = { nav.navigate(SignInRoute) },
                onServerSettings = { nav.navigate(ServerRoute) },
            )
        }
        composable<SignInRoute> {
            SignInScreen(onBack = { nav.popBackStack() }, onServerSettings = { nav.navigate(ServerRoute) })
        }
        composable<ServerRoute> { ServerScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun PendingServerLink(container: AppContainer, nav: NavHostController) {
    val pending by container.pendingServerLink.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending != null) nav.navigate(ServerRoute) { launchSingleTop = true }
    }
}

private data class Tab(val route: Any, val label: Int, val tag: String)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun MainNavigation(container: AppContainer, onLanguageChanged: () -> Unit) {
    val nav = rememberNavController()
    PendingServerLink(container, nav)
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val unreadFlow = remember(container) {
        container.records.activeProfile.flatMapLatest { p ->
            if (p == null) flowOf(0) else container.records.unreadCount(p.id)
        }
    }
    val unread by unreadFlow.collectAsStateWithLifecycle(initialValue = 0)
    val tabs = listOf(
        Tab(HomeRoute, R.string.tab_home, "nav_home"),
        Tab(RecordsRoute, R.string.tab_records, "nav_records"),
        Tab(SettingsRoute, R.string.tab_settings, "nav_settings"),
    )
    val showBottomBar = destination?.let { d ->
        d.hasRoute(HomeRoute::class) || d.hasRoute(RecordsRoute::class) || d.hasRoute(SettingsRoute::class)
    } ?: true

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hasRoute(tab.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                when (tab.route) {
                                    HomeRoute -> BadgedBox(badge = { if (unread > 0) Badge { Text(unread.coerceAtMost(99).toString()) } }) {
                                        Icon(Icons.Filled.Home, contentDescription = null)
                                    }
                                    RecordsRoute -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                                    else -> Icon(Icons.Filled.Settings, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(tab.label)) },
                            modifier = Modifier.testTag(tab.tag),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenDocument = { nav.navigate(DocumentRoute(it)) },
                    onOpenLabResult = { nav.navigate(LabResultRoute(it)) },
                    onOpenVisit = { nav.navigate(VisitRoute(it)) },
                    onOpenRecords = { nav.navigate(RecordsRoute) },
                )
            }
            composable<RecordsRoute> {
                RecordsScreen(
                    onOpenDocument = { nav.navigate(DocumentRoute(it)) },
                    onOpenLabResult = { nav.navigate(LabResultRoute(it)) },
                    onOpenVisit = { nav.navigate(VisitRoute(it)) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onServerSettings = { nav.navigate(ServerRoute) }, onLanguageChanged = onLanguageChanged)
            }
            composable<VisitRoute> { entry ->
                VisitScreen(
                    visitId = entry.toRoute<VisitRoute>().visitId,
                    onBack = { nav.popBackStack() },
                    onOpenDocument = { nav.navigate(DocumentRoute(it)) },
                    onOpenLabResult = { nav.navigate(LabResultRoute(it)) },
                )
            }
            composable<DocumentRoute> { entry ->
                val route = entry.toRoute<DocumentRoute>()
                DocumentViewerScreen(documentId = route.documentId, initialVersion = route.version, onBack = { nav.popBackStack() })
            }
            composable<LabResultRoute> { entry ->
                LabResultScreen(resultId = entry.toRoute<LabResultRoute>().resultId, onBack = { nav.popBackStack() })
            }
            composable<ServerRoute> { ServerScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
