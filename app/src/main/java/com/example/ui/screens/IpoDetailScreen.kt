package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ads.AdBannerSlot
import com.example.data.model.BoardType
import com.example.data.model.IpoEntity
import com.example.data.preferences.GmpWatchRule
import com.example.ui.components.FinancialBarChart
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.ShareIpoBottomSheet
import com.example.ui.components.formatAllotmentOdds
import com.example.ui.components.formatCompactApps
import com.example.ui.components.formatCount
import com.example.ui.components.formatCrores
import com.example.ui.components.formatRupees
import com.example.ui.components.formatRupeesSigned
import com.example.ui.components.formatSubX
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import com.example.ui.viewmodel.DetailTab
import com.example.ui.viewmodel.SubscriptionSyncUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpoDetailScreen(
    ipo: IpoEntity?,
    selectedTab: DetailTab,
    onSelectTab: (DetailTab) -> Unit,
    onBack: () -> Unit,
    onUpdateLots: (Int) -> Unit,
    onAdImpression: () -> Unit,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    isRefreshingGmp: Boolean = isRefreshing,
    onRefreshGmp: () -> Unit = onRefresh,
    onTrackAllotment: (String) -> Unit = {},
    isDay3ReminderEnabled: Boolean = false,
    gmpWatchRule: GmpWatchRule? = null,
    onToggleDay3Reminder: (Boolean) -> Unit = {},
    onSetGmpWatchRule: (minPercent: Int?, maxPercent: Int?) -> Unit = { _, _ -> },
    onCompareIpo: (String) -> Unit = {},
    isLoading: Boolean = false,
    subscriptionSyncState: SubscriptionSyncUiState = SubscriptionSyncUiState(),
    onRefreshSubscription: () -> Unit = onRefresh,
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    var showShareSheet by remember { mutableStateOf(false) }

    val openAllotmentPortal: () -> Unit = {
        ipo?.let { target ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.registrarUrl))
            runCatching { context.startActivity(intent) }
        }
    }

    val openVerifyGmpWeb: () -> Unit = {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.investorgain.com/report/live-ipo-gmp/331/"),
        )
        runCatching { context.startActivity(intent) }
    }

    val openVerifySubscriptionWeb: () -> Unit = {
        val url = if (ipo?.boardType == BoardType.SME) {
            "https://www.chittorgarh.com/report/sme-ipo-subscription-status-live-bidding-bse-nse/22/"
        } else {
            "https://www.chittorgarh.com/report/ipo-subscription-status-live-bidding-data-bse-nse/21/"
        }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    val glass = LocalGlassStyle.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = ipo?.let { "${it.companyName} IPO" } ?: "IPO Report",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ipo != null) {
                            Text(
                                text = "${ipo.exchange} • ${ipo.sector}",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    GlassCircleIconButton(
                        onClick = onBack,
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Navigate back",
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .testTag("detail_back_button"),
                    )
                },
                actions = {
                    if (ipo != null) {
                        if (isRefreshing) {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("detail_refresh_button"),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            GlassCircleIconButton(
                                onClick = onRefresh,
                                icon = Icons.Rounded.Refresh,
                                contentDescription = "Refresh IPO details",
                                modifier = Modifier.testTag("detail_refresh_button"),
                            )
                        }

                        GlassCircleIconButton(
                            onClick = { showShareSheet = true },
                            icon = Icons.Outlined.Share,
                            contentDescription = "Share IPO summary",
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .testTag("detail_share_button"),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        bottomBar = {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                AdBannerSlot(onAdImpression = onAdImpression)
            }
        },
    ) { innerPadding ->
        if (ipo == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.testTag("detail_loading_indicator"))
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = glass.cardFill,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = glassBorderStroke(glass = glass, width = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp)
                            .testTag("detail_unavailable_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp),
                            )
                            Text(
                                text = "IPO Report Unavailable",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "This IPO entry may have been updated or removed during the latest verified sync.",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = onRefresh,
                                    modifier = Modifier.testTag("detail_unavailable_refresh_btn"),
                                ) {
                                    Text("Sync Latest")
                                }
                                OutlinedButton(
                                    onClick = onBack,
                                    modifier = Modifier.testTag("detail_unavailable_back_btn"),
                                ) {
                                    Text("Back to IPOs")
                                }
                            }
                        }
                    }
                }
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .testTag("detail_lazy_column"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Compact Hero Header (Logo, Board/Status, Offer Date, Offer Price & Lot Size)
                item(key = "hero_card") {
                    DetailHeroSummaryCard(
                        ipo = ipo,
                        isRefreshingGmp = isRefreshingGmp,
                        onRefreshGmp = onRefreshGmp,
                        onShareIpo = { showShareSheet = true },
                        onVerifyOnline = openVerifyGmpWeb,
                    )
                }

                // 1b. Actual Listing Price vs Pre-Listing GMP (shown for Listed IPOs)
                item(key = "listed_vs_gmp_top") {
                    ListedPerformanceVsGmpStrip(ipo = ipo)
                }

                // 2. IPO Schedule Dates right at the beginning (Open -> Close -> Allotment -> Listing)
                item(key = "ipo_schedule_top") {
                    IpoScheduleTimelineCard(ipo = ipo)
                }

                // 2b. Private Instant Allotment Alert Action Card
                item(key = "ipo_track_allotment_cta") {
                    Surface(
                        onClick = { onTrackAllotment(ipo.id) },
                        shape = MaterialTheme.shapes.large,
                        color = glass.cardFill,
                        border = glassBorderStroke(
                            glass = glass,
                            width = 1.dp,
                            accentTint = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail_track_allotment_btn"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Get Instant Allotment Alert",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Private on-device alert for 1 or multiple PAN profiles • Zero data sharing",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = marketColors.mutedText,
                                )
                            }
                            Text(
                                text = "Setup →",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // 3. Detail Section Filter Tabs
                item(key = "detail_tabs") {
                    DetailTabSwitcher(
                        selectedTab = selectedTab,
                        onSelectTab = onSelectTab,
                    )
                }

                when (selectedTab) {
                    DetailTab.ALL -> {
                        item(key = "smart_ipo_alerts") {
                            SmartIpoAlertsCard(
                                ipo = ipo,
                                isDay3ReminderEnabled = isDay3ReminderEnabled,
                                gmpWatchRule = gmpWatchRule,
                                onToggleDay3Reminder = onToggleDay3Reminder,
                                onSetGmpWatchRule = onSetGmpWatchRule,
                                onCompareIpo = { onCompareIpo(ipo.id) },
                            )
                        }
                        item(key = "live_subscription") {
                            LiveSubscriptionCard(
                                ipo = ipo,
                                isRefreshingSubscription = subscriptionSyncState.isRefreshing,
                                subscriptionStatusMessage = subscriptionSyncState.statusMessage,
                                subscriptionSyncLabel = subscriptionSyncState.lastSyncedLabel,
                                isAutoPollingActive = subscriptionSyncState.isAutoPollingActive,
                                onRefreshSubscription = onRefreshSubscription,
                                onVerifySubscriptionOnline = openVerifySubscriptionWeb,
                            )
                        }
                        item(key = "allotment_odds") {
                            AllotmentProbabilityCard(ipo = ipo)
                        }
                        item(key = "expected_premium") {
                            ExpectedPremiumCard(ipo = ipo)
                        }
                        item(key = "fresh_vs_ofs") {
                            FreshVsOfsVisualBarCard(ipo = ipo)
                        }
                        item(key = "ipo_details_breakdown") {
                            IpoDetailsBreakdownCard(ipo = ipo)
                        }
                        item(key = "category_lot_tiers") {
                            CategoryLotTiersCard(
                                ipo = ipo,
                                onApplyLots = onUpdateLots,
                            )
                        }
                        // Applications Reserved + Auto-hiding Middle Mobile Banner Ad (0dp gap when unfilled)
                        item(key = "applications_reserved_and_middle_ad") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ApplicationsReservedCard(ipo = ipo)
                                AdBannerSlot(
                                    onAdImpression = onAdImpression,
                                    topPaddingWhenLoadedDp = 14,
                                    roundedCardStyle = true,
                                    slotTag = "detail_middle_banner_slot",
                                    containerTag = "detail_middle_banner_container",
                                )
                            }
                        }
                        item(key = "lot_calculator") {
                            LotCalculatorCard(
                                ipo = ipo,
                                onUpdateLots = onUpdateLots,
                            )
                        }
                        item(key = "sub_daily") {
                            DailySubscriptionTableCard(ipo = ipo)
                        }
                        item(key = "app_breakup") {
                            ApplicationBreakupCard(ipo = ipo)
                        }
                        item(key = "val_ratios") {
                            ValuationRatiosCard(ipo = ipo)
                        }
                        item(key = "fin_chart") {
                            FinancialChartCard(ipo = ipo)
                        }
                        item(key = "fin_table") {
                            FinancialStatementTableCard(ipo = ipo)
                        }
                        item(key = "reservation_category") {
                            ReservationCategoryWiseCard(ipo = ipo)
                        }
                        item(key = "company_overview") {
                            CompanyOverviewCard(ipo = ipo)
                        }
                        item(key = "know_before_investing") {
                            KnowBeforeInvestingCard(ipo = ipo)
                        }
                        item(key = "registrar") {
                            RegistrarContactDetailsCard(
                                ipo = ipo,
                                onOpenAllotmentPortal = openAllotmentPortal,
                            )
                        }
                        item(key = "disclaimer") {
                            IpoDisclaimerCard()
                        }
                    }

                    DetailTab.OVERVIEW -> {
                        item(key = "smart_ipo_alerts") {
                            SmartIpoAlertsCard(
                                ipo = ipo,
                                isDay3ReminderEnabled = isDay3ReminderEnabled,
                                gmpWatchRule = gmpWatchRule,
                                onToggleDay3Reminder = onToggleDay3Reminder,
                                onSetGmpWatchRule = onSetGmpWatchRule,
                                onCompareIpo = { onCompareIpo(ipo.id) },
                            )
                        }
                        item(key = "expected_premium") {
                            ExpectedPremiumCard(ipo = ipo)
                        }
                        item(key = "fresh_vs_ofs") {
                            FreshVsOfsVisualBarCard(ipo = ipo)
                        }
                        item(key = "ipo_details_breakdown") {
                            IpoDetailsBreakdownCard(ipo = ipo)
                        }
                        item(key = "category_lot_tiers") {
                            CategoryLotTiersCard(
                                ipo = ipo,
                                onApplyLots = onUpdateLots,
                            )
                        }
                        item(key = "applications_reserved_and_middle_ad") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ApplicationsReservedCard(ipo = ipo)
                                AdBannerSlot(
                                    onAdImpression = onAdImpression,
                                    topPaddingWhenLoadedDp = 14,
                                    roundedCardStyle = true,
                                    slotTag = "detail_middle_banner_slot",
                                    containerTag = "detail_middle_banner_container",
                                )
                            }
                        }
                        item(key = "lot_calculator") {
                            LotCalculatorCard(
                                ipo = ipo,
                                onUpdateLots = onUpdateLots,
                            )
                        }
                        item(key = "company_overview") {
                            CompanyOverviewCard(ipo = ipo)
                        }
                        item(key = "registrar") {
                            RegistrarContactDetailsCard(
                                ipo = ipo,
                                onOpenAllotmentPortal = openAllotmentPortal,
                            )
                        }
                        item(key = "disclaimer") {
                            IpoDisclaimerCard()
                        }
                    }

                    DetailTab.SUBSCRIPTION -> {
                        item(key = "live_subscription") {
                            LiveSubscriptionCard(
                                ipo = ipo,
                                initiallyExpandedBars = true,
                                isRefreshingSubscription = subscriptionSyncState.isRefreshing,
                                subscriptionStatusMessage = subscriptionSyncState.statusMessage,
                                subscriptionSyncLabel = subscriptionSyncState.lastSyncedLabel,
                                isAutoPollingActive = subscriptionSyncState.isAutoPollingActive,
                                onRefreshSubscription = onRefreshSubscription,
                                onVerifySubscriptionOnline = openVerifySubscriptionWeb,
                            )
                        }
                        item(key = "allotment_odds") {
                            AllotmentProbabilityCard(ipo = ipo)
                        }
                        item(key = "app_breakup_and_middle_ad") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ApplicationBreakupCard(ipo = ipo)
                                AdBannerSlot(
                                    onAdImpression = onAdImpression,
                                    topPaddingWhenLoadedDp = 14,
                                    roundedCardStyle = true,
                                    slotTag = "detail_middle_banner_slot",
                                    containerTag = "detail_middle_banner_container",
                                )
                            }
                        }
                        item(key = "sub_daily") {
                            DailySubscriptionTableCard(ipo = ipo)
                        }
                        item(key = "reservation_category") {
                            ReservationCategoryWiseCard(ipo = ipo)
                        }
                    }

                    DetailTab.FINANCIALS -> {
                        item(key = "fin_chart") {
                            FinancialChartCard(ipo = ipo)
                        }
                        item(key = "fin_table_and_middle_ad") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                FinancialStatementTableCard(ipo = ipo)
                                AdBannerSlot(
                                    onAdImpression = onAdImpression,
                                    topPaddingWhenLoadedDp = 14,
                                    roundedCardStyle = true,
                                    slotTag = "detail_middle_banner_slot",
                                    containerTag = "detail_middle_banner_container",
                                )
                            }
                        }
                        item(key = "val_ratios") {
                            ValuationRatiosCard(ipo = ipo)
                        }
                        item(key = "know_before_investing") {
                            KnowBeforeInvestingCard(ipo = ipo)
                        }
                        item(key = "disclaimer") {
                            IpoDisclaimerCard()
                        }
                    }
                }
            }
        }
    }

    if (showShareSheet && ipo != null) {
        ShareIpoBottomSheet(
            ipo = ipo,
            onDismiss = { showShareSheet = false },
        )
    }
}

