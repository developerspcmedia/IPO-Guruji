package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ads.NativeAdPlacement
import com.example.ads.NativeAdSlot
import com.example.data.model.UsIpoEntity
import com.example.data.model.UsIpoStatus
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.glassPanel
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import com.example.ui.viewmodel.UsIpoUiState

@Composable
fun UsIpoBoardScreen(
    uiState: UsIpoUiState,
    onEnsureLoaded: () -> Unit,
    onSelectStatus: (UsIpoStatus) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenMenu: () -> Unit,
    onUsIpoClick: (String) -> Unit,
    usaNewsAlertsEnabled: Boolean = true,
    onToggleUsaNewsAlerts: (Boolean) -> Unit = {},
    onSendTestUsaNewsAlert: () -> Unit = {},
    onAdImpression: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        onEnsureLoaded()
    }

    var showUsaAlertsDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSearchActive) {
        onToggleSearch(false)
    }

    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current

    val spinTransition = rememberInfiniteTransition(label = "us_sync_spin")
    val spinDegrees by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "us_sync_rotation",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("us_ipo_board_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top Header Row
            item(key = "us_top_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        GlassCircleIconButton(
                            onClick = onOpenMenu,
                            icon = Icons.Rounded.Menu,
                            contentDescription = "Open navigation menu",
                            modifier = Modifier.testTag("us_board_menu_button"),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "USA IPOs",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "USD",
                                        style = MonoNumericStyle.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                text = "NASDAQ • NYSE • SEC EDGAR",
                                style = MaterialTheme.typography.labelSmall,
                                color = marketColors.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            GlassCircleIconButton(
                                onClick = { showUsaAlertsDialog = true },
                                icon = if (usaNewsAlertsEnabled) {
                                    Icons.Rounded.NotificationsActive
                                } else {
                                    Icons.Rounded.NotificationsOff
                                },
                                contentDescription = "Configure USA IPO news alerts",
                                active = usaNewsAlertsEnabled,
                                modifier = Modifier.testTag("us_board_news_alert_button"),
                            )
                            if (usaNewsAlertsEnabled) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = 6.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(marketColors.bullish),
                                )
                            }
                        }
                        GlassCircleIconButton(
                            onClick = { onToggleSearch(!uiState.isSearchActive) },
                            icon = if (uiState.isSearchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = if (uiState.isSearchActive) "Close US IPO search" else "Search USA IPOs",
                            active = uiState.isSearchActive,
                            modifier = Modifier.testTag("us_board_search_button"),
                        )
                        GlassCircleIconButton(
                            onClick = onRefresh,
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "Refresh USA IPO feed",
                            active = uiState.isRefreshing,
                            modifier = Modifier
                                .testTag("us_board_refresh_button")
                                .then(if (uiState.isRefreshing) Modifier.rotate(spinDegrees) else Modifier),
                        )
                    }
                }
            }

            // Expandable Search Field
            item(key = "us_search_bar") {
                AnimatedVisibility(visible = uiState.isSearchActive) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("us_board_search_input"),
                        placeholder = {
                            Text(
                                text = "Search ticker, company, or exchange...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onSearchQueryChange("") },
                                    modifier = Modifier.testTag("us_board_search_clear"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear search query",
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = glass.cardFill,
                            unfocusedContainerColor = glass.cardFill,
                        ),
                    )
                }
            }

            // US Market Pulse Summary Card
            item(key = "us_pulse_card") {
                UsMarketPulseCard(
                    upcomingCount = uiState.upcomingCount,
                    pricedCount = uiState.pricedCount,
                    filedCount = uiState.filedCount,
                    lastSyncedLabel = uiState.lastSyncedLabel,
                    sourcesLabel = uiState.activeSourcesLabel,
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRefresh,
                )
            }

            // 3-Segment Status Pill Bar (Upcoming / Priced / Filed)
            item(key = "us_status_tabs") {
                UsStatusSegmentedBar(
                    selectedStatus = uiState.selectedStatus,
                    upcomingCount = uiState.upcomingCount,
                    pricedCount = uiState.pricedCount,
                    filedCount = uiState.filedCount,
                    onSelectStatus = onSelectStatus,
                )
            }

            // Non-blocking error recovery banner
            if (!uiState.errorMessage.isNullOrBlank()) {
                item(key = "us_error_banner") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(
                                glass = glass,
                                shape = RoundedCornerShape(16.dp),
                                fillColor = marketColors.bearishContainer,
                                accentTint = marketColors.bearish,
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("us_board_error_banner"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = "Sync warning",
                            tint = marketColors.bearish,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = onRefresh,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("us_board_retry_button"),
                        ) {
                            Text(text = "Retry", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Initial Loading State
            if (uiState.isRefreshing && uiState.allUsIpos.isEmpty()) {
                item(key = "us_initial_loading") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(glass = glass, shape = RoundedCornerShape(22.dp))
                            .padding(horizontal = 24.dp, vertical = 40.dp)
                            .testTag("us_board_loading_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Fetching live USA IPO calendar...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Pulling upcoming, priced, and SEC Form S-1/F-1 filings from Nasdaq & EDGAR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = marketColors.mutedText,
                        )
                    }
                }
            } else if (uiState.filteredUsIpos.isEmpty()) {
                item(key = "us_empty_state") {
                    UsEmptyCategoryCard(
                        selectedStatus = uiState.selectedStatus,
                        hasSearchQuery = uiState.searchQuery.isNotBlank(),
                        pricedCount = uiState.pricedCount,
                        filedCount = uiState.filedCount,
                        upcomingCount = uiState.upcomingCount,
                        onClearSearch = { onSearchQueryChange("") },
                        onSelectStatus = onSelectStatus,
                        onRefresh = onRefresh,
                    )
                }
            } else {
                val nativeInsertIndex = if (uiState.filteredUsIpos.size >= 2) 1 else 0
                itemsIndexed(
                    items = uiState.filteredUsIpos,
                    key = { _, item -> item.dealId },
                ) { index, item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        UsIpoCard(
                            ipo = item,
                            onClick = { onUsIpoClick(item.dealId) },
                        )
                        if (index == nativeInsertIndex) {
                            NativeAdSlot(
                                placement = NativeAdPlacement.SECONDARY,
                                topPaddingDp = 12,
                                onAdImpression = onAdImpression,
                                slotTag = "us_board_native_ad",
                            )
                        }
                    }
                }
            }

            item(key = "us_bottom_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showUsaAlertsDialog) {
        var sentSample by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showUsaAlertsDialog = false },
            title = {
                Text(
                    text = "USA IPO News Alerts",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Get notified when fresh NASDAQ, NYSE, or SEC S-1/F-1 IPO news headlines arrive in the News tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (usaNewsAlertsEnabled) "Alerts Active" else "Alerts Paused",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Switch(
                            checked = usaNewsAlertsEnabled,
                            onCheckedChange = onToggleUsaNewsAlerts,
                            modifier = Modifier.testTag("us_board_news_alert_switch"),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onSendTestUsaNewsAlert()
                            sentSample = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("us_board_news_alert_test_button"),
                    ) {
                        Text(if (sentSample) "USA Test Alert Sent" else "Send Test USA IPO Alert")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUsaAlertsDialog = false }) {
                    Text("Done")
                }
            },
        )
    }
}

