package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.IpoSeedData
import com.example.data.model.BoardType
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoNewsArticle
import com.example.data.model.IpoNewsCatalog
import com.example.data.model.IpoStatus
import com.example.data.model.IpoTimelineBucket
import com.example.data.model.NewsCategory
import com.example.data.preferences.GmpWatchRule
import com.example.data.preferences.NewsNotificationPreferencesSnapshot
import com.example.data.preferences.ThemePreference
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.GmpSyncSummary
import com.example.data.repository.IpoFilingVerifier
import com.example.data.repository.IpoNewsRepository
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveSubscriptionRepository
import com.example.notifications.IpoNotificationManager
import com.example.widget.IpoLiveWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class BoardFilter(val label: String) {
    ALL("All"),
    MAINBOARD("Mainboard"),
    SME("SME"),
}

enum class StatusFilter(val label: String) {
    ALL("All IPOs"),
    LIVE("Open"),
    ALLOTMENT("Allotment"),
    PRE_APPLY("Pre-Apply"),
    UPCOMING("Upcoming"),
    CLOSED("Closed"),
}

enum class BoardTimelineTab(val label: String) {
    CURRENT("Current"),
    UPCOMING("Upcoming"),
    LISTED("Listed"),
}

enum class RadarSortMode(val label: String) {
    GMP_PERCENT("Top GMP %"),
    SUBSCRIPTION_X("Most Subscribed"),
    ISSUE_SIZE("IPO Size"),
}

enum class BoardSortMode(val label: String) {
    DEFAULT("Featured"),
    HIGHEST_GMP("Top GMP %"),
    HIGHEST_SUB("Top Subs"),
    CLOSING_SOON("Closing Soon"),
    ISSUE_SIZE("Issue Size"),
}

data class MarketPulseSummary(
    val liveCount: Int = 0,
    val preApplyCount: Int = 0,
    val upcomingCount: Int = 0,
    val closedCount: Int = 0,
    val smeCount: Int = 0,
    val avgPositiveGmpPercent: Double = 0.0,
)

data class IpoBoardUiState(
    val allIpos: List<IpoEntity> = emptyList(),
    val filteredIpos: List<IpoEntity> = emptyList(),
    val radarIpos: List<IpoEntity> = emptyList(),
    val newsArticles: List<IpoNewsArticle> = IpoNewsCatalog.getLatestNews(),
    val boardFilter: BoardFilter = BoardFilter.ALL,
    val timelineTab: BoardTimelineTab = BoardTimelineTab.CURRENT,
    val statusFilter: StatusFilter = StatusFilter.ALL,
    val boardSortMode: BoardSortMode = BoardSortMode.DEFAULT,
    val savedOnlyFilter: Boolean = false,
    val bookmarkedIpoIds: Set<String> = emptySet(),
    val compactCardsEnabled: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val radarSortMode: RadarSortMode = RadarSortMode.GMP_PERCENT,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isRefreshing: Boolean = false,
    val isRefreshingGmp: Boolean = false,
    val lastSyncedLabel: String = "Syncing...",
    val liveGmpSourcesLabel: String = "NSE India • InvestorGain • IPOWatch • Chittorgarh • IPO Central",
    val gmpStatusMessage: String? = null,
    val subscriptionSourcesStatusLine: String? = null,
    val liveNewsSourcesLabel: String = "ET IPO • LiveMint • Google News",
    val marketPulse: MarketPulseSummary = MarketPulseSummary(),
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val adImpressionCount: Int = 0,
    val notifyNewIpoEnabled: Boolean = true,
    val notifyOpenIpoEnabled: Boolean = true,
    val notifyNewsAlertsEnabled: Boolean = true,
    val newsAlertCategories: Set<NewsCategory> = NewsNotificationPreferencesSnapshot.DEFAULT_NEWS_ALERT_CATEGORIES,
    val unreadNewsCount: Int = 0,
    val day3ReminderIpoIds: Set<String> = emptySet(),
    val gmpWatchRules: Map<String, GmpWatchRule> = emptyMap(),
    val isLoading: Boolean = true,
)