@Composable
private fun DetailTabSwitcher(
    selectedTab: DetailTab,
    onSelectTab: (DetailTab) -> Unit,
) {
    val glass = LocalGlassStyle.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail_tab_switcher"),
        shape = RoundedCornerShape(99.dp),
        color = glass.pillFill,
        border = glassBorderStroke(glass = glass, width = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DetailTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val selectedBg = if (glass.isAmoled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    glass.elevatedFill
                }
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = if (selected) selectedBg else Color.Transparent,
                    border = if (selected) {
                        if (glass.isAmoled) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
                        } else {
                            glassBorderStroke(glass = glass, width = 0.85.dp)
                        }
                    } else {
                        null
                    },
                    shadowElevation = if (selected && !glass.isDark && !glass.isGlass) 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(if (tab == DetailTab.ALL) 0.72f else if (tab == DetailTab.SUBSCRIPTION) 1.24f else 1.02f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .clickable { onSelectTab(tab) }
                        .testTag("detail_tab_${tab.name.lowercase()}"),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
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
    }
}

@Composable
private fun CategoryLotTiersCard(
    ipo: IpoEntity,
    onApplyLots: (Int) -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val tiers = ipo.categoryLotTiers
    val isSme = ipo.boardType == BoardType.SME

    SectionCard(title = "Retail, S-HNI & Big HNI Lot Size") {
        Text(
            text = "Minimum & maximum application lots, shares, and total amount at upper cut-off ₹${ipo.priceHigh}. Tap any tier to load into the calculator.",
            style = MaterialTheme.typography.bodySmall,
            color = marketColors.mutedText,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ParticipantBucketChip(
                title = "Retail (RII)",
                quota = if (isSme) "35%–50%" else "~35% Quota",
                cap = "Up to ₹2L",
                modifier = Modifier.weight(1f),
            )
            ParticipantBucketChip(
                title = "S-HNI",
                quota = "1/3rd NII",
                cap = "₹2L – ₹10L",
                modifier = Modifier.weight(1f),
            )
            ParticipantBucketChip(
                title = "Big HNI",
                quota = "2/3rd NII",
                cap = "> ₹10 Lakh",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category_lot_table"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderCell("Category", Modifier.weight(1.45f))
            TableHeaderCell("Lots", Modifier.weight(0.65f), TextAlign.End)
            TableHeaderCell("Shares", Modifier.weight(0.9f), TextAlign.End)
            TableHeaderCell("Amount", Modifier.weight(1.2f), TextAlign.End)
        }
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = marketColors.border)

        tiers.forEachIndexed { idx, tier ->
            val isSelected = ipo.userLots == tier.lots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onApplyLots(tier.lots) }
                    .testTag("lot_tier_${tier.id}")
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1.45f)) {
                    Text(
                        text = tier.categoryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = tier.limitHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                    )
                }
                Text(
                    text = "${tier.lots}",
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.65f),
                )
                Text(
                    text = formatCount(tier.shares),
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.9f),
                )
                Text(
                    text = formatRupees(tier.totalAmount),
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    textAlign = TextAlign.End,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.2f),
                )
            }
            if (idx < tiers.lastIndex) {
                HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(top = 2.dp),
                )
                Text(
                    text = if (isSme) {
                        "Retail participants invest up to ₹2L (1 lot = ${ipo.lotSize} shares, ${formatRupees(ipo.minInvestment)}). Bids between ₹2L–₹10L qualify under S-HNI (${ipo.sHniMinLots}–${ipo.sHniMaxLots} lots) and above ₹10L qualify under Big HNI (${ipo.bHniMinLots}+ lots)."
                    } else {
                        "Retail participants can apply for 1 to ${ipo.retailMaxLots} lots (up to ₹2L) with cut-off & lottery allotment. S-HNI covers ${ipo.sHniMinLots}–${ipo.sHniMaxLots} lots (₹2L–₹10L) and Big HNI starts at ${ipo.bHniMinLots} lots (> ₹10L)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                )
            }
        }
    }
}

