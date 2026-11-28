package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ads.NativeAdSlot
import com.example.data.model.IpoEntity
import com.example.ui.components.IpoCard
import com.example.ui.components.ShareIpoBottomSheet
import com.example.ui.components.ShareSummaryBottomSheet
import com.example.ui.components.buildBoardShareMessage
import com.example.ui.components.glassBorderStroke
import com.example.ui.components.glassPanel
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import com.example.ui.viewmodel.BoardFilter
import com.example.ui.viewmodel.BoardSortMode
import com.example.ui.viewmodel.BoardTimelineTab
import com.example.ui.viewmodel.IpoBoardUiState
import com.example.ui.viewmodel.StatusFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpoBoardScreen(
    uiState: IpoBoardUiState,
    onSelectBoardFilter: (BoardFilter) -> Unit,
    onSelectStatusFilter: (StatusFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenRadar: () -> Unit,
    onOpenSettings: () -> Unit,
    onIpoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSelectTimelineTab: (BoardTimelineTab) -> Unit = {},
    onRefreshGmp: () -> Unit = onRefresh,
    onAdImpression: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenCompare: (String?) -> Unit = {},
    onSelectBoardSortMode: (BoardSortMode) -> Unit = {},
    onToggleSavedOnly: (Boolean) -> Unit = {},
    onToggleBookmarkIpo: (String) -> Unit = {},
    onToggleCompactCards: (Boolean) -> Unit = {},
) {
    val marketColors = LocalMarketColors.current
    var shareTargetIpo by remember { mutableStateOf<IpoEntity?>(null) }
    var showBoardShareSheet by remember { mutableStateOf(false) }
    val countWord = if (uiState.filteredIpos.size == 1) "IPO" else "IPOs"
    val boardSubtitle = when (uiState.boardFilter) {
        BoardFilter.ALL -> "All • ${uiState.filteredIpos.size} $countWord"
        BoardFilter.MAINBOARD -> "Mainboard • ${uiState.filteredIpos.size} $countWord"
        BoardFilter.SME -> "SME • ${uiState.filteredIpos.size} $countWord"
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            // Sleek Top Header with Hamburger Menu, Single Brand Logo & Essential Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onOpenMenu,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("menu_drawer_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "Open navigation menu",
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("opening_brand_logo"),
                        shape = RoundedCornerShape(9.dp),
                        color = Color(0xFF080B12),
                        border = BorderStroke(1.dp, marketColors.border),
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_ipo_guruji_logo),
                            contentDescription = "IPO Guruji Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(modifier = Modifier.width(7.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "IPO Guruji",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = boardSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = marketColors.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showBoardShareSheet = true },
                        modifier = Modifier
                            .width(42.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("share_board_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share live IPOs and GMP",
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    IconButton(
                        onClick = onOpenCalendar,
                        modifier = Modifier
                            .width(42.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("calendar_shortcut_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = "IPO Event Calendar",
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    IconButton(
                        onClick = { onToggleSearch(!uiState.isSearchActive) },
                        modifier = Modifier
                            .width(42.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("search_toggle_button"),
                    ) {
                        Icon(
                            imageVector = if (uiState.isSearchActive) Icons.Rounded.Close else Icons.Outlined.Search,
                            contentDescription = if (uiState.isSearchActive) "Close search" else "Search IPOs",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .width(42.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Theme, alerts and settings",
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = uiState.isSearchActive) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                        .testTag("ipo_search_input"),
                    placeholder = { Text("Search company, NSE/BSE SME...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }

            // Capsule Segmented Switcher: Current | Upcoming | Listed
            TimelineSegmentedToggle(
                selectedTab = uiState.timelineTab,
                onSelectTab = onSelectTimelineTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 2.dp, bottom = 8.dp),
            )

            // Unified Board Filter Row: All | Mainboard | SME
            BoardSegmentFilterRow(
                selectedFilter = uiState.boardFilter,
                onSelectFilter = onSelectBoardFilter,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            )

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("ipo_lazy_column"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 2.dp,
                        bottom = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Compact Last Sync Date/Time + GMP Refresh & Live Subscription Radar Strip
                    item(key = "market_pulse") {
                        MarketPulseBanner(
                            lastSyncedLabel = uiState.lastSyncedLabel,
                            isDataDelayed = uiState.isDataDelayed,
                            subscriptionSourcesStatusLine = uiState.subscriptionSourcesStatusLine,
                            isRefreshingGmp = uiState.isRefreshingGmp || uiState.isRefreshing,
                            onRefreshGmp = onRefreshGmp,
                            onOpenRadar = onOpenRadar,
                        )
                    }

                    // Prominent Main-Screen Compare IPOs Quick Action Strip
                    item(key = "compare_ipos_banner") {
                        CompareIposQuickBanner(
                            ipoCount = uiState.allIpos.size,
                            onClick = { onOpenCompare(null) },
                        )
                    }

                    if (uiState.isLoading && uiState.filteredIpos.isEmpty()) {
                        items(3, key = { "shimmer_$it" }) { idx ->
                            IpoShimmerSkeletonCard(
                                modifier = if (idx == 0) Modifier.testTag("ipo_shimmer_skeleton") else Modifier,
                            )
                        }
                    } else if (uiState.filteredIpos.isEmpty() && !uiState.isLoading) {
                        item(key = "empty_state") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, marketColors.border),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = if (uiState.savedOnlyFilter) {
                                            Icons.Outlined.BookmarkBorder
                                        } else {
                                            Icons.Rounded.SearchOff
                                        },
                                        contentDescription = null,
                                        tint = marketColors.mutedText,
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (uiState.savedOnlyFilter) "No Saved IPOs Yet" else "No matching IPOs",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (uiState.savedOnlyFilter) {
                                            "Tap the bookmark icon on any IPO card to build your personal watchlist."
                                        } else {
                                            "Try switching between Current, Upcoming, Listed, or All / Mainboard / SME tabs."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = marketColors.mutedText,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    TextButton(
                                        onClick = {
                                            onToggleSavedOnly(false)
                                            onSelectBoardSortMode(BoardSortMode.DEFAULT)
                                            onSelectTimelineTab(BoardTimelineTab.CURRENT)
                                            onSelectBoardFilter(BoardFilter.ALL)
                                            onSelectStatusFilter(StatusFilter.ALL)
                                            onSearchQueryChange("")
                                        },
                                    ) {
                                        Text("Reset Filters")
                                    }
                                }
                            }
                        }
                    } else {
                        val nativeAdInsertAfterIndex = if (uiState.filteredIpos.size >= 2) 1 else 0
                        itemsIndexed(
                            items = uiState.filteredIpos,
                            key = { _, ipo -> ipo.id },
                        ) { index, ipo ->
                            IpoCard(
                                ipo = ipo,
                                onClick = { onIpoClick(ipo.id) },
                                onShareClick = { shareTargetIpo = ipo },
                                isListedTab = uiState.timelineTab == BoardTimelineTab.LISTED,
                                isBookmarked = ipo.id in uiState.bookmarkedIpoIds,
                                onToggleBookmark = { onToggleBookmarkIpo(ipo.id) },
                                onCompareClick = { onOpenCompare(ipo.id) },
                                compactMode = uiState.compactCardsEnabled,
                            )
                            if (index == nativeAdInsertAfterIndex) {
                                NativeAdSlot(
                                    topPaddingDp = 12,
                                    onAdImpression = onAdImpression,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    shareTargetIpo?.let { target ->
        ShareIpoBottomSheet(
            ipo = target,
            onDismiss = { shareTargetIpo = null },
        )
    }

    if (showBoardShareSheet) {
        val boardSummary = remember(uiState.filteredIpos) {
            buildBoardShareMessage(uiState.filteredIpos)
        }
        ShareSummaryBottomSheet(
            title = "Share Live IPOs & GMP",
            subtitle = "Share current IPO dates, price bands, issue sizes, and live GMP via social apps.",
            subject = "IPO Guruji • Live IPOs & GMP Summary",
            shareText = boardSummary,
            onDismiss = { showBoardShareSheet = false },
        )
    }
}

@Composable
private fun TimelineSegmentedToggle(
    selectedTab: BoardTimelineTab,
    onSelectTab: (BoardTimelineTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        modifier = modifier.testTag("timeline_segmented_toggle"),
        shape = RoundedCornerShape(99.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, marketColors.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardTimelineTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val tag = when (tab) {
                    BoardTimelineTab.CURRENT -> "timeline_tab_current"
                    BoardTimelineTab.UPCOMING -> "timeline_tab_upcoming"
                    BoardTimelineTab.LISTED -> "timeline_tab_listed"
                }
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    border = if (isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                    } else {
                        null
                    },
                    shadowElevation = if (isSelected) 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .clickable { onSelectTab(tab) }
                        .testTag(tag),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                marketColors.mutedText
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketPulseBanner(
    lastSyncedLabel: String,
    isDataDelayed: Boolean = false,
    subscriptionSourcesStatusLine: String? = null,
    isRefreshingGmp: Boolean,
    onRefreshGmp: () -> Unit,
    onOpenRadar: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val bannerSurfaceShape = RoundedCornerShape(16.dp)
    Surface(
        shape = bannerSurfaceShape,
        color = if (glass.isDark) MaterialTheme.colorScheme.surface else Color.Transparent,
        border = if (glass.isDark) BorderStroke(1.dp, marketColors.border) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("market_pulse_banner"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glass.isDark) {
                        Modifier
                    } else {
                        Modifier.background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF18181B),
                                    Color(0xFF27272A),
                                ),
                            ),
                        )
                    },
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = "Last Sync",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (glass.isDark) marketColors.mutedText else Color(0xFFD4D4D8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.testTag("verified_filings_badge"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = "Multi-source verified filings",
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = "Verified",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF86EFAC),
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = lastSyncedLabel,
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.labelMedium.fontSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (glass.isDark) MaterialTheme.colorScheme.onSurface else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("last_sync_datetime"),
                    )
                    if (isDataDelayed) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Data may be delayed",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFBBF24),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("delayed_data_notice"),
                        )
                    }
                    if (!subscriptionSourcesStatusLine.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subscriptionSourcesStatusLine,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                            color = if (glass.isDark) marketColors.mutedText else Color(0xFFA1A1AA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("board_subscription_sources_status"),
                        )
                    }
                }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    onClick = onRefreshGmp,
                    shape = RoundedCornerShape(99.dp),
                    color = Color(0x2B34D399),
                    border = BorderStroke(1.dp, Color(0x596EE7B7)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_gmp_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isRefreshingGmp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = Color(0xFF6EE7B7),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh latest GMP",
                                tint = Color(0xFF6EE7B7),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = if (isRefreshingGmp) "Sync" else "GMP",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6EE7B7),
                        )
                    }
                }

                Surface(
                    onClick = onOpenRadar,
                    shape = RoundedCornerShape(99.dp),
                    color = if (glass.isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0x26FFFFFF),
                    border = BorderStroke(1.dp, if (glass.isDark) marketColors.border else Color(0x4094A3B8)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("open_radar_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(marketColors.liveAccent),
                        )
                        Text(
                            text = "Live Sub",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (glass.isDark) MaterialTheme.colorScheme.onSurface else Color.White,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open GMP and Live Subscription Radar",
                            tint = if (glass.isDark) marketColors.mutedText else Color(0xFFCBD5E1),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CompareIposQuickBanner(
    ipoCount: Int,
    onClick: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = glass.cardFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = glassBorderStroke(
            glass = glass,
            width = 1.dp,
            accentTint = null,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("board_compare_ipos_button"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Balance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Compare IPOs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (ipoCount >= 2) {
                            "Side-by-side GMP, Valuation & Subscription"
                        } else {
                            "Compare GMP, Dates & Subscription"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(99.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.8.dp, marketColors.border),
            ) {
                Row(
                    modifier = Modifier.padding(start = 11.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Compare",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Open Compare IPOs",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun IpoShimmerSkeletonCard(modifier: Modifier = Modifier) {
    val marketColors = LocalMarketColors.current
    val transition = rememberInfiniteTransition(label = "ipo_card_shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer_alpha",
    )
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, marketColors.border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(placeholderColor),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(width = 58.dp, height = 24.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(placeholderColor),
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 24.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(placeholderColor),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .height(13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(placeholderColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardSegmentFilterRow(
    selectedFilter: BoardFilter,
    onSelectFilter: (BoardFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassStyle.current
    val options = listOf(
        Triple(BoardFilter.ALL, "All India", "board_filter_all"),
        Triple(BoardFilter.MAINBOARD, "Mainboard", "board_filter_mainboard"),
        Triple(BoardFilter.SME, "SME", "board_filter_sme"),
    )
    Row(
        modifier = modifier
            .glassPanel(
                glass = glass,
                shape = RoundedCornerShape(14.dp),
                fillColor = glass.pillFill,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (filter, label, tag) ->
            val selected = selectedFilter == filter
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .clickable { onSelectFilter(filter) }
                    .padding(vertical = 7.dp, horizontal = 6.dp)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