class IpoBoardViewModel(
    private val ipoRepository: IpoRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val newsRepository: IpoNewsRepository = IpoNewsRepository(),
    private val appContext: Context? = null,
) : ViewModel() {

    private val boardFilter = MutableStateFlow(BoardFilter.ALL)
    private val timelineTab = MutableStateFlow(BoardTimelineTab.CURRENT)
    private val statusFilter = MutableStateFlow(StatusFilter.ALL)
    private val boardSortMode = MutableStateFlow(BoardSortMode.DEFAULT)
    private val savedOnlyFilter = MutableStateFlow(false)
    private val radarSortMode = MutableStateFlow(RadarSortMode.GMP_PERCENT)
    private val searchQuery = MutableStateFlow("")
    private val isSearchActive = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)
    private val isRefreshingGmp = MutableStateFlow(false)
    private val newsArticles = MutableStateFlow(IpoNewsCatalog.getLatestNews())
    private val lastSyncedLabel = MutableStateFlow("Syncing...")
    private val liveGmpSourcesLabel = MutableStateFlow("NSE India • InvestorGain • IPOWatch • Chittorgarh • IPO Central")
    private val gmpStatusMessage = MutableStateFlow<String?>(null)
    private val subscriptionSourcesStatusLine = MutableStateFlow<String?>(null)
    private val liveNewsSourcesLabel = MutableStateFlow("ET IPO • LiveMint • Google News")
    private var lastSyncEpochMs: Long = 0L
    private var lastSuccessfulSyncLabel: String? = null
    private var isBoardVisible: Boolean = false
    private var boardPollJob: Job? = null

    init {
        refreshAllOnOpen()
    }

    private val primaryFilterFlow = combine(
        boardFilter,
        timelineTab,
        statusFilter,
        boardSortMode,
        savedOnlyFilter,
    ) { board, timeline, status, boardSort, savedOnly ->
        PrimaryFilters(board, timeline, status, boardSort, savedOnly)
    }

    private val filterFlow = combine(
        primaryFilterFlow,
        radarSortMode,
        searchQuery,
        isSearchActive,
    ) { primary, sortMode, query, searchOpen ->
        FilterParams(
            board = primary.board,
            timeline = primary.timeline,
            status = primary.status,
            boardSort = primary.boardSort,
            savedOnly = primary.savedOnly,
            sortMode = sortMode,
            query = query,
            searchOpen = searchOpen,
        )
    }

    private val syncStatusFlow = combine(
        isRefreshing,
        isRefreshingGmp,
        lastSyncedLabel,
        liveGmpSourcesLabel,
        gmpStatusMessage,
    ) { refreshing, refreshingGmp, syncedLabel, gmpSources, statusMsg ->
        SyncStatus(refreshing, refreshingGmp, syncedLabel, gmpSources, statusMsg)
    }

    private val syncMetaFlow = combine(
        syncStatusFlow,
        newsArticles,
        liveNewsSourcesLabel,
        subscriptionSourcesStatusLine,
    ) { status, articles, sourcesLabel, subSourcesLine ->
        SyncMeta(
            isRefreshing = status.isRefreshing,
            isRefreshingGmp = status.isRefreshingGmp,
            newsArticles = articles,
            lastSyncedLabel = status.lastSyncedLabel,
            liveGmpSourcesLabel = status.liveGmpSourcesLabel,
            gmpStatusMessage = status.gmpStatusMessage,
            subscriptionSourcesStatusLine = subSourcesLine,
            liveNewsSourcesLabel = sourcesLabel,
        )
    }

    private val newsPrefsFlow = combine(
        preferencesRepository.notifyNewsAlertsEnabled,
        preferencesRepository.newsAlertCategories,
        preferencesRepository.unreadNewsIds,
        preferencesRepository.day3ReminderIpoIds,
        preferencesRepository.gmpWatchRules,
    ) { notifyNews, categories, unreadIds, day3Ids, gmpRules ->
        NewsAndSmartAlertsMeta(
            notifyNewsAlertsEnabled = notifyNews,
            newsAlertCategories = categories,
            unreadNewsCount = unreadIds.size,
            day3ReminderIpoIds = day3Ids,
            gmpWatchRules = gmpRules,
        )
    }

    private val uxPrefsFlow = combine(
        preferencesRepository.bookmarkedIpoIds,
        preferencesRepository.compactCardsEnabled,
        preferencesRepository.biometricLockEnabled,
    ) { bookmarks, compactCards, biometricLock ->
        UxPrefsMeta(
            bookmarkedIpoIds = bookmarks,
            compactCardsEnabled = compactCards,
            biometricLockEnabled = biometricLock,
        )
    }

    private val corePrefsFlow = combine(
        preferencesRepository.themePreference,
        preferencesRepository.adImpressionCount,
        preferencesRepository.notifyNewIpoEnabled,
        preferencesRepository.notifyOpenIpoEnabled,
    ) { themePref, impressions, notifyNew, notifyOpen ->
        CorePrefsMeta(
            themePreference = themePref,
            adImpressionCount = impressions,
            notifyNewIpoEnabled = notifyNew,
            notifyOpenIpoEnabled = notifyOpen,
        )
    }

    private val userPrefsFlow = combine(
        corePrefsFlow,
        newsPrefsFlow,
        uxPrefsFlow,
    ) { core, alertsMeta, ux ->
        UserPrefsMeta(
            themePreference = core.themePreference,
            adImpressionCount = core.adImpressionCount,
            notifyNewIpoEnabled = core.notifyNewIpoEnabled,
            notifyOpenIpoEnabled = core.notifyOpenIpoEnabled,
            notifyNewsAlertsEnabled = alertsMeta.notifyNewsAlertsEnabled,
            newsAlertCategories = alertsMeta.newsAlertCategories,
            unreadNewsCount = alertsMeta.unreadNewsCount,
            day3ReminderIpoIds = alertsMeta.day3ReminderIpoIds,
            gmpWatchRules = alertsMeta.gmpWatchRules,
            bookmarkedIpoIds = ux.bookmarkedIpoIds,
            compactCardsEnabled = ux.compactCardsEnabled,
            biometricLockEnabled = ux.biometricLockEnabled,
        )
    }

    val uiState: StateFlow<IpoBoardUiState> = combine(
        ipoRepository.observeAllIpos(),
        filterFlow,
        syncMetaFlow,
        userPrefsFlow,
    ) { rawIpos, filters, syncMeta, userPrefs ->
        val ipos = rawIpos.filter { IpoFilingVerifier.isDisplayableEntity(it) }
        val query = filters.query.trim().lowercase()
        val timelineScopedIpos = when (filters.timeline) {
            BoardTimelineTab.CURRENT -> ipos.filter { ipo ->
                IpoDateUtils.classifyTimelineTab(
                    openDate = ipo.openDate,
                    closeDate = ipo.closeDate,
                    listingDate = ipo.listingDate,
                    status = ipo.status,
                ) == IpoTimelineBucket.CURRENT
            }
            BoardTimelineTab.UPCOMING -> ipos.filter { ipo ->
                IpoDateUtils.classifyTimelineTab(
                    openDate = ipo.openDate,
                    closeDate = ipo.closeDate,
                    listingDate = ipo.listingDate,
                    status = ipo.status,
                ) == IpoTimelineBucket.UPCOMING
            }
            BoardTimelineTab.LISTED -> ipos.filter { ipo ->
                IpoDateUtils.classifyTimelineTab(
                    openDate = ipo.openDate,
                    closeDate = ipo.closeDate,
                    listingDate = ipo.listingDate,
                    status = ipo.status,
                ) == IpoTimelineBucket.LISTED
            }.sortedByDescending { ipo ->
                IpoDateUtils.parseFlexibleDateMs(ipo.listingDate)
                    ?: IpoDateUtils.parseFlexibleDateMs(ipo.closeDate)
                    ?: 0L
            }
        }
        val filtered = timelineScopedIpos.filter { ipo ->
            val boardMatches = when (filters.board) {
                BoardFilter.ALL -> true
                BoardFilter.MAINBOARD -> ipo.boardType == BoardType.MAINBOARD
                BoardFilter.SME -> ipo.boardType == BoardType.SME
            }
            val queryMatches = if (query.isEmpty()) {
                true
            } else {
                ipo.companyName.lowercase().contains(query) ||
                    ipo.shortCode.lowercase().contains(query) ||
                    ipo.sector.lowercase().contains(query) ||
                    ipo.exchange.lowercase().contains(query)
            }
            val savedMatches = if (filters.savedOnly) {
                ipo.id in userPrefs.bookmarkedIpoIds
            } else {
                true
            }
            boardMatches && queryMatches && savedMatches
        }.let { list ->
            when (filters.boardSort) {
                BoardSortMode.DEFAULT -> when (filters.timeline) {
                    BoardTimelineTab.CURRENT -> list.sortedWith(
                        compareBy<IpoEntity> { ipo ->
                            val isOpenNow = IpoDateUtils.isBiddingOpenToday(
                                openDate = ipo.openDate,
                                closeDate = ipo.closeDate,
                                listingDate = ipo.listingDate,
                                status = ipo.status,
                            )
                            val inAllotment = IpoDateUtils.isInAllotmentPhase(
                                closeDate = ipo.closeDate,
                                listingDate = ipo.listingDate,
                                status = ipo.status,
                            )
                            when {
                                isOpenNow -> 0
                                inAllotment -> 1
                                else -> 2
                            }
                        }.thenBy { ipo ->
                            val isOpenNow = IpoDateUtils.isBiddingOpenToday(
                                openDate = ipo.openDate,
                                closeDate = ipo.closeDate,
                                listingDate = ipo.listingDate,
                                status = ipo.status,
                            )
                            if (isOpenNow) {
                                IpoDateUtils.parseFlexibleDateMs(ipo.closeDate) ?: Long.MAX_VALUE
                            } else {
                                IpoDateUtils.parseFlexibleDateMs(ipo.listingDate)
                                    ?: IpoDateUtils.parseFlexibleDateMs(ipo.closeDate)
                                    ?: Long.MAX_VALUE
                            }
                        }.thenBy { it.sortOrder },
                    )
                    BoardTimelineTab.UPCOMING -> list.sortedWith(
                        compareBy<IpoEntity> { ipo ->
                            IpoDateUtils.parseFlexibleDateMs(ipo.openDate) ?: Long.MAX_VALUE
                        }.thenBy { it.sortOrder }
                            .thenBy { it.companyName.lowercase() },
                    )
                    BoardTimelineTab.LISTED -> list
                }
                BoardSortMode.HIGHEST_GMP -> list.sortedByDescending { it.gmpPercent }
                BoardSortMode.HIGHEST_SUB -> list.sortedByDescending { it.totalSubX }
                BoardSortMode.CLOSING_SOON -> list.sortedBy { ipo ->
                    IpoDateUtils.parseFlexibleDateMs(ipo.closeDate) ?: Long.MAX_VALUE
                }
                BoardSortMode.ISSUE_SIZE -> list.sortedByDescending { it.issueSizeCr }
            }
        }

        val radar = when (filters.sortMode) {
            RadarSortMode.GMP_PERCENT -> ipos.sortedByDescending { it.gmpPercent }
            RadarSortMode.SUBSCRIPTION_X -> ipos.sortedByDescending { it.totalSubX }
            RadarSortMode.ISSUE_SIZE -> ipos.sortedByDescending { it.issueSizeCr }
        }

        val positiveGmps = ipos.filter { it.gmpRupees > 0 }
        val avgGmp = if (positiveGmps.isNotEmpty()) {
            positiveGmps.map { it.gmpPercent }.average()
        } else {
            0.0
        }

        IpoBoardUiState(
            allIpos = ipos,
            filteredIpos = filtered,
            radarIpos = radar,
            newsArticles = syncMeta.newsArticles,
            boardFilter = filters.board,
            timelineTab = filters.timeline,
            statusFilter = filters.status,
            boardSortMode = filters.boardSort,
            savedOnlyFilter = filters.savedOnly,
            bookmarkedIpoIds = userPrefs.bookmarkedIpoIds,
            compactCardsEnabled = userPrefs.compactCardsEnabled,
            biometricLockEnabled = userPrefs.biometricLockEnabled,
            radarSortMode = filters.sortMode,
            searchQuery = filters.query,
            isSearchActive = filters.searchOpen,
            isRefreshing = syncMeta.isRefreshing,
            isRefreshingGmp = syncMeta.isRefreshingGmp,
            lastSyncedLabel = syncMeta.lastSyncedLabel,
            liveGmpSourcesLabel = syncMeta.liveGmpSourcesLabel,
            gmpStatusMessage = syncMeta.gmpStatusMessage,
            subscriptionSourcesStatusLine = syncMeta.subscriptionSourcesStatusLine,
            liveNewsSourcesLabel = syncMeta.liveNewsSourcesLabel,
            marketPulse = MarketPulseSummary(
                liveCount = ipos.count { it.status == IpoStatus.LIVE },
                preApplyCount = ipos.count { it.status == IpoStatus.PRE_APPLY },
                upcomingCount = ipos.count { it.status == IpoStatus.UPCOMING },
                closedCount = ipos.count { it.status == IpoStatus.CLOSED },
                smeCount = ipos.count { it.boardType == BoardType.SME },
                avgPositiveGmpPercent = avgGmp,
            ),
            themePreference = userPrefs.themePreference,
            adImpressionCount = userPrefs.adImpressionCount,
            notifyNewIpoEnabled = userPrefs.notifyNewIpoEnabled,
            notifyOpenIpoEnabled = userPrefs.notifyOpenIpoEnabled,
            notifyNewsAlertsEnabled = userPrefs.notifyNewsAlertsEnabled,
            newsAlertCategories = userPrefs.newsAlertCategories,
            unreadNewsCount = userPrefs.unreadNewsCount,
            day3ReminderIpoIds = userPrefs.day3ReminderIpoIds,
            gmpWatchRules = userPrefs.gmpWatchRules,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IpoBoardUiState(),
    )

    fun refreshAllOnOpen() {
        refreshMarketPulse()
    }

    fun onAppForegrounded() {
        val elapsedMs = System.currentTimeMillis() - lastSyncEpochMs
        if (elapsedMs > 30_000L) {
            refreshMarketPulse()
        }
    }

    fun onBoardVisible() {
        isBoardVisible = true
        if (boardPollJob?.isActive == true) return
        boardPollJob = viewModelScope.launch {
            while (isActive && isBoardVisible) {
                delay(LiveSubscriptionRepository.BOARD_POLL_INTERVAL_MS)
                if (!isBoardVisible) break
                if (LiveSubscriptionRepository.shouldPollBoard(uiState.value.allIpos)) {
                    refreshBoardSubscriptionsQuietly()
                }
            }
        }
    }

    fun onBoardHidden() {
        isBoardVisible = false
        boardPollJob?.cancel()
        boardPollJob = null
    }

    private suspend fun refreshBoardSubscriptionsQuietly() {
        if (isRefreshing.value || isRefreshingGmp.value) return
        runCatching {
            ipoRepository.refreshLiveSubscriptionsOnly(force = true)
        }.onSuccess { result ->
            LiveSubscriptionRepository.formatSourceStatuses(result.sourceStatuses)?.let {
                subscriptionSourcesStatusLine.value = it
            }
            if (result.hasLiveSourceData && result.syncedAtLabel.isNotBlank()) {
                lastSuccessfulSyncLabel = result.syncedAtLabel
                lastSyncedLabel.value = result.syncedAtLabel
                lastSyncEpochMs = System.currentTimeMillis()
            } else {
                lastSyncedLabel.value = lastSuccessfulSyncLabel?.let {
                    "Last updated $it (couldn't refresh now)"
                } ?: lastSyncedLabel.value
            }
        }
    }

    fun setBoardFilter(filter: BoardFilter) {
        boardFilter.value = filter
    }

    fun setTimelineTab(tab: BoardTimelineTab) {
        timelineTab.value = tab
    }

    fun setStatusFilter(filter: StatusFilter) {
        statusFilter.value = filter
    }

    fun setBoardSortMode(mode: BoardSortMode) {
        boardSortMode.value = mode
    }

    fun setSavedOnlyFilter(savedOnly: Boolean) {
        savedOnlyFilter.value = savedOnly
    }

    fun toggleBookmarkIpo(ipoId: String) {
        viewModelScope.launch {
            preferencesRepository.toggleBookmarkIpo(ipoId)
        }
    }

    fun setCompactCardsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setCompactCardsEnabled(enabled)
        }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBiometricLockEnabled(enabled)
        }
    }

    fun setRadarSortMode(mode: RadarSortMode) {
        radarSortMode.value = mode
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSearchActive(active: Boolean) {
        isSearchActive.value = active
        if (!active) searchQuery.value = ""
    }

    fun refreshGmpNow() {
        if (isRefreshingGmp.value) return
        viewModelScope.launch {
            isRefreshingGmp.value = true
            try {
                val gmpSummary = ipoRepository.syncWithLiveGmp()
                applyGmpSummary(gmpSummary, manualTap = true)
                dispatchSyncNotifications(gmpSummary)
            } finally {
                isRefreshingGmp.value = false
            }
        }
    }

    fun refreshMarketPulse() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            isRefreshingGmp.value = true
            try {
                val gmpDeferred = async { ipoRepository.syncWithLiveGmp() }
                val newsDeferred = async {
                    newsRepository.fetchLatestNews(IpoSeedData.createInitialCatalog())
                }
                val gmpSummary = gmpDeferred.await()
                val newsResult = newsDeferred.await()

                newsArticles.value = newsResult.articles
                liveNewsSourcesLabel.value = newsResult.activeSourceNames.joinToString(" • ")
                applyGmpSummary(gmpSummary, manualTap = false, hasLiveNews = newsResult.liveSourceCount > 0)
                dispatchSyncNotifications(gmpSummary)
                dispatchNewsNotifications(newsResult.articles)
            } finally {
                isRefreshing.value = false
                isRefreshingGmp.value = false
            }
        }
    }

    private suspend fun dispatchSyncNotifications(summary: GmpSyncSummary) {
        val ctx = appContext ?: return
        IpoNotificationManager.processSyncNotifications(
            context = ctx,
            preferencesRepository = preferencesRepository,
            previousIpos = summary.previousIpos,
            updatedIpos = summary.updatedIpos,
        )
        IpoNotificationManager.processSmartIpoAlerts(
            context = ctx,
            preferencesRepository = preferencesRepository,
            ipos = summary.updatedIpos,
        )
        IpoLiveWidgetProvider.requestWidgetUpdate(ctx)
    }

    private suspend fun dispatchNewsNotifications(articles: List<IpoNewsArticle>) {
        val ctx = appContext ?: return
        IpoNotificationManager.processNewsSyncNotifications(
            context = ctx,
            preferencesRepository = preferencesRepository,
            articles = articles,
        )
    }

    internal fun applyGmpSummary(
        gmpSummary: GmpSyncSummary,
        manualTap: Boolean,
        hasLiveNews: Boolean = false,
    ) {
        LiveSubscriptionRepository.formatSourceStatuses(gmpSummary.subscriptionSourceStatuses)?.let {
            subscriptionSourcesStatusLine.value = it
        }
        if (gmpSummary.activeSources.isNotEmpty()) {
            val distinctProviders = gmpSummary.activeSources
                .mapNotNull { IpoFilingVerifier.providerFamily(it).takeIf { f -> f.isNotBlank() } }
                .distinct()
            val sourcesJoined = distinctProviders.ifEmpty { gmpSummary.activeSources }.joinToString(" • ")
            liveGmpSourcesLabel.value = sourcesJoined
            val countTotal = gmpSummary.updatedExistingCount + gmpSummary.discoveredLiveCount
            val filteredNote = if (gmpSummary.excludedUnconfirmedCount > 0) {
                " • ${gmpSummary.excludedUnconfirmedCount} unconfirmed hidden"
            } else {
                ""
            }
            gmpStatusMessage.value = if (countTotal > 0) {
                "Verified $countTotal filed IPOs across ${distinctProviders.size.coerceAtLeast(1)} sources$filteredNote • ${gmpSummary.syncedAtTime}"
            } else {
                "Cross-verified ($sourcesJoined)$filteredNote • ${gmpSummary.syncedAtTime}"
            }
            lastSuccessfulSyncLabel = gmpSummary.syncedAtTime
            lastSyncedLabel.value = gmpSummary.syncedAtTime
            lastSyncEpochMs = System.currentTimeMillis()
        } else {
            val failureLabel = lastSuccessfulSyncLabel?.let {
                "Last updated $it (couldn't refresh now)"
            } ?: "Couldn't refresh now"
            lastSyncedLabel.value = failureLabel
            if (manualTap) {
                gmpStatusMessage.value = failureLabel
            }
        }
    }

    internal fun currentLastSyncedLabelForTest(): String = lastSyncedLabel.value

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch {
            preferencesRepository.setThemePreference(preference)
        }
    }

    fun setNotifyNewIpoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyNewIpoEnabled(enabled)
        }
    }

    fun setNotifyOpenIpoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyOpenIpoEnabled(enabled)
        }
    }

    fun setNotifyNewsAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyNewsAlertsEnabled(enabled)
        }
    }

    fun toggleNewsAlertCategory(category: NewsCategory) {
        if (category == NewsCategory.ALL) return
        viewModelScope.launch {
            val current = uiState.value.newsAlertCategories.toMutableSet()
            if (category in current) {
                if (current.size > 1) {
                    current.remove(category)
                }
            } else {
                current.add(category)
            }
            preferencesRepository.setNewsAlertCategories(current)
        }
    }

    fun markNewsAsRead() {
        viewModelScope.launch {
            preferencesRepository.markAllNewsRead()
        }
    }

    fun setDay3Reminder(ipoId: String, enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDay3Reminder(ipoId, enabled)
            if (enabled) {
                val ctx = appContext ?: return@launch
                IpoNotificationManager.processSmartIpoAlerts(
                    context = ctx,
                    preferencesRepository = preferencesRepository,
                    ipos = uiState.value.allIpos,
                )
            }
        }
    }

    fun setGmpWatchRule(ipoId: String, minGmpPercent: Int?, maxGmpPercent: Int?) {
        viewModelScope.launch {
            preferencesRepository.setGmpWatchRule(ipoId, minGmpPercent, maxGmpPercent)
            if (minGmpPercent != null || maxGmpPercent != null) {
                val ctx = appContext ?: return@launch
                IpoNotificationManager.processSmartIpoAlerts(
                    context = ctx,
                    preferencesRepository = preferencesRepository,
                    ipos = uiState.value.allIpos,
                )
            }
        }
    }

    fun sendSampleAlertNotification() {
        val ctx = appContext ?: return
        val ipos = uiState.value.allIpos
        val candidate = ipos.firstOrNull { it.status == IpoStatus.LIVE }
            ?: ipos.firstOrNull()
            ?: return
        IpoNotificationManager.postSubscriptionOpenNotification(ctx, candidate)
    }

    fun setUsaNewsAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value.newsAlertCategories.toMutableSet()
            if (enabled) {
                current.add(NewsCategory.USA_IPOS)
                preferencesRepository.setNotifyNewsAlertsEnabled(true)
                preferencesRepository.setNewsAlertCategories(current)
            } else {
                if (current.size > 1) {
                    current.remove(NewsCategory.USA_IPOS)
                    preferencesRepository.setNewsAlertCategories(current)
                } else {
                    preferencesRepository.setNotifyNewsAlertsEnabled(false)
                }
            }
        }
    }

    fun sendSampleNewsNotification() {
        val ctx = appContext ?: return
        val articles = uiState.value.newsArticles
        val enabledCats = uiState.value.newsAlertCategories
        val candidate = articles.firstOrNull { it.category in enabledCats }
            ?: articles.firstOrNull()
            ?: return
        IpoNotificationManager.postSampleNewsAlertNotification(ctx, candidate)
    }

    fun sendSampleUsaNewsNotification() {
        val ctx = appContext ?: return
        val articles = uiState.value.newsArticles
        val candidate = articles.firstOrNull { it.category == NewsCategory.USA_IPOS }
            ?: IpoNewsCatalog.getLatestNews().firstOrNull { it.category == NewsCategory.USA_IPOS }
            ?: return
        IpoNotificationManager.postSampleNewsAlertNotification(ctx, candidate)
    }

    fun recordAdImpression() {
        viewModelScope.launch {
            preferencesRepository.recordAdImpression()
        }
    }

    suspend fun recordDetailOpenAndGetCount(): Int = preferencesRepository.incrementDetailOpen()

    private data class PrimaryFilters(
        val board: BoardFilter,
        val timeline: BoardTimelineTab,
        val status: StatusFilter,
        val boardSort: BoardSortMode,
        val savedOnly: Boolean,
    )

    private data class FilterParams(
        val board: BoardFilter,
        val timeline: BoardTimelineTab,
        val status: StatusFilter,
        val boardSort: BoardSortMode,
        val savedOnly: Boolean,
        val sortMode: RadarSortMode,
        val query: String,
        val searchOpen: Boolean,
    )

    private data class SyncStatus(
        val isRefreshing: Boolean,
        val isRefreshingGmp: Boolean,
        val lastSyncedLabel: String,
        val liveGmpSourcesLabel: String,
        val gmpStatusMessage: String?,
    )

    override fun onCleared() {
        onBoardHidden()
        super.onCleared()
    }

    private data class SyncMeta(
        val isRefreshing: Boolean,
        val isRefreshingGmp: Boolean,
        val newsArticles: List<IpoNewsArticle>,
        val lastSyncedLabel: String,
        val liveGmpSourcesLabel: String,
        val gmpStatusMessage: String?,
        val subscriptionSourcesStatusLine: String?,
        val liveNewsSourcesLabel: String,
    )

    private data class NewsAndSmartAlertsMeta(
        val notifyNewsAlertsEnabled: Boolean,
        val newsAlertCategories: Set<NewsCategory>,
        val unreadNewsCount: Int,
        val day3ReminderIpoIds: Set<String>,
        val gmpWatchRules: Map<String, GmpWatchRule>,
    )

    private data class UxPrefsMeta(
        val bookmarkedIpoIds: Set<String>,
        val compactCardsEnabled: Boolean,
        val biometricLockEnabled: Boolean,
    )

    private data class CorePrefsMeta(
        val themePreference: ThemePreference,
        val adImpressionCount: Int,
        val notifyNewIpoEnabled: Boolean,
        val notifyOpenIpoEnabled: Boolean,
    )

    private data class UserPrefsMeta(
        val themePreference: ThemePreference,
        val adImpressionCount: Int,
        val notifyNewIpoEnabled: Boolean,
        val notifyOpenIpoEnabled: Boolean,
        val notifyNewsAlertsEnabled: Boolean,
        val newsAlertCategories: Set<NewsCategory>,
        val unreadNewsCount: Int,
        val day3ReminderIpoIds: Set<String>,
        val gmpWatchRules: Map<String, GmpWatchRule>,
        val bookmarkedIpoIds: Set<String>,
        val compactCardsEnabled: Boolean,
        val biometricLockEnabled: Boolean,
    )

    class Factory(
        private val ipoRepository: IpoRepository,
        private val preferencesRepository: UserPreferencesRepository,
        private val newsRepository: IpoNewsRepository = IpoNewsRepository(),
        private val appContext: Context? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return IpoBoardViewModel(ipoRepository, preferencesRepository, newsRepository, appContext) as T
        }
    }
}