@Composable
private fun ParticipantBucketChip(
    title: String,
    quota: String,
    cap: String,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = cap,
                style = MonoNumericStyle.copy(
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = quota,
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LotCalculatorCard(
    ipo: IpoEntity,
    onUpdateLots: (Int) -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val totalShares = ipo.lotSize * ipo.userLots
    val totalCapital = ipo.minInvestment * ipo.userLots
    val estProfit = ipo.expectedLotProfit * ipo.userLots
    val isBullish = estProfit >= 0
    val activeCategory = when {
        totalCapital <= 200_000 -> "Retail (RII)"
        totalCapital <= 1_000_000 -> "Small HNI (S-HNI)"
        else -> "Big HNI (B-HNI)"
    }
    val presets = listOf(
        "Retail Min" to 1,
        "Retail Max" to ipo.retailMaxLots,
        "S-HNI Min" to ipo.sHniMinLots,
        "Big HNI" to ipo.bHniMinLots,
    )

    SectionCard(title = "Lot & Est. GMP Profit Calculator") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Application Lots",
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = activeCategory,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${ipo.userLots} Lot${if (ipo.userLots > 1) "s" else ""} (${formatCount(totalShares)} shares)",
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onUpdateLots(ipo.userLots - 1) },
                    enabled = ipo.userLots > 1,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lot_decrement_button"),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Decrease lots")
                }
                OutlinedButton(
                    onClick = { onUpdateLots(ipo.userLots + 1) },
                    enabled = ipo.userLots < 200,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lot_increment_button"),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Increase lots")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { (label, presetLots) ->
                val selected = ipo.userLots == presetLots
                FilterChip(
                    selected = selected,
                    onClick = { onUpdateLots(presetLots) },
                    modifier = Modifier.testTag("lot_preset_${label.lowercase().replace(" ", "_")}"),
                    label = {
                        Text(
                            text = "$label ($presetLots)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = marketColors.border,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = marketColors.border, thickness = 0.8.dp)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Capital Required",
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                )
                Text(
                    text = formatRupees(totalCapital),
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.titleLarge.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Est. Listing Gain",
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                )
                Text(
                    text = formatRupeesSigned(estProfit),
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.titleLarge.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (isBullish) marketColors.bullish else marketColors.bearish,
                )
            }
        }
    }
}