@Composable
private fun UsMarketPulseCard(
    upcomingCount: Int,
    pricedCount: Int,
    filedCount: Int,
    lastSyncedLabel: String,
    sourcesLabel: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(
                glass = glass,
                shape = RoundedCornerShape(22.dp),
                accentTint = MaterialTheme.colorScheme.primary,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = "Verified public exchange feeds",
                    tint = marketColors.bullish,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = sourcesLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(glass.pillFill)
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "Sync status",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = when {
                        isRefreshing -> "Syncing..."
                        lastSyncedLabel.isNotBlank() -> lastSyncedLabel
                        else -> "Live Feed"
                    },
                    style = MonoNumericStyle.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UsPulseStatTile(
                label = "Upcoming",
                count = upcomingCount,
                accentColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.weight(1f),
            )
            UsPulseStatTile(
                label = "Priced",
                count = pricedCount,
                accentColor = marketColors.bullish,
                containerColor = marketColors.bullishContainer,
                modifier = Modifier.weight(1f),
            )
            UsPulseStatTile(
                label = "Filed S-1",
                count = filedCount,
                accentColor = marketColors.smeBadge,
                containerColor = marketColors.smeContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UsPulseStatTile(
    label: String,
    count: Int,
    accentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = count.toString(),
            style = MonoNumericStyle.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = accentColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsStatusSegmentedBar(
    selectedStatus: UsIpoStatus,
    upcomingCount: Int,
    pricedCount: Int,
    filedCount: Int,
    onSelectStatus: (UsIpoStatus) -> Unit,
) {
    val glass = LocalGlassStyle.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(
                glass = glass,
                shape = RoundedCornerShape(18.dp),
                fillColor = glass.pillFill,
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val entries = listOf(
            Triple(UsIpoStatus.UPCOMING, upcomingCount, "us_status_tab_upcoming"),
            Triple(UsIpoStatus.PRICED, pricedCount, "us_status_tab_priced"),
            Triple(UsIpoStatus.FILED, filedCount, "us_status_tab_filed"),
        )
        entries.forEach { (status, count, tag) ->
            val selected = selectedStatus == status
            Box(
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable { onSelectStatus(status) }
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "us_tab_label_${status.name}",
                ) { isSel ->
                    Text(
                        text = "${status.label} ($count)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSel) {
                            MaterialTheme.colorScheme.onPrimary
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

@Composable
private fun UsIpoCard(
    ipo: UsIpoEntity,
    onClick: () -> Unit,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current

    val (statusColor, statusBg) = when (ipo.status) {
        UsIpoStatus.UPCOMING -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        UsIpoStatus.PRICED -> marketColors.bullish to marketColors.bullishContainer
        UsIpoStatus.FILED -> marketColors.smeBadge to marketColors.smeContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(glass = glass, shape = RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag("us_ipo_card_${ipo.dealId}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header: Ticker badge + Company Name + Status Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusBg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ipo.displayTicker,
                    style = MonoNumericStyle.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = statusColor,
                    maxLines = 1,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = ipo.companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(statusBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = ipo.status.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = statusColor,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(glass.pillFill)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = ipo.exchange,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (ipo.secFormType.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = ipo.secFormType,
                                style = MonoNumericStyle.copy(
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open US IPO details",
                tint = marketColors.mutedText,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(20.dp),
            )
        }

        HorizontalDivider(color = glass.hairline, thickness = 0.8.dp)

        // 3-Column USD Financial Metric Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UsMetricColumn(
                label = "Price (USD)",
                value = ipo.displayPrice,
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            UsMetricColumn(
                label = "Deal Size",
                value = ipo.compactDealSize,
                valueColor = if (ipo.dealSizeNumericUsd > 0.0) marketColors.bullish else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            UsMetricColumn(
                label = ipo.shortDateLabel,
                value = ipo.displayEventDate,
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        // Subtle Footer: Shares Offered + S-1 Link Hint
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Shares: ${ipo.displayShares}",
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Deal & S-1",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun UsMetricColumn(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalMarketColors.current.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MonoNumericStyle.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsEmptyCategoryCard(
    selectedStatus: UsIpoStatus,
    hasSearchQuery: Boolean,
    pricedCount: Int,
    filedCount: Int,
    upcomingCount: Int,
    onClearSearch: () -> Unit,
    onSelectStatus: (UsIpoStatus) -> Unit,
    onRefresh: () -> Unit,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(glass = glass, shape = RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("us_board_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = if (hasSearchQuery) {
                "No matching ${selectedStatus.label} USA IPOs"
            } else {
                "No ${selectedStatus.label} USA IPOs scheduled right now"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (hasSearchQuery) {
                "Try a different ticker symbol, company name, or switch status tabs."
            } else {
                "US IPO pricing calendars update weekly as underwriters set dates on Nasdaq and NYSE."
            },
            style = MaterialTheme.typography.bodySmall,
            color = marketColors.mutedText,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasSearchQuery) {
                Button(onClick = onClearSearch) {
                    Text("Clear Search")
                }
            } else {
                if (selectedStatus != UsIpoStatus.PRICED && pricedCount > 0) {
                    Button(onClick = { onSelectStatus(UsIpoStatus.PRICED) }) {
                        Text("View Priced ($pricedCount)")
                    }
                } else if (selectedStatus != UsIpoStatus.FILED && filedCount > 0) {
                    Button(onClick = { onSelectStatus(UsIpoStatus.FILED) }) {
                        Text("View Filed ($filedCount)")
                    }
                } else if (selectedStatus != UsIpoStatus.UPCOMING && upcomingCount > 0) {
                    Button(onClick = { onSelectStatus(UsIpoStatus.UPCOMING) }) {
                        Text("View Upcoming ($upcomingCount)")
                    }
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh Feed")
                }
            }
        }
    }
}
