package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.IpoNewsArticle
import com.example.data.model.IpoNewsCatalog
import com.example.data.model.NewsCategory
import com.example.data.preferences.NewsNotificationPreferencesSnapshot
import com.example.ui.components.CompanyBannerImage
import com.example.ui.components.CompanyLogo
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.glassBorderStroke
import com.example.ui.components.glassPanel
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IpoNewsScreen(
    onOpenIpoDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    articles: List<IpoNewsArticle> = IpoNewsCatalog.getLatestNews(),
    isRefreshing: Boolean = false,
    lastSyncedLabel: String = "Synced today",
    sourcesSummary: String = "ET IPO • LiveMint • Google News",
    onRefresh: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    notifyNewsAlertsEnabled: Boolean = true,
    newsAlertCategories: Set<NewsCategory> = NewsNotificationPreferencesSnapshot.DEFAULT_NEWS_ALERT_CATEGORIES,
    hasNotificationPermission: Boolean = true,
    highlightedArticleId: String? = null,
    onConsumeHighlightedArticle: () -> Unit = {},
    onToggleNewsAlerts: (Boolean) -> Unit = {},
    onToggleNewsAlertCategory: (NewsCategory) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onSendTestNewsAlert: () -> Unit = {},
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    var selectedCategory by remember { mutableStateOf(NewsCategory.ALL) }
    var activeArticle by remember { mutableStateOf<IpoNewsArticle?>(null) }
    var showNewsAlertsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(highlightedArticleId, articles) {
        val targetId = highlightedArticleId
        if (!targetId.isNullOrBlank()) {
            val matched = articles.firstOrNull { it.id == targetId }
            if (matched != null) {
                activeArticle = matched
            }
            onConsumeHighlightedArticle()
        }
    }

    val filteredArticles = remember(selectedCategory, articles) {
        if (selectedCategory == NewsCategory.ALL) {
            articles
        } else {
            articles.filter { it.category == selectedCategory }
        }
    }

    val featuredArticle = filteredArticles.firstOrNull { it.isFeatured }
        ?: filteredArticles.firstOrNull()
    val listArticles = filteredArticles.filter { it.id != featuredArticle?.id }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            val glass = LocalGlassStyle.current
            // Minimalist Liquid Glass News Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircleIconButton(
                    onClick = onOpenMenu,
                    icon = Icons.Rounded.Menu,
                    contentDescription = "Open navigation menu",
                    modifier = Modifier.testTag("news_menu_drawer_button"),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "IPO News",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "$sourcesSummary • $lastSyncedLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        GlassCircleIconButton(
                            onClick = { showNewsAlertsSheet = true },
                            icon = if (notifyNewsAlertsEnabled) {
                                Icons.Rounded.NotificationsActive
                            } else {
                                Icons.Rounded.NotificationsOff
                            },
                            contentDescription = "Configure IPO News alerts",
                            tint = if (notifyNewsAlertsEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                marketColors.mutedText
                            },
                            modifier = Modifier.testTag("news_alerts_button"),
                        )
                        if (notifyNewsAlertsEnabled) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-6).dp, y = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(marketColors.bullish),
                            )
                        }
                    }

                    if (isRefreshing) {
                        IconButton(
                            onClick = {
                                selectedCategory = NewsCategory.ALL
                                onRefresh()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("news_reset_button"),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        GlassCircleIconButton(
                            onClick = {
                                selectedCategory = NewsCategory.ALL
                                onRefresh()
                            },
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "Refresh latest IPO news",
                            modifier = Modifier.testTag("news_reset_button"),
                        )
                    }
                }
            }

            // Frosted Glass Category Filter Pills
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(NewsCategory.entries) { category ->
                    val selected = selectedCategory == category
                    FilterChip(
                        selected = selected,
                        onClick = { selectedCategory = category },
                        modifier = Modifier.testTag("news_filter_${category.name.lowercase()}"),
                        shape = RoundedCornerShape(99.dp),
                        label = {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = glass.pillFill,
                            selectedContainerColor = glass.elevatedFill,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = glass.specularBorderBottom,
                            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        ),
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("news_lazy_column"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "news_alerts_status_banner") {
                        NewsAlertsStatusBanner(
                            enabled = notifyNewsAlertsEnabled,
                            topicCount = newsAlertCategories.size,
                            needsPermission = !hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                            onClick = { showNewsAlertsSheet = true },
                        )
                    }

                    if (featuredArticle != null) {
                        item(key = "featured_${featuredArticle.id}") {
                            FeaturedNewsCard(
                                article = featuredArticle,
                                onClick = { activeArticle = featuredArticle },
                            )
                        }
                    }

                    items(
                        items = listArticles,
                        key = { it.id },
                    ) { article ->
                        NewsArticleCard(
                            article = article,
                            onClick = { activeArticle = article },
                            onOpenLinkedIpo = { ipoId -> onOpenIpoDetail(ipoId) },
                        )
                    }
                }
            }
        }
    }

    activeArticle?.let { article ->
        val glass = LocalGlassStyle.current
        ModalBottomSheet(
            onDismissRequest = { activeArticle = null },
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            containerColor = glass.sheetFill,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NewsCategoryBadge(category = article.category)
                    Text(
                        text = "${article.source} • ${article.publishedAgo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompanyLogo(
                        logoUrl = article.logoUrl,
                        companyName = article.linkedIpoName ?: article.source,
                        shortCode = article.linkedShortCode,
                        boardType = article.linkedBoardType,
                        size = 44.dp,
                    )
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (article.imageUrl.isNotBlank()) {
                    CompanyBannerImage(
                        bannerUrl = article.imageUrl,
                        contentDescriptionName = article.linkedIpoName ?: article.source,
                        headline = article.linkedIpoName ?: article.source,
                        subheadline = "${article.category.label} • ${article.source}",
                        height = 136.dp,
                    )
                }

                HorizontalDivider(color = marketColors.border)

                article.fullBody.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (article.linkedIpoId != null && article.linkedIpoName != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val targetId = article.linkedIpoId
                            activeArticle = null
                            onOpenIpoDetail(targetId)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("news_open_ipo_button"),
                    ) {
                        Text("View ${article.linkedIpoName} Financials")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                if (article.articleUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.articleUrl))
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("news_open_source_button"),
                    ) {
                        Text("Read on ${article.source}")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (showNewsAlertsSheet) {
        NewsAlertsBottomSheet(
            enabled = notifyNewsAlertsEnabled,
            selectedCategories = newsAlertCategories,
            hasNotificationPermission = hasNotificationPermission,
            onToggleEnabled = onToggleNewsAlerts,
            onToggleCategory = onToggleNewsAlertCategory,
            onRequestPermission = onRequestNotificationPermission,
            onSendTestAlert = onSendTestNewsAlert,
            onDismiss = { showNewsAlertsSheet = false },
        )
    }
}

@Composable
private fun NewsAlertsStatusBanner(
    enabled: Boolean,
    topicCount: Int,
    needsPermission: Boolean,
    onClick: () -> Unit,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current
    val accent = when {
        needsPermission && enabled -> marketColors.smeBadge
        enabled -> marketColors.bullish
        else -> marketColors.mutedText
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = glass.cardFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = glassBorderStroke(glass = glass, width = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("news_alerts_status_banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (enabled) {
                            Icons.Outlined.NotificationsActive
                        } else {
                            Icons.Outlined.NotificationsOff
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            needsPermission && enabled -> "Allow News Notifications"
                            enabled -> "News Alerts Active"
                            else -> "News Alerts Paused"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            needsPermission && enabled -> "Tap to grant permission for breaking IPO headlines"
                            enabled -> "Watching $topicCount topic${if (topicCount == 1) "" else "s"} for breaking IPO alerts"
                            else -> "Tap to enable instant alerts for breaking IPO news"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Manage",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewsAlertsBottomSheet(
    enabled: Boolean,
    selectedCategories: Set<NewsCategory>,
    hasNotificationPermission: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleCategory: (NewsCategory) -> Unit,
    onRequestPermission: () -> Unit,
    onSendTestAlert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current
    var testSent by remember { mutableStateOf(false) }
    val topicOptions = remember {
        NewsCategory.entries.filter { it != NewsCategory.ALL }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = glass.sheetFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("news_alerts_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column {
                    Text(
                        text = "IPO News Alerts",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Instant on-device alerts for the News tab",
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = glass.cardFill,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = glassBorderStroke(glass = glass, width = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Breaking IPO News Alerts",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Notify automatically when new verified IPO headlines arrive",
                            style = MaterialTheme.typography.bodySmall,
                            color = marketColors.mutedText,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.testTag("news_alerts_master_switch"),
                    )
                }
            }

            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("news_alerts_permission_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow Android Notifications")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ALERT TOPICS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = marketColors.mutedText,
                )
                Text(
                    text = "Choose which IPO news categories trigger a notification (at least 1 active).",
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    topicOptions.forEach { category ->
                        val isSelected = category in selectedCategories
                        FilterChip(
                            selected = isSelected,
                            enabled = enabled,
                            onClick = { onToggleCategory(category) },
                            label = {
                                Text(
                                    text = category.label,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = glass.pillFill,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.testTag("news_alert_cat_${category.name.lowercase()}"),
                        )
                    }
                }
            }

            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onSendTestAlert()
                        testSent = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("news_alerts_test_button"),
                ) {
                    Text(if (testSent) "Test Sent" else "Send Test Alert")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("news_alerts_done_button"),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun FeaturedNewsCard(
    article: IpoNewsArticle,
    onClick: () -> Unit,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(
                glass = glass,
                shape = MaterialTheme.shapes.large,
                fillColor = glass.elevatedFill,
                borderWidth = 1.dp,
                accentTint = MaterialTheme.colorScheme.primary,
            )
            .clickable(onClick = onClick)
            .testTag("featured_news_card")
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "TOP STORY • ${article.category.label.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = article.publishedAgo,
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    ),
                    color = marketColors.mutedText,
                )
            }

            if (article.imageUrl.isNotBlank()) {
                CompanyBannerImage(
                    bannerUrl = article.imageUrl,
                    contentDescriptionName = article.linkedIpoName ?: article.source,
                    headline = article.linkedIpoName ?: article.source,
                    subheadline = "${article.category.label} • ${article.source}",
                    height = 128.dp,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CompanyLogo(
                    logoUrl = article.logoUrl,
                    companyName = article.linkedIpoName ?: article.source,
                    shortCode = article.linkedShortCode,
                    boardType = article.linkedBoardType,
                    size = 44.dp,
                )
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = article.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${article.source} • ${article.readTimeMinutes} min read",
                    style = MaterialTheme.typography.labelMedium,
                    color = marketColors.mutedText,
                )
                Text(
                    text = "Read Brief →",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun NewsArticleCard(
    article: IpoNewsArticle,
    onClick: () -> Unit,
    onOpenLinkedIpo: (String) -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .testTag("news_card_${article.id}"),
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
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NewsCategoryBadge(category = article.category)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = marketColors.mutedText,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = article.publishedAgo,
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        ),
                        color = marketColors.mutedText,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CompanyLogo(
                    logoUrl = article.logoUrl,
                    companyName = article.linkedIpoName ?: article.source,
                    shortCode = article.linkedShortCode,
                    boardType = article.linkedBoardType,
                    size = 42.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = article.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.6.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Newspaper,
                        contentDescription = null,
                        tint = marketColors.mutedText,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = article.source,
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                }

                if (article.linkedIpoId != null && article.linkedIpoName != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenLinkedIpo(article.linkedIpoId) },
                    ) {
                        Text(
                            text = "${article.linkedIpoName.substringBefore(" ")} IPO →",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCategoryBadge(category: NewsCategory) {
    val marketColors = LocalMarketColors.current
    val (bg, fg) = when (category) {
        NewsCategory.UPCOMING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        NewsCategory.GMP_LISTING -> marketColors.bullishContainer to marketColors.bullish
        NewsCategory.SME_BUZZ -> marketColors.smeContainer to marketColors.smeBadge
        NewsCategory.SEBI_FILINGS, NewsCategory.ALL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = category.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
        )
    }
}
