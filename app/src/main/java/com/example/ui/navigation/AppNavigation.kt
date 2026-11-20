package com.example.ui.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ads.AdBannerSlot
import com.example.ads.AdMobManager
import com.example.security.BiometricVaultLock
import com.example.data.local.IpoDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AllotmentRepository
import com.example.data.repository.IpoRepository
import com.example.notifications.IpoNotificationManager
import com.example.ui.components.AppNavigationDrawerSheet
import com.example.ui.components.DrawerMenuItemId
import com.example.ui.components.ExitAppDialog
import com.example.ui.components.GlassBackdrop
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.screens.AllotmentAlertsScreen
import com.example.ui.screens.AllotmentHubScreen
import com.example.ui.screens.CompareIposScreen
import com.example.ui.screens.ContactUsDialog
import com.example.ui.screens.IpoBoardScreen
import com.example.ui.screens.IpoDetailScreen
import com.example.ui.screens.IpoEventCalendarScreen
import com.example.ui.screens.IpoFaqsScreen
import com.example.ui.screens.IpoNewsScreen
import com.example.ui.screens.MarketRadarScreen
import com.example.ui.screens.PrivacyPolicyScreen
import com.example.ui.screens.SettingsBottomSheet
import com.example.ui.screens.TermsConditionsScreen
import com.example.ui.screens.YearWiseIpoListScreen
import com.example.ui.screens.YearWiseSubscriptionScreen
import com.example.ui.viewmodel.AllotmentAlertsViewModel
import com.example.ui.viewmodel.BoardFilter
import com.example.ui.viewmodel.BoardTimelineTab
import com.example.ui.viewmodel.IpoBoardUiState
import com.example.ui.viewmodel.IpoBoardViewModel
import com.example.ui.viewmodel.IpoDetailViewModel
import com.example.ui.viewmodel.RadarSortMode
import com.example.ui.viewmodel.StatusFilter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object IpoBoardRoute

@Serializable
object IpoNewsRoute

@Serializable
object MarketRadarRoute

@Serializable
data class CompareIposRoute(val initialIpoId: String? = null)

@Serializable
object YearWiseIpoListRoute

@Serializable
object YearWiseSubscriptionRoute

@Serializable
object IpoEventCalendarRoute

@Serializable
data class AllotmentAlertsRoute(val preselectedIpoId: String? = null)

@Serializable
object AllotmentHubRoute

@Serializable
object FaqsRoute

@Serializable
object PrivacyPolicyRoute

@Serializable
object TermsRoute

@Serializable
data class IpoDetailRoute(val ipoId: String)

private sealed interface BottomBarAction {
    data class Board(val filter: BoardFilter) : BottomBarAction
    data object Allotment : BottomBarAction
    data object News : BottomBarAction
}