@Composable
private fun CompanyOverviewCard(ipo: IpoEntity) {
    SectionCard(title = "Business Overview & Objects") {
        Text(
            text = ipo.overview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (ipo.objectsOfIssue.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "ISSUE OBJECTIVE(S)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            ipo.objectsOfIssue.forEachIndexed { idx, obj ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${idx + 1}.",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(text = obj, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ApplicationBreakupCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val rows = ipo.applicationWiseBreakup

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("application_breakup_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = glass.cardFill,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = glassBorderStroke(glass = glass, width = 1.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (glass.isDark || glass.isGlass) 0.dp else 1.5.dp,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Application-Wise Breakup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = if (ipo.boardType == BoardType.SME) "SME Lots" else "SEBI Quota",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Estimated reserved applications, received bids, and allotment probability across Retail, S-HNI, and Big HNI categories.",
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (rows.isEmpty()) {
                Text(
                    text = "Application-wise breakup will populate once total issue size and final price band are published.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                )
                return@Column
            }

            val totalReserved = rows.sumOf { it.reservedApplications }
            val totalReceived = rows.sumOf { it.receivedApplications }
            val retailRow = rows.firstOrNull { it.id == "retail" }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BreakupMetricBox(
                    label = "Reserved Apps",
                    value = formatCompactApps(totalReserved),
                    modifier = Modifier.weight(1f),
                )
                BreakupMetricBox(
                    label = "Total Applied",
                    value = if (totalReceived > 0L) formatCompactApps(totalReceived) else "Pre-Open",
                    highlighted = totalReceived > 0L,
                    modifier = Modifier.weight(1f),
                )
                BreakupMetricBox(
                    label = "Retail Odds",
                    value = retailRow?.let { formatAllotmentOdds(it.subscriptionX) } ?: "—",
                    highlighted = (retailRow?.subscriptionX ?: 0.0) > 0.0,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableHeaderCell("Category", Modifier.weight(1.25f))
                TableHeaderCell("Reserved", Modifier.weight(0.95f), TextAlign.End)
                TableHeaderCell("Applied", Modifier.weight(0.95f), TextAlign.End)
                TableHeaderCell("Chance", Modifier.weight(0.95f), TextAlign.End)
            }
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = marketColors.border)

            rows.forEachIndexed { idx, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("breakup_row_${row.id}")
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1.25f)) {
                        Text(
                            text = row.categoryLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = row.quotaLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = marketColors.mutedText,
                        )
                    }
                    Text(
                        text = formatCompactApps(row.reservedApplications),
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.95f),
                    )
                    Text(
                        text = if (row.receivedApplications > 0L) formatCompactApps(row.receivedApplications) else "—",
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        textAlign = TextAlign.End,
                        color = if (row.receivedApplications > 0L) MaterialTheme.colorScheme.primary else marketColors.mutedText,
                        modifier = Modifier.weight(0.95f),
                    )
                    Text(
                        text = formatAllotmentOdds(row.subscriptionX),
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        textAlign = TextAlign.End,
                        color = if (row.subscriptionX in 0.01..1.0) marketColors.bullish else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.95f),
                    )
                }
                if (idx < rows.lastIndex) {
                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)
                }
            }

            HorizontalDivider(color = marketColors.border)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Total (RII + NII)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.25f),
                )
                Text(
                    text = formatCompactApps(totalReserved),
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.95f),
                )
                Text(
                    text = if (totalReceived > 0L) formatCompactApps(totalReceived) else "—",
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(0.95f),
                )
                Text(
                    text = formatSubX(ipo.totalSubX),
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    textAlign = TextAlign.End,
                    color = marketColors.bullish,
                    modifier = Modifier.weight(0.95f),
                )
            }
        }
    }
}

