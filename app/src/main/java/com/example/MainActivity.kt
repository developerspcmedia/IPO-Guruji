package com.example

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ads.AdMobManager
import com.example.ads.ConsentManager
import com.example.ai.configureAppCheck
import com.example.data.preferences.ThemePreference
import com.example.notifications.IpoNotificationManager
import com.example.ui.navigation.AppNavigation
import com.example.ui.screens.AppOpenSplashScreen
import com.example.ui.theme.IpoPulseTheme
import com.example.ui.theme.resolveEffectiveTheme
import com.example.ui.viewmodel.IpoBoardViewModel

class MainActivity : FragmentActivity() {
    private var pendingNotificationIpoId by mutableStateOf<String?>(null)
    private var pendingOpenAllotmentAlerts by mutableStateOf(false)
    private var pendingOpenNewsTab by mutableStateOf(false)
    private var pendingNewsArticleId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure Firebase App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        ConsentManager.gatherConsent(this) {
            AdMobManager.initialize(applicationContext)
        }
        IpoNotificationManager.ensureChannels(applicationContext)
        IpoNotificationManager.schedulePeriodicSync(applicationContext)
        pendingNotificationIpoId = intent?.getStringExtra(IpoNotificationManager.EXTRA_IPO_ID)
        pendingOpenAllotmentAlerts = intent?.getBooleanExtra(
            IpoNotificationManager.EXTRA_OPEN_ALLOTMENT_ALERTS,
            false,
        ) == true
        pendingOpenNewsTab = intent?.getBooleanExtra(
            IpoNotificationManager.EXTRA_OPEN_NEWS_TAB,
            false,
        ) == true
        pendingNewsArticleId = intent?.getStringExtra(IpoNotificationManager.EXTRA_NEWS_ARTICLE_ID)

        val container = (application as IpoApplication).appContainer
        val adminOverrideRepository = container.adminOverrideRepository
        val ipoRepository = container.ipoRepository
        val allotmentRepository = container.allotmentRepository
        val usIpoRepository = container.usIpoRepository
        val userProfileRepository = container.userProfileRepository
        val newsRepository = container.newsRepository
        val preferencesRepository = container.preferencesRepository

        setContent {
            val boardViewModel: IpoBoardViewModel = viewModel(
                factory = IpoBoardViewModel.Factory(
                    ipoRepository = ipoRepository,
                    preferencesRepository = preferencesRepository,
                    newsRepository = newsRepository,
                    appContext = applicationContext,
                ),
            )
            val uiState by boardViewModel.uiState.collectAsStateWithLifecycle()
            var showOpenPage by rememberSaveable { mutableStateOf(true) }

            val effectiveTheme = resolveEffectiveTheme(uiState.themePreference)
            val isDarkBars = effectiveTheme == ThemePreference.DARK

            LaunchedEffect(isDarkBars) {
                if (isDarkBars) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(
                            AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT,
                        ),
                        navigationBarStyle = SystemBarStyle.light(
                            AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT,
                        ),
                    )
                }
            }

            IpoPulseTheme(themePreference = uiState.themePreference) {
                Crossfade(
                    targetState = showOpenPage,
                    animationSpec = tween(durationMillis = 280),
                    label = "open_page_transition",
                ) { showingSplash ->
                    if (showingSplash) {
                        AppOpenSplashScreen(
                            onAdImpression = { boardViewModel.recordAdImpression() },
                            onFinished = { showOpenPage = false },
                        )
                    } else {
                        AppNavigation(
                            boardViewModel = boardViewModel,
                            uiState = uiState,
                            ipoRepository = ipoRepository,
                            adminOverrideRepository = adminOverrideRepository,
                            userProfileRepository = userProfileRepository,
                            allotmentRepository = allotmentRepository,
                            usIpoRepository = usIpoRepository,
                            pendingNotificationIpoId = pendingNotificationIpoId,
                            onConsumeNotificationIpoId = { pendingNotificationIpoId = null },
                            pendingOpenAllotmentAlerts = pendingOpenAllotmentAlerts,
                            onConsumeOpenAllotmentAlerts = { pendingOpenAllotmentAlerts = false },
                            pendingOpenNewsTab = pendingOpenNewsTab,
                            pendingNewsArticleId = pendingNewsArticleId,
                            onConsumeOpenNewsTab = {
                                pendingOpenNewsTab = false
                                pendingNewsArticleId = null
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val ipoId = intent.getStringExtra(IpoNotificationManager.EXTRA_IPO_ID)
        if (!ipoId.isNullOrBlank()) {
            pendingNotificationIpoId = ipoId
        }
        if (intent.getBooleanExtra(IpoNotificationManager.EXTRA_OPEN_ALLOTMENT_ALERTS, false)) {
            pendingOpenAllotmentAlerts = true
        }
        if (intent.getBooleanExtra(IpoNotificationManager.EXTRA_OPEN_NEWS_TAB, false)) {
            pendingOpenNewsTab = true
            pendingNewsArticleId = intent.getStringExtra(IpoNotificationManager.EXTRA_NEWS_ARTICLE_ID)
        }
    }
}
