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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BoardType
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.ui.components.BoardPill
import com.example.ui.components.CompanyLogo
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.StatusPill
import com.example.ui.components.formatCrores
import com.example.ui.components.formatPercentSigned
import com.example.ui.components.formatRupeesSigned
import com.example.ui.components.formatSubX
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import com.example.ui.viewmodel.BoardFilter
import java.util.Calendar

private val YEAR_REGEX = Regex("\\b(20\\d{2})\\b")

internal fun extractIpoYear(
    ipo: IpoEntity,
    referenceTimeMs: Long = System.currentTimeMillis(),
): String {
    val candidates = listOf(ipo.listingDate, ipo.closeDate, ipo.openDate, ipo.allotmentDate)
    for (date in candidates) {
        val explicit = YEAR_REGEX.find(date)?.groupValues?.getOrNull(1)
        if (!explicit.isNullOrBlank()) return explicit
    }
    for (date in candidates) {
        val parsedMs = IpoDateUtils.parseFlexibleDateMs(date, referenceTimeMs)
        if (parsedMs != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = parsedMs }
            return cal.get(Calendar.YEAR).toString()
        }
    }
    val currentCal = Calendar.getInstance().apply { timeInMillis = referenceTimeMs }
    return currentCal.get(Calendar.YEAR).toString()
}

private fun formatReportPriceBand(priceLow: Int, priceHigh: Int): String = when {
    priceHigh <= 0 -> "TBA"
    priceLow > 0 && priceLow != priceHigh -> "₹$priceLow–₹$priceHigh"
    else -> "₹$priceHigh"
}