@Composable
private fun BreakupMetricBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MonoNumericStyle.copy(
                    fontSize = MaterialTheme.typography.titleSmall.fontSize,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DailySubscriptionTableCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    SectionCard(title = "Subscription Details") {
        Text(
            text = "Updated at End of the Day • Day-by-Day Demand Progression",
            style = MaterialTheme.typography.labelSmall,
            color = marketColors.mutedText,
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (ipo.dailySubscriptions.isEmpty()) {
            Text(
                text = "Bidding opens on ${ipo.openDate}. Live QIB, NII, and Retail demand will appear here once exchange bidding begins.",
                style = MaterialTheme.typography.bodyMedium,
                color = marketColors.mutedText,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TableHeaderCell("As on", Modifier.weight(1.1f))
                TableHeaderCell("QIB", Modifier.weight(0.95f), TextAlign.End)
                TableHeaderCell("NII", Modifier.weight(0.95f), TextAlign.End)
                TableHeaderCell("Retail", Modifier.weight(0.95f), TextAlign.End)
                TableHeaderCell("Total", Modifier.weight(1f), TextAlign.End)
            }
            Spacer(modifier = Modifier.height(4.dp))
            ipo.dailySubscriptions.forEachIndexed { idx, day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = day.dayLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.1f),
                    )
                    TableValueCell(formatSubX(day.qibX), Modifier.weight(0.95f))
                    TableValueCell(formatSubX(day.niiX), Modifier.weight(0.95f))
                    TableValueCell(formatSubX(day.retailX), Modifier.weight(0.95f))
                    TableValueCell(formatSubX(day.totalX), Modifier.weight(1f), highlight = true)
                }
                if (idx < ipo.dailySubscriptions.lastIndex) {
                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)
                }
            }
        }
    }
}