private data class FixedBottomItem(
    val action: BottomBarAction,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

@Composable
fun AppNavigation(
    boardViewModel: IpoBoardViewModel,
    uiState: IpoBoardUiState,
    ipoRepository: IpoRepository,
    allotmentRepository: AllotmentRepository? = null,
    pendingNotificationIpoId: String? = null,
    onConsumeNotificationIpoId: () -> Unit = {},
    pendingOpenAllotmentAlerts: Boolean = false,
    onConsumeOpenAllotmentAlerts: () -> Unit = {},
    pendingOpenNewsTab: Boolean = false,
    pendingNewsArticleId: String? = null,
    onConsumeOpenNewsTab: () -> Unit = {},
    onExitApp: (() -> Unit)? = null,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val resolvedAllotmentRepository = remember(allotmentRepository, context) {
        allotmentRepository ?: AllotmentRepository(
            IpoDatabase.getInstance(context.applicationContext).allotmentDao(),
        )
    }
    val allotmentViewModel: AllotmentAlertsViewModel = viewModel(
        factory = AllotmentAlertsViewModel.Factory(
            allotmentRepository = resolvedAllotmentRepository,
            preferencesRepository = UserPreferencesRepository(context.applicationContext),
            appContext = context.applicationContext,
        ),
    )
    val allotmentUiState by allotmentViewModel.uiState.collectAsStateWithLifecycle()
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var highlightedNewsArticleId by remember { mutableStateOf<String?>(null) }
    var hasNotificationPermission by remember {
        mutableStateOf(IpoNotificationManager.hasNotificationPermission(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
    }

    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !IpoNotificationManager.hasNotificationPermission(context)
        ) {
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(pendingNotificationIpoId) {
        val targetId = pendingNotificationIpoId
        if (!targetId.isNullOrBlank()) {
            navController.navigate(IpoDetailRoute(targetId)) {
                launchSingleTop = true
            }
            onConsumeNotificationIpoId()
        }
    }

    LaunchedEffect(pendingOpenAllotmentAlerts) {
        if (pendingOpenAllotmentAlerts) {
            navController.navigate(AllotmentAlertsRoute()) {
                launchSingleTop = true
            }
            onConsumeOpenAllotmentAlerts()
        }
    }

    LaunchedEffect(pendingOpenNewsTab, pendingNewsArticleId) {
        if (pendingOpenNewsTab) {
            highlightedNewsArticleId = pendingNewsArticleId
            boardViewModel.markNewsAsRead()
            navController.navigate(IpoNewsRoute) {
                launchSingleTop = true
            }
            onConsumeOpenNewsTab()
        }
    }

    var hasCompletedFirstResume by remember { mutableStateOf(false) }
    var isVaultUnlocked by rememberSaveable { mutableStateOf(false) }
    var vaultAuthError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.biometricLockEnabled) {
        if (!uiState.biometricLockEnabled) {
            isVaultUnlocked = true
            vaultAuthError = null
        } else {
            vaultAuthError = null
        }
    }

    val triggerVaultUnlock: () -> Unit = {
        vaultAuthError = null
        BiometricVaultLock.authenticate(
            context = context,
            onSuccess = {
                isVaultUnlocked = true
                vaultAuthError = null
            },
            onError = { err ->
                vaultAuthError = err
            },
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (uiState.biometricLockEnabled) {
            isVaultUnlocked = false
            vaultAuthError = null
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasNotificationPermission = IpoNotificationManager.hasNotificationPermission(context)
        boardViewModel.onAppForegrounded()
        if (allotmentUiState.pendingCount > 0 && allotmentUiState.notifyAllotmentEnabled) {
            allotmentViewModel.checkAllPendingNow(silentIfEmpty = true)
        }
        if (hasCompletedFirstResume) {
            AdMobManager.maybeShowAppOpenOnResume(
                context = context,
                onImpression = { boardViewModel.recordAdImpression() },
            )
        } else {
            hasCompletedFirstResume = true
            AdMobManager.preloadAppOpenAd(context)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isDetailScreen = currentDestination?.hasRoute<IpoDetailRoute>() == true
    val isNewsScreen = currentDestination?.hasRoute<IpoNewsRoute>() == true
    val isMarketRadarScreen = currentDestination?.hasRoute<MarketRadarRoute>() == true
    val isCompareIposScreen = currentDestination?.hasRoute<CompareIposRoute>() == true
    val isYearWiseListScreen = currentDestination?.hasRoute<YearWiseIpoListRoute>() == true
    val isYearWiseSubScreen = currentDestination?.hasRoute<YearWiseSubscriptionRoute>() == true
    val isCalendarScreen = currentDestination?.hasRoute<IpoEventCalendarRoute>() == true
    val isAllotmentAlertsScreen = currentDestination?.hasRoute<AllotmentAlertsRoute>() == true
    val isAllotmentHubScreen = currentDestination?.hasRoute<AllotmentHubRoute>() == true
    val isFaqsScreen = currentDestination?.hasRoute<FaqsRoute>() == true
    val isPrivacyScreen = currentDestination?.hasRoute<PrivacyPolicyRoute>() == true
    val isTermsScreen = currentDestination?.hasRoute<TermsRoute>() == true
    val isDrawerSubScreen = isMarketRadarScreen || isCompareIposScreen || isYearWiseListScreen || isYearWiseSubScreen ||
        isCalendarScreen || isAllotmentHubScreen || isFaqsScreen || isPrivacyScreen || isTermsScreen
    val isBoardScreen = currentDestination?.hasRoute<IpoBoardRoute>() != false &&
        !isNewsScreen && !isMarketRadarScreen && !isAllotmentAlertsScreen && !isDrawerSubScreen && !isDetailScreen

    LaunchedEffect(isNewsScreen, uiState.unreadNewsCount) {
        if (isNewsScreen && uiState.unreadNewsCount > 0) {
            boardViewModel.markNewsAsRead()
        }
    }

    val activeDrawerItem = when {
        showSettingsSheet -> DrawerMenuItemId.SETTINGS
        showContactDialog -> DrawerMenuItemId.CONTACT_US
        isNewsScreen -> DrawerMenuItemId.IPO_NEWS
        isMarketRadarScreen -> DrawerMenuItemId.IPO_GMP
        isCompareIposScreen -> DrawerMenuItemId.COMPARE_IPOS
        isYearWiseListScreen -> DrawerMenuItemId.IPO_LIST_YEARWISE
        isYearWiseSubScreen -> DrawerMenuItemId.SUBSCRIPTION_YEARWISE
        isCalendarScreen -> DrawerMenuItemId.IPO_CALENDAR
        isAllotmentAlertsScreen -> DrawerMenuItemId.ALLOTMENT_ALERTS
        isAllotmentHubScreen -> DrawerMenuItemId.ALLOTMENT_HUB
        isFaqsScreen -> DrawerMenuItemId.FAQS
        isPrivacyScreen -> DrawerMenuItemId.PRIVACY_POLICY
        isTermsScreen -> DrawerMenuItemId.TERMS_CONDITIONS
        uiState.timelineTab == BoardTimelineTab.LISTED -> DrawerMenuItemId.LISTED
        uiState.boardFilter == BoardFilter.MAINBOARD -> DrawerMenuItemId.MAINBOARD
        uiState.boardFilter == BoardFilter.SME -> DrawerMenuItemId.SME
        else -> DrawerMenuItemId.HOME
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = isBoardScreen && !drawerState.isOpen && !showSettingsSheet && !showContactDialog && !showExitDialog) {
        if (uiState.isSearchActive) {
            boardViewModel.setSearchActive(false)
        } else {
            showExitDialog = true
        }
    }

    val bottomItems = remember {
        listOf(
            FixedBottomItem(
                action = BottomBarAction.Board(BoardFilter.ALL),
                label = "ALL",
                selectedIcon = Icons.Rounded.Apps,
                unselectedIcon = Icons.Outlined.Apps,
                testTag = "bottom_bar_all",
            ),
            FixedBottomItem(
                action = BottomBarAction.Board(BoardFilter.MAINBOARD),
                label = "MAINBOARD",
                selectedIcon = Icons.Rounded.Business,
                unselectedIcon = Icons.Outlined.Business,
                testTag = "bottom_bar_mainboard",
            ),
            FixedBottomItem(
                action = BottomBarAction.Board(BoardFilter.SME),
                label = "SME",
                selectedIcon = Icons.Rounded.RocketLaunch,
                unselectedIcon = Icons.Outlined.RocketLaunch,
                testTag = "bottom_bar_sme",
            ),
            FixedBottomItem(
                action = BottomBarAction.Allotment,
                label = "ALLOTMENT",
                selectedIcon = Icons.Rounded.NotificationsActive,
                unselectedIcon = Icons.Outlined.NotificationsActive,
                testTag = "bottom_bar_allotment",
            ),
            FixedBottomItem(
                action = BottomBarAction.News,
                label = "NEWS",
                selectedIcon = Icons.Rounded.Newspaper,
                unselectedIcon = Icons.Outlined.Newspaper,
                testTag = "bottom_bar_news",
            ),
        )
    }

    fun handleBottomBarClick(action: BottomBarAction) {
        when (action) {
            is BottomBarAction.Board -> {
                boardViewModel.setBoardFilter(action.filter)
                if (!isBoardScreen) {
                    navController.navigate(IpoBoardRoute) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            BottomBarAction.Allotment -> {
                if (!isAllotmentAlertsScreen) {
                    navController.navigate(AllotmentAlertsRoute()) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            BottomBarAction.News -> {
                if (!isNewsScreen) {
                    navController.navigate(IpoNewsRoute) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }

    fun openIpoDetail(ipoId: String) {
        navController.navigate(IpoDetailRoute(ipoId))
        scope.launch {
            val openCount = boardViewModel.recordDetailOpenAndGetCount()
            AdMobManager.maybeShowInterstitial(
                context = context,
                detailOpenCount = openCount,
                onImpression = { boardViewModel.recordAdImpression() },
            )
        }
    }

    fun navigateToBoardRoot() {
        if (!isBoardScreen) {
            navController.navigate(IpoBoardRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    fun handleDrawerSelection(item: DrawerMenuItemId) {
        scope.launch { drawerState.close() }
        when (item) {
            DrawerMenuItemId.HOME -> {
                boardViewModel.setTimelineTab(BoardTimelineTab.CURRENT_UPCOMING)
                boardViewModel.setBoardFilter(BoardFilter.ALL)
                boardViewModel.setStatusFilter(StatusFilter.ALL)
                navigateToBoardRoot()
            }

            DrawerMenuItemId.MAINBOARD -> {
                boardViewModel.setTimelineTab(BoardTimelineTab.CURRENT_UPCOMING)
                boardViewModel.setBoardFilter(BoardFilter.MAINBOARD)
                navigateToBoardRoot()
            }

            DrawerMenuItemId.SME -> {
                boardViewModel.setTimelineTab(BoardTimelineTab.CURRENT_UPCOMING)
                boardViewModel.setBoardFilter(BoardFilter.SME)
                navigateToBoardRoot()
            }

            DrawerMenuItemId.LISTED -> {
                boardViewModel.setBoardFilter(BoardFilter.ALL)
                boardViewModel.setTimelineTab(BoardTimelineTab.LISTED)
                navigateToBoardRoot()
            }

            DrawerMenuItemId.IPO_CALENDAR -> {
                navController.navigate(IpoEventCalendarRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.ALLOTMENT_ALERTS -> {
                navController.navigate(AllotmentAlertsRoute()) { launchSingleTop = true }
            }

            DrawerMenuItemId.ALLOTMENT_HUB -> {
                navController.navigate(AllotmentHubRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.IPO_NEWS -> {
                handleBottomBarClick(BottomBarAction.News)
            }

            DrawerMenuItemId.IPO_GMP -> {
                boardViewModel.setRadarSortMode(RadarSortMode.GMP_PERCENT)
                navController.navigate(MarketRadarRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.COMPARE_IPOS -> {
                navController.navigate(CompareIposRoute()) { launchSingleTop = true }
            }

            DrawerMenuItemId.IPO_LIST_YEARWISE -> {
                navController.navigate(YearWiseIpoListRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.SUBSCRIPTION_YEARWISE -> {
                navController.navigate(YearWiseSubscriptionRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.FAQS -> {
                navController.navigate(FaqsRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.SETTINGS -> {
                showSettingsSheet = true
            }

            DrawerMenuItemId.CONTACT_US -> {
                showContactDialog = true
            }

            DrawerMenuItemId.SHARE_FRIENDS -> {
                val shareText = buildString {
                    appendLine("Track Live Mainboard & SME IPOs, GMP, Subscription Demand & Allotment Dates with IPO Guruji!")
                    if (uiState.marketPulse.liveCount > 0) {
                        appendLine("Currently tracking ${uiState.marketPulse.liveCount} Open IPOs and live GMP updates.")
                    }
                    append("Check out IPO Guruji on Android.")
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "IPO Guruji • Live IPO & GMP Tracker")
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                runCatching {
                    context.startActivity(Intent.createChooser(shareIntent, "Share IPO Guruji"))
                }
            }

            DrawerMenuItemId.PRIVACY_POLICY -> {
                navController.navigate(PrivacyPolicyRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.TERMS_CONDITIONS -> {
                navController.navigate(TermsRoute) { launchSingleTop = true }
            }

            DrawerMenuItemId.EXIT_APP -> {
                showExitDialog = true
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp
        val drawerWidth = minOf(304.dp, maxWidth * 0.82f)
        val hideChromeBars = isDetailScreen || isDrawerSubScreen

        val glass = LocalGlassStyle.current

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = (isBoardScreen || isNewsScreen || isAllotmentAlertsScreen) && !showSettingsSheet && !showContactDialog && !showExitDialog,
            drawerContent = {
                AppNavigationDrawerSheet(
                    activeItem = activeDrawerItem,
                    liveIpoCount = uiState.marketPulse.liveCount,
                    lastSyncedLabel = uiState.lastSyncedLabel,
                    drawerWidth = drawerWidth,
                    onSelectItem = ::handleDrawerSelection,
                    onQuickSyncGmp = {
                        scope.launch { drawerState.close() }
                        boardViewModel.refreshGmpNow()
                    },
                )
            },
        ) {
            GlassBackdrop(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (!useNavigationRail && !hideChromeBars) {
                        Column(
                            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                        ) {
                            AdBannerSlot(
                                onAdImpression = { boardViewModel.recordAdImpression() },
                            )
                            Surface(
                                color = glass.dockFill,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                tonalElevation = 0.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    HorizontalDivider(
                                        color = if (glass.isGlass) {
                                            glass.specularBorderTop.copy(alpha = 0.9f)
                                        } else {
                                            glass.hairline
                                        },
                                        thickness = if (glass.isGlass) 1.dp else 0.8.dp,
                                    )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    bottomItems.forEach { item ->
                                        val selected = when (item.action) {
                                            is BottomBarAction.Board -> isBoardScreen && uiState.boardFilter == item.action.filter
                                            BottomBarAction.Allotment -> isAllotmentAlertsScreen
                                            BottomBarAction.News -> isNewsScreen
                                        }
                                        val contentColor = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .minimumInteractiveComponentSize()
                                                .clickable { handleBottomBarClick(item.action) }
                                                .testTag(item.testTag),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            val showNewsBadge = item.action == BottomBarAction.News &&
                                                uiState.unreadNewsCount > 0 &&
                                                !isNewsScreen
                                            Box(contentAlignment = Alignment.Center) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(99.dp))
                                                        .background(
                                                            if (selected) {
                                                                MaterialTheme.colorScheme.primaryContainer
                                                            } else {
                                                                Color.Transparent
                                                            },
                                                        )
                                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                        contentDescription = item.label,
                                                        tint = contentColor,
                                                        modifier = Modifier.size(19.dp),
                                                    )
                                                }
                                                if (showNewsBadge) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .clip(RoundedCornerShape(99.dp))
                                                            .background(MaterialTheme.colorScheme.error)
                                                            .padding(horizontal = 4.dp, vertical = 0.5.dp)
                                                            .testTag("bottom_bar_news_badge"),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = if (uiState.unreadNewsCount > 9) "9+" else uiState.unreadNewsCount.toString(),
                                                            color = MaterialTheme.colorScheme.onError,
                                                            fontSize = 8.sp,
                                                            lineHeight = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = item.label,
                                                fontSize = 9.5.sp,
                                                lineHeight = 11.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                                color = contentColor,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavigationRail && !hideChromeBars) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.statusBars),
                        containerColor = glass.dockFill,
                    ) {
                        bottomItems.forEach { item ->
                            val selected = when (item.action) {
                                is BottomBarAction.Board -> isBoardScreen && uiState.boardFilter == item.action.filter
                                BottomBarAction.Allotment -> isAllotmentAlertsScreen
                                BottomBarAction.News -> isNewsScreen
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { handleBottomBarClick(item.action) },
                                modifier = Modifier.testTag(item.testTag),
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                    )
                                },
                                label = {
                                    Text(
                                        text = item.label,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                },
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (!isDetailScreen) {
                                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = IpoBoardRoute,
                        ) {
                            composable<IpoBoardRoute> {
                                IpoBoardScreen(
                                    uiState = uiState,
                                    onSelectBoardFilter = boardViewModel::setBoardFilter,
                                    onSelectTimelineTab = boardViewModel::setTimelineTab,
                                    onSelectStatusFilter = boardViewModel::setStatusFilter,
                                    onSearchQueryChange = boardViewModel::setSearchQuery,
                                    onToggleSearch = boardViewModel::setSearchActive,
                                    onRefresh = boardViewModel::refreshMarketPulse,
                                    onRefreshGmp = boardViewModel::refreshGmpNow,
                                    onOpenRadar = { navController.navigate(MarketRadarRoute) },
                                    onOpenSettings = { showSettingsSheet = true },
                                    onIpoClick = ::openIpoDetail,
                                    onAdImpression = { boardViewModel.recordAdImpression() },
                                    onOpenMenu = { scope.launch { drawerState.open() } },
                                    onOpenCalendar = {
                                        navController.navigate(IpoEventCalendarRoute) { launchSingleTop = true }
                                    },
                                    onSelectBoardSortMode = boardViewModel::setBoardSortMode,
                                    onToggleSavedOnly = boardViewModel::setSavedOnlyFilter,
                                    onToggleBookmarkIpo = boardViewModel::toggleBookmarkIpo,
                                    onToggleCompactCards = boardViewModel::setCompactCardsEnabled,
                                )
                            }

                            composable<IpoNewsRoute> {
                                IpoNewsScreen(
                                    articles = uiState.newsArticles,
                                    isRefreshing = uiState.isRefreshing,
                                    lastSyncedLabel = uiState.lastSyncedLabel,
                                    sourcesSummary = uiState.liveNewsSourcesLabel,
                                    onRefresh = boardViewModel::refreshMarketPulse,
                                    onOpenIpoDetail = ::openIpoDetail,
                                    onOpenMenu = { scope.launch { drawerState.open() } },
                                    notifyNewsAlertsEnabled = uiState.notifyNewsAlertsEnabled,
                                    newsAlertCategories = uiState.newsAlertCategories,
                                    hasNotificationPermission = hasNotificationPermission,
                                    highlightedArticleId = highlightedNewsArticleId,
                                    onConsumeHighlightedArticle = { highlightedNewsArticleId = null },
                                    onToggleNewsAlerts = { enabled ->
                                        boardViewModel.setNotifyNewsAlertsEnabled(enabled)
                                        if (enabled) requestNotificationsIfNeeded()
                                    },
                                    onToggleNewsAlertCategory = boardViewModel::toggleNewsAlertCategory,
                                    onRequestNotificationPermission = ::requestNotificationsIfNeeded,
                                    onSendTestNewsAlert = {
                                        if (hasNotificationPermission) {
                                            boardViewModel.sendSampleNewsNotification()
                                        } else {
                                            requestNotificationsIfNeeded()
                                        }
                                    },
                                )
                            }

                            composable<MarketRadarRoute> {
                                MarketRadarScreen(
                                    uiState = uiState,
                                    onSelectSortMode = boardViewModel::setRadarSortMode,
                                    onIpoClick = ::openIpoDetail,
                                    onBack = { navController.popBackStack() },
                                    onRefreshGmp = boardViewModel::refreshGmpNow,
                                )
                            }

                            composable<CompareIposRoute> { backStackEntry ->
                                val args = backStackEntry.toRoute<CompareIposRoute>()
                                CompareIposScreen(
                                    allIpos = uiState.allIpos,
                                    initialIpoId = args.initialIpoId,
                                    onIpoClick = ::openIpoDetail,
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable<YearWiseIpoListRoute> {
                                YearWiseIpoListScreen(
                                    allIpos = uiState.allIpos,
                                    onIpoClick = ::openIpoDetail,
                                    onBack = { navController.popBackStack() },
                                    isRefreshing = uiState.isRefreshing,
                                    onRefresh = boardViewModel::refreshMarketPulse,
                                )
                            }

                            composable<YearWiseSubscriptionRoute> {
                                YearWiseSubscriptionScreen(
                                    allIpos = uiState.allIpos,
                                    onIpoClick = ::openIpoDetail,
                                    onBack = { navController.popBackStack() },
                                    isRefreshing = uiState.isRefreshing,
                                    onRefresh = boardViewModel::refreshMarketPulse,
                                )
                            }

                            composable<IpoEventCalendarRoute> {
                                IpoEventCalendarScreen(
                                    allIpos = uiState.allIpos,
                                    isRefreshing = uiState.isRefreshing,
                                    onRefresh = boardViewModel::refreshMarketPulse,
                                    onIpoClick = ::openIpoDetail,
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable<AllotmentAlertsRoute> { backStackEntry ->
                                val args = backStackEntry.toRoute<AllotmentAlertsRoute>()
                                AllotmentAlertsScreen(
                                    uiState = allotmentUiState,
                                    allIpos = uiState.allIpos,
                                    preselectedIpoId = args.preselectedIpoId,
                                    hasNotificationPermission = hasNotificationPermission,
                                    onRequestNotificationPermission = ::requestNotificationsIfNeeded,
                                    onToggleAutoMonitor = { enabled ->
                                        allotmentViewModel.setNotifyAllotmentEnabled(enabled)
                                        if (enabled) requestNotificationsIfNeeded()
                                    },
                                    onSelectProfileFilter = allotmentViewModel::selectProfileFilter,
                                    onSaveProfile = { existingId, name, pan, dematId, upiHandle, onSuccess ->
                                        allotmentViewModel.saveProfile(
                                            existingId = existingId,
                                            displayName = name,
                                            panNumber = pan,
                                            dematId = dematId,
                                            upiHandle = upiHandle,
                                            onSuccess = onSuccess,
                                        )
                                    },
                                    onDeleteProfile = allotmentViewModel::deleteProfile,
                                    onTrackIpo = { ipo, profileIds, lots, appNo, onSuccess ->
                                        allotmentViewModel.trackIpoForProfiles(
                                            ipo = ipo,
                                            profileIds = profileIds,
                                            lotsApplied = lots,
                                            applicationNumber = appNo,
                                            onSuccess = onSuccess,
                                        )
                                        requestNotificationsIfNeeded()
                                    },
                                    onDeleteWatch = allotmentViewModel::deleteWatch,
                                    onCheckSingleWatch = allotmentViewModel::checkSingleWatchNow,
                                    onCheckAllNow = { allotmentViewModel.checkAllPendingNow() },
                                    onConfirmManualResult = { watchId, status, shares ->
                                        allotmentViewModel.confirmManualResult(
                                            watchId = watchId,
                                            status = status,
                                            sharesAllotted = shares,
                                        )
                                    },
                                    onSendTestNotification = {
                                        if (hasNotificationPermission) {
                                            allotmentViewModel.sendTestAllotmentNotification()
                                        } else {
                                            requestNotificationsIfNeeded()
                                        }
                                    },
                                    onWipeAllPrivateData = allotmentViewModel::wipeAllPrivateData,
                                    onBack = {
                                        if (!navController.popBackStack()) {
                                            navigateToBoardRoot()
                                        }
                                    },
                                    biometricLockEnabled = uiState.biometricLockEnabled,
                                    isVaultUnlocked = isVaultUnlocked,
                                    vaultAuthError = vaultAuthError,
                                    onUnlockVault = triggerVaultUnlock,
                                    onLockVaultNow = {
                                        isVaultUnlocked = false
                                        vaultAuthError = null
                                    },
                                    onToggleBiometricLock = { enabled ->
                                        if (enabled) {
                                            if (BiometricVaultLock.canAuthenticate(context)) {
                                                boardViewModel.setBiometricLockEnabled(true)
                                                isVaultUnlocked = true
                                                vaultAuthError = null
                                            } else {
                                                vaultAuthError = BiometricVaultLock.checkCapability(context).statusMessage
                                            }
                                        } else {
                                            boardViewModel.setBiometricLockEnabled(false)
                                            isVaultUnlocked = true
                                            vaultAuthError = null
                                        }
                                    },
                                )
                            }

                            composable<AllotmentHubRoute> {
                                AllotmentHubScreen(
                                    onBack = { navController.popBackStack() },
                                    onOpenAllotmentAlerts = {
                                        navController.navigate(AllotmentAlertsRoute()) { launchSingleTop = true }
                                    },
                                )
                            }

                            composable<FaqsRoute> {
                                IpoFaqsScreen(
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable<PrivacyPolicyRoute> {
                                PrivacyPolicyScreen(
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable<TermsRoute> {
                                TermsConditionsScreen(
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable<IpoDetailRoute> { backStackEntry ->
                                val args = backStackEntry.toRoute<IpoDetailRoute>()
                                val detailViewModel: IpoDetailViewModel = viewModel(
                                    key = "detail_${args.ipoId}",
                                    factory = IpoDetailViewModel.Factory(
                                        ipoId = args.ipoId,
                                        ipoRepository = ipoRepository,
                                    ),
                                )
                                val loadState by detailViewModel.loadState.collectAsStateWithLifecycle()
                                val selectedTab by detailViewModel.selectedTab.collectAsStateWithLifecycle()
                                val subscriptionSyncState by detailViewModel.subscriptionSyncState.collectAsStateWithLifecycle()

                                LaunchedEffect(loadState.ipo?.id, loadState.ipo?.status) {
                                    loadState.ipo?.let { detailViewModel.onDetailScreenVisible(it) }
                                }

                                IpoDetailScreen(
                                    ipo = loadState.ipo,
                                    selectedTab = selectedTab,
                                    onSelectTab = detailViewModel::selectTab,
                                    onBack = { navController.popBackStack() },
                                    onUpdateLots = detailViewModel::updateLots,
                                    onAdImpression = { boardViewModel.recordAdImpression() },
                                    isRefreshing = uiState.isRefreshing,
                                    onRefresh = {
                                        boardViewModel.refreshMarketPulse()
                                        detailViewModel.refreshLiveSubscription(force = true)
                                    },
                                    isRefreshingGmp = uiState.isRefreshingGmp,
                                    onRefreshGmp = boardViewModel::refreshGmpNow,
                                    onTrackAllotment = { targetIpoId ->
                                        navController.navigate(AllotmentAlertsRoute(preselectedIpoId = targetIpoId)) {
                                            launchSingleTop = true
                                        }
                                    },
                                    isDay3ReminderEnabled = args.ipoId in uiState.day3ReminderIpoIds,
                                    gmpWatchRule = uiState.gmpWatchRules[args.ipoId],
                                    onToggleDay3Reminder = { enabled ->
                                        boardViewModel.setDay3Reminder(args.ipoId, enabled)
                                        if (enabled) requestNotificationsIfNeeded()
                                    },
                                    onSetGmpWatchRule = { minPct, maxPct ->
                                        boardViewModel.setGmpWatchRule(args.ipoId, minPct, maxPct)
                                        if (minPct != null || maxPct != null) requestNotificationsIfNeeded()
                                    },
                                    onCompareIpo = { targetIpoId ->
                                        navController.navigate(CompareIposRoute(initialIpoId = targetIpoId)) {
                                            launchSingleTop = true
                                        }
                                    },
                                    isLoading = loadState.isLoading,
                                    subscriptionSyncState = subscriptionSyncState,
                                    onRefreshSubscription = {
                                        detailViewModel.refreshLiveSubscription(force = true)
                                    },
                                )
                            }
                        }
                    }

                    if (useNavigationRail && !hideChromeBars) {
                        Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                            AdBannerSlot(
                                onAdImpression = { boardViewModel.recordAdImpression() },
                            )
                        }
                    }
                }
            }
            }
        }
        }
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            currentTheme = uiState.themePreference,
            adImpressionCount = uiState.adImpressionCount,
            onSelectTheme = boardViewModel::setThemePreference,
            onDismiss = { showSettingsSheet = false },
            notifyNewIpoEnabled = uiState.notifyNewIpoEnabled,
            notifyOpenIpoEnabled = uiState.notifyOpenIpoEnabled,
            notifyAllotmentEnabled = allotmentUiState.notifyAllotmentEnabled,
            notifyNewsAlertsEnabled = uiState.notifyNewsAlertsEnabled,
            hasNotificationPermission = hasNotificationPermission,
            onToggleNotifyNewIpo = { enabled ->
                boardViewModel.setNotifyNewIpoEnabled(enabled)
                if (enabled) requestNotificationsIfNeeded()
            },
            onToggleNotifyOpenIpo = { enabled ->
                boardViewModel.setNotifyOpenIpoEnabled(enabled)
                if (enabled) requestNotificationsIfNeeded()
            },
            onToggleNotifyAllotment = { enabled ->
                allotmentViewModel.setNotifyAllotmentEnabled(enabled)
                if (enabled) requestNotificationsIfNeeded()
            },
            onToggleNotifyNewsAlerts = { enabled ->
                boardViewModel.setNotifyNewsAlertsEnabled(enabled)
                if (enabled) requestNotificationsIfNeeded()
            },
            onOpenAllotmentAlerts = {
                showSettingsSheet = false
                navController.navigate(AllotmentAlertsRoute()) { launchSingleTop = true }
            },
            onRequestNotificationPermission = ::requestNotificationsIfNeeded,
            onSendTestNotification = {
                if (hasNotificationPermission) {
                    boardViewModel.sendSampleAlertNotification()
                } else {
                    requestNotificationsIfNeeded()
                }
            },
            compactCardsEnabled = uiState.compactCardsEnabled,
            biometricLockEnabled = uiState.biometricLockEnabled,
            onToggleCompactCards = boardViewModel::setCompactCardsEnabled,
            onToggleBiometricLock = { enabled ->
                boardViewModel.setBiometricLockEnabled(enabled)
                if (!enabled) {
                    isVaultUnlocked = true
                    vaultAuthError = null
                }
            },
        )
    }

    if (showContactDialog) {
        ContactUsDialog(
            onDismiss = { showContactDialog = false },
        )
    }

    if (showExitDialog) {
        ExitAppDialog(
            onDismiss = { showExitDialog = false },
            onConfirmExit = {
                showExitDialog = false
                if (onExitApp != null) {
                    onExitApp()
                } else {
                    context.findHostActivity()?.finish()
                }
            },
        )
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