@Composable
fun YearWiseIpoListScreen(
    allIpos: List<IpoEntity>,
    onIpoClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val availableYears = remember(allIpos) {
        listOf("All") + allIpos.map { extractIpoYear(it) }.distinct().sortedDescending()
    }
    var selectedYear by remember { mutableStateOf("All") }
    var selectedBoard by remember { mutableStateOf(BoardFilter.ALL) }

    LaunchedEffect(availableYears) {
        if (selectedYear !in availableYears) {
            selectedYear = "All"
        }
    }

    val filteredIpos = remember(allIpos, selectedYear, selectedBoard) {
        allIpos
            .filter { ipo ->
                val yearMatch = selectedYear == "All" || extractIpoYear(ipo) == selectedYear
                val boardMatch = when (selectedBoard) {
                    BoardFilter.ALL -> true
                    BoardFilter.MAINBOARD -> ipo.boardType == BoardType.MAINBOARD
                    BoardFilter.SME -> ipo.boardType == BoardType.SME
                }
                yearMatch && boardMatch
            }
            .sortedByDescending { ipo ->
                IpoDateUtils.parseFlexibleDateMs(ipo.listingDate)
                    ?: IpoDateUtils.parseFlexibleDateMs(ipo.closeDate)
                    ?: IpoDateUtils.parseFlexibleDateMs(ipo.openDate)
                    ?: Long.MIN_VALUE
            }
    }

    val totalIssueSizeCr = remember(filteredIpos) { filteredIpos.sumOf { it.issueSizeCr } }
    val avgGmpPercent = remember(filteredIpos) {
        val valid = filteredIpos.filter { it.priceHigh > 0 }
        if (valid.isEmpty()) 0.0 else valid.map { it.gmpPercent }.average()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("yearwise_ipo_list_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            DrawerSubScreenTopBar(
                title = "IPO List (Year-wise)",
                subtitle = "${filteredIpos.size} IPOs • Mainboard & SME Archive",
                onBack = onBack,
                backButtonTag = "yearwise_list_back_button",
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("yearwise_ipo_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "year_filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableYears) { year ->
                                val selected = selectedYear == year
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedYear = year },
                                    label = { Text(if (year == "All") "All Years" else year) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = glass.pillFill,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    modifier = Modifier.testTag("year_chip_$year"),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoardFilter.entries.forEach { board ->
                                val selected = selectedBoard == board
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedBoard = board },
                                    label = { Text(board.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = glass.pillFill,
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                                    modifier = Modifier.testTag("yearwise_board_chip_${board.name.lowercase()}"),
                                )
                            }
                        }
                    }
                }

                item(key = "summary_strip") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = glass.cardFill,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = glassBorderStroke(glass = glass, width = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("yearwise_summary_strip"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            ReportStatPill(label = "IPOs", value = filteredIpos.size.toString())
                            ReportStatPill(
                                label = "Issue Volume",
                                value = if (totalIssueSizeCr > 0) formatCrores(totalIssueSizeCr) else "TBA",
                            )
                            ReportStatPill(
                                label = "Avg GMP / Gain",
                                value = formatPercentSigned(avgGmpPercent),
                                valueColor = if (avgGmpPercent >= 0) marketColors.bullish else marketColors.bearish,
                            )
                        }
                    }
                }

                if (filteredIpos.isEmpty()) {
                    item(key = "yearwise_empty") {
                        ReportEmptyStateCard(
                            title = "No IPOs found for this filter",
                            message = "Try switching to All Years or selecting All boards to view verified Mainboard & SME IPO records.",
                            onReset = {
                                selectedYear = "All"
                                selectedBoard = BoardFilter.ALL
                            },
                            testTag = "yearwise_empty_state",
                        )
                    }
                } else {
                    items(items = filteredIpos, key = { it.id }) { ipo ->
                        val isListed = IpoDateUtils.isCloseDatePassed(
                            closeDate = ipo.closeDate,
                            status = ipo.status,
                            listingDate = ipo.listingDate,
                        )
                        val gainTint = if (ipo.gmpRupees >= 0) marketColors.bullish else marketColors.bearish
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { onIpoClick(ipo.id) }
                                .testTag("yearwise_ipo_row_${ipo.id}"),
                            shape = MaterialTheme.shapes.medium,
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CompanyLogo(
                                        logoUrl = ipo.logoUrl,
                                        companyName = ipo.companyName,
                                        shortCode = ipo.shortCode,
                                        boardType = ipo.boardType,
                                        width = 48.dp,
                                        height = 34.dp,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ipo.companyName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (isListed) {
                                                "Listed: ${ipo.listingDate}"
                                            } else {
                                                "Open: ${ipo.openDate} – ${ipo.closeDate}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = marketColors.mutedText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    StatusPill(status = ipo.status)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    BoardPill(boardType = ipo.boardType, exchange = ipo.exchange)
                                    Text(
                                        text = "${extractIpoYear(ipo)} • Lot ${ipo.lotSize}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = marketColors.mutedText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                HorizontalDivider(thickness = 0.7.dp, color = marketColors.border.copy(alpha = 0.7f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ReportCompactMetric(
                                        label = "Price Band",
                                        value = formatReportPriceBand(ipo.priceLow, ipo.priceHigh),
                                        modifier = Modifier.weight(1f),
                                    )
                                    ReportCompactMetric(
                                        label = "Issue Size",
                                        value = if (ipo.issueSizeCr > 0) formatCrores(ipo.issueSizeCr) else "TBA",
                                        modifier = Modifier.weight(1f),
                                        alignEnd = false,
                                    )
                                    ReportCompactMetric(
                                        label = "Est. GMP",
                                        value = "${formatRupeesSigned(ipo.gmpRupees)} (${formatPercentSigned(ipo.gmpPercent)})",
                                        valueColor = gainTint,
                                        modifier = Modifier.weight(1.2f),
                                        alignEnd = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YearWiseSubscriptionScreen(
    allIpos: List<IpoEntity>,
    onIpoClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val availableYears = remember(allIpos) {
        listOf("All") + allIpos.map { extractIpoYear(it) }.distinct().sortedDescending()
    }
    var selectedYear by remember { mutableStateOf("All") }
    var selectedBoard by remember { mutableStateOf(BoardFilter.ALL) }

    LaunchedEffect(availableYears) {
        if (selectedYear !in availableYears) {
            selectedYear = "All"
        }
    }

    val rankedIpos = remember(allIpos, selectedYear, selectedBoard) {
        allIpos
            .filter { ipo ->
                val yearMatch = selectedYear == "All" || extractIpoYear(ipo) == selectedYear
                val boardMatch = when (selectedBoard) {
                    BoardFilter.ALL -> true
                    BoardFilter.MAINBOARD -> ipo.boardType == BoardType.MAINBOARD
                    BoardFilter.SME -> ipo.boardType == BoardType.SME
                }
                yearMatch && boardMatch
            }
            .sortedWith(
                compareByDescending<IpoEntity> { it.totalSubX }
                    .thenBy { it.status.ordinal },
            )
    }

    val oversubscribedCount = remember(rankedIpos) { rankedIpos.count { it.totalSubX >= 1.0 } }
    val peakSubX = remember(rankedIpos) { rankedIpos.maxOfOrNull { it.totalSubX } ?: 0.0 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("yearwise_subscription_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            DrawerSubScreenTopBar(
                title = "Subscription (Year-wise)",
                subtitle = "QIB • NII • Retail • Total Demand Tracker",
                onBack = onBack,
                backButtonTag = "yearwise_sub_back_button",
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("yearwise_sub_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "sub_year_chips") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableYears) { year ->
                                FilterChip(
                                    selected = selectedYear == year,
                                    onClick = { selectedYear = year },
                                    label = { Text(if (year == "All") "All Years" else year) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = glass.pillFill,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    modifier = Modifier.testTag("sub_year_chip_$year"),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoardFilter.entries.forEach { board ->
                                val selected = selectedBoard == board
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedBoard = board },
                                    label = { Text(board.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = glass.pillFill,
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                                    modifier = Modifier.testTag("sub_board_chip_${board.name.lowercase()}"),
                                )
                            }
                        }
                    }
                }

                item(key = "sub_summary_strip") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = glass.cardFill,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = glassBorderStroke(glass = glass, width = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sub_summary_strip"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            ReportStatPill(label = "IPOs Tracked", value = rankedIpos.size.toString())
                            ReportStatPill(
                                label = "Oversubscribed",
                                value = oversubscribedCount.toString(),
                                valueColor = if (oversubscribedCount > 0) marketColors.bullish else MaterialTheme.colorScheme.onSurface,
                            )
                            ReportStatPill(
                                label = "Peak Demand",
                                value = if (peakSubX > 0.0) formatSubX(peakSubX) else "—",
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (rankedIpos.isEmpty()) {
                    item(key = "sub_empty") {
                        ReportEmptyStateCard(
                            title = "No subscription records match",
                            message = "Reset the year or board filter to compare QIB, NII, and Retail demand across verified IPOs.",
                            onReset = {
                                selectedYear = "All"
                                selectedBoard = BoardFilter.ALL
                            },
                            testTag = "sub_empty_state",
                        )
                    }
                } else {
                    items(items = rankedIpos, key = { it.id }) { ipo ->
                        val totalBadgeText = when {
                            ipo.totalSubX > 0.0 -> formatSubX(ipo.totalSubX)
                            ipo.status == IpoStatus.UPCOMING || ipo.status == IpoStatus.PRE_APPLY -> "Upcoming"
                            else -> "Awaiting"
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { onIpoClick(ipo.id) }
                                .testTag("sub_report_row_${ipo.id}"),
                            shape = MaterialTheme.shapes.medium,
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ipo.companyName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${extractIpoYear(ipo)} • ${ipo.exchange} • Issue ${if (ipo.issueSizeCr > 0) formatCrores(ipo.issueSizeCr) else "TBA"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = marketColors.mutedText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(99.dp),
                                        color = if (ipo.totalSubX >= 1.0) {
                                            marketColors.bullishContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ) {
                                        Text(
                                            text = totalBadgeText,
                                            style = MonoNumericStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                                            color = if (ipo.totalSubX >= 1.0) {
                                                marketColors.bullish
                                            } else {
                                                marketColors.mutedText
                                            },
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                }

                                HorizontalDivider(thickness = 0.7.dp, color = marketColors.border.copy(alpha = 0.7f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    SubscriptionMiniColumn("QIB", ipo.qibSubX, modifier = Modifier.weight(1f))
                                    SubscriptionMiniColumn("NII", ipo.niiCombinedSubX, modifier = Modifier.weight(1f))
                                    SubscriptionMiniColumn("Retail", ipo.retailSubX, modifier = Modifier.weight(1f))
                                    SubscriptionMiniColumn(
                                        label = "Total",
                                        subX = ipo.totalSubX,
                                        highlight = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class RegistrarPortal(
    val name: String,
    val subtitle: String,
    val url: String,
)

@Composable
fun AllotmentHubScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAllotmentAlerts: () -> Unit = {},
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current

    val registrars = remember {
        listOf(
            RegistrarPortal(
                name = "MUFG Intime (Link Intime)",
                subtitle = "Official Mainboard & SME IPO Allotment Portal",
                url = "https://in.mpms.mufg.com/Initial_Offer/public-issues.html",
            ),
            RegistrarPortal(
                name = "KFin Technologies (KFintech)",
                subtitle = "Check IPO Status via PAN, Application No. or DP/Client ID",
                url = "https://ris.kfintech.com/ipostatus/",
            ),
            RegistrarPortal(
                name = "Bigshare Services",
                subtitle = "Mainboard & SME Registrar Allotment Search",
                url = "https://ipo.bigshareonline.com/IPO_Status.html",
            ),
            RegistrarPortal(
                name = "Skyline Financial Services",
                subtitle = "SME & Mainboard IPO Allotment Verification",
                url = "https://www.skylinerta.com/ipo.php",
            ),
            RegistrarPortal(
                name = "Cameo Corporate Services",
                subtitle = "Public Issue Allotment Status Lookup",
                url = "https://ipo.cameoindia.com/",
            ),
            RegistrarPortal(
                name = "Maashitla Securities",
                subtitle = "SME Public Issue Allotment Portal",
                url = "https://maashitla.com/allotment-status/public-issues",
            ),
            RegistrarPortal(
                name = "BSE India IPO Status",
                subtitle = "Exchange-level Application Verification (Equity)",
                url = "https://www.bseindia.com/investors/appli_check.aspx",
            ),
            RegistrarPortal(
                name = "NSE India Bid Verification",
                subtitle = "Verify UPI / ASBA IPO Bids on NSE",
                url = "https://www.nseindia.com/invest/check-trades-bids-verify-ipo-bids",
            ),
        )
    }

    val checklistSteps = remember {
        listOf(
            "Keep your 10-digit PAN, Application Number, or DP/Client ID handy.",
            "Retail bids are best placed at the Cut-Off Price to avoid price-band rejection.",
            "Approve your UPI Autopay Mandate before 5:00 PM on the IPO closing date.",
            "Allotment is typically finalized late evening on T+1 working day after close.",
            "Blocked ASBA/UPI funds are released on T+2 if shares are not allotted.",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("allotment_hub_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            DrawerSubScreenTopBar(
                title = "Allotment Hub",
                subtitle = "Official Registrars & ASBA / UPI Checklist",
                onBack = onBack,
                backButtonTag = "allotment_hub_back_button",
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "instant_alerts_cta") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(onClick = onOpenAllotmentAlerts)
                            .testTag("allotment_hub_open_alerts_card"),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Instant Private Allotment Alerts",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Save multiple private PAN profiles on-device & get instant alerts when allotted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item(key = "registrars_header") {
                    Text(
                        text = "OFFICIAL REGISTRAR & EXCHANGE PORTALS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = marketColors.mutedText,
                    )
                }

                items(items = registrars, key = { it.name }) { registrar ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(registrar.url)),
                                    )
                                }
                            }
                            .testTag("registrar_portal_${registrar.name}"),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = glass.cardFill,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = glassBorderStroke(glass = glass, width = 1.dp),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (glass.isDark || glass.isGlass) 0.dp else 1.5.dp,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Verified,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = registrar.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = registrar.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.mutedText,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Open ${registrar.name}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                item(key = "checklist_card") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = glass.cardFill,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = glassBorderStroke(glass = glass, width = 1.dp),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (glass.isDark || glass.isGlass) 0.dp else 1.5.dp,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "ASBA & UPI Application Checklist",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            checklistSteps.forEach { step ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = marketColors.bullish,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(16.dp),
                                    )
                                    Text(
                                        text = step,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DrawerSubScreenTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    backButtonTag: String,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCircleIconButton(
            onClick = onBack,
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            modifier = Modifier.testTag(backButtonTag),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRefresh != null) {
            if (isRefreshing) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.minimumInteractiveComponentSize(),
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
                    contentDescription = "Refresh report",
                )
            }
        }
    }
    HorizontalDivider(thickness = 0.6.dp, color = glass.hairline)
}

@Composable
private fun ReportEmptyStateCard(
    title: String,
    message: String,
    onReset: () -> Unit,
    testTag: String,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = glass.cardFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = glassBorderStroke(glass = glass, width = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FilterAltOff,
                contentDescription = null,
                tint = marketColors.mutedText,
                modifier = Modifier.size(34.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onReset) {
                Text("Reset Filters", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReportCompactMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    alignEnd: Boolean = false,
) {
    val marketColors = LocalMarketColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
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
            style = MonoNumericStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReportStatPill(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val marketColors = LocalMarketColors.current
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = marketColors.mutedText,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MonoNumericStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = valueColor,
        )
    }
}

@Composable
private fun SubscriptionMiniColumn(
    label: String,
    subX: Double,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val marketColors = LocalMarketColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = marketColors.mutedText,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = if (subX > 0.0) formatSubX(subX) else "—",
            style = MonoNumericStyle.copy(
                fontSize = 13.sp,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = if (highlight && subX >= 1.0) marketColors.bullish else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