@Composable
private fun FinancialChartCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    SectionCard(title = "3-Year Financial Performance (₹ Cr)") {
        if (ipo.financialYears.isEmpty()) {
            Text(
                text = "Restated RHP 3-year financial performance is awaiting DRHP breakdown for this live IPO.",
                style = MaterialTheme.typography.bodyMedium,
                color = marketColors.mutedText,
            )
        } else {
            FinancialBarChart(years = ipo.financialYears)
        }
    }
}

@Composable
private fun FinancialStatementTableCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    val years = ipo.financialYears
    SectionCard(title = "Restated Financial Report (₹ in Crores)") {
        if (years.isEmpty()) {
            Text(
                text = "Financial statement line items will populate once the Red Herring Prospectus (RHP) is published on SEBI / exchanges.",
                style = MaterialTheme.typography.bodyMedium,
                color = marketColors.mutedText,
            )
            return@SectionCard
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TableHeaderCell("Particulars", Modifier.weight(1.4f))
            years.forEach { yr ->
                TableHeaderCell(yr.fiscalYear, Modifier.weight(1f), TextAlign.End)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        val rows = listOf(
            "Assets" to years.map { it.assetsCr },
            "Revenue" to years.map { it.revenueCr },
            "EBITDA" to years.map { it.ebitdaCr },
            "Profit After Tax" to years.map { it.patCr },
            "Net Worth" to years.map { it.netWorthCr },
            "Total Borrowing" to years.map { it.totalDebtCr },
        )

        rows.forEachIndexed { idx, (label, values) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (label == "Profit After Tax" || label == "Revenue") {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    },
                    modifier = Modifier.weight(1.4f),
                )
                values.forEach { value ->
                    Text(
                        text = String.format(Locale.US, "%.1f", value),
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        textAlign = TextAlign.End,
                        color = if (value < 0) marketColors.bearish else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (idx < rows.lastIndex) {
                HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)
            }
        }
    }
}

@Composable
private fun ValuationRatiosCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    val v = ipo.valuation
    if (v.marketCapCr <= 0.0 && v.peRatio <= 0.0 && v.eps == 0.0) {
        SectionCard(title = "Key Valuation Ratios") {
            Text(
                text = "Post-issue EPS, P/E, and RoNW multiples will update after price-band & RHP ratio verification.",
                style = MaterialTheme.typography.bodyMedium,
                color = marketColors.mutedText,
            )
        }
        return
    }
    val items = listOf(
        "EPS (Post-Issue)" to "₹${String.format(Locale.US, "%.2f", v.eps)}",
        "P/E Ratio" to "${String.format(Locale.US, "%.2f", v.peRatio)}x",
        "ROE" to "${String.format(Locale.US, "%.1f", v.roePercent)}%",
        "RoNW" to "${String.format(Locale.US, "%.1f", v.ronwPercent)}%",
        "Debt / Equity" to String.format(Locale.US, "%.2f", v.debtToEquity),
        "NAV / Share" to "₹${String.format(Locale.US, "%.1f", v.navPerShare)}",
        "Price / Book" to "${String.format(Locale.US, "%.2f", v.priceToBook)}x",
        "Implied M-Cap" to formatCrores(v.marketCapCr),
    )

    SectionCard(title = "Key Valuation Ratios") {
        items.chunked(2).forEachIndexed { rowIdx, pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEachIndexed { colIdx, (label, value) ->
                    if (colIdx == 1) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(36.dp)
                                .background(marketColors.border.copy(alpha = 0.7f)),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = marketColors.mutedText,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = value,
                            style = MonoNumericStyle.copy(
                                fontSize = MaterialTheme.typography.titleSmall.fontSize,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (rowIdx < items.chunked(2).lastIndex) {
                HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlassStyle.current
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = glass.cardFill,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = glassBorderStroke(glass = glass, width = 1.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (glass.isDark || glass.isGlass) 0.dp else 1.5.dp,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun TableHeaderCell(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    val marketColors = LocalMarketColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = marketColors.mutedText,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun TableValueCell(
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Text(
        text = text,
        style = MonoNumericStyle.copy(
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
        ),
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}
