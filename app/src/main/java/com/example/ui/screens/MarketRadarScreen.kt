package com.example.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.FilterAltOff
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BoardType
import com.example.data.model.IpoEntity
import com.example.ui.components.BoardPill
import com.example.ui.components.CompanyLogo
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.StatusPill
import com.example.ui.components.formatCrores
import com.example.ui.components.formatPercentSigned
import com.example.ui.components.formatRupees
import com.example.ui.components.formatRupeesSigned
import com.example.ui.components.formatSubX
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import com.example.ui.viewmodel.BoardFilter
import com.example.ui.viewmodel.IpoBoardUiState
import com.example.ui.viewmodel.RadarSortMode

@Composable
fun MarketRadarScreen(
    uiState: IpoBoardUiState,
    onSelectSortMode: (RadarSortMode) -> Unit,
    onIpoClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefreshGmp: () -> Unit = {},
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    var selectedBoard by remember { mutableStateOf(BoardFilter.ALL) }

    val displayedIpos = remember(uiState.radarIpos, selectedBoard) {
        when (selectedBoard) {
            BoardFilter.ALL -> uiState.radarIpos
            BoardFilter.MAINBOARD -> uiState.radarIpos.filter { it.boardType == BoardType.MAINBOARD }
            BoardFilter.SME -> uiState.radarIpos.filter { it.boardType == BoardType.SME }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("market_radar_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassCircleIconButton(
                        onClick = onBack,
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to IPO list",
                        modifier = Modifier.testTag("radar_back_button"),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "GMP & Sub Radar",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = uiState.gmpStatusMessage ?: uiState.liveGmpSourcesLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = marketColors.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (uiState.isRefreshingGmp) {
                    IconButton(
                        onClick = onRefreshGmp,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("radar_refresh_gmp_button"),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    GlassCircleIconButton(
                        onClick = onRefreshGmp,
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Refresh latest GMP",
                        modifier = Modifier.testTag("radar_refresh_gmp_button"),
                    )
                }
            }

            HorizontalDivider(thickness = 0.6.dp, color = glass.hairline)
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(RadarSortMode.entries.size) { index ->
                    val mode = RadarSortMode.entries[index]
                    val selected = uiState.radarSortMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectSortMode(mode) },
                        modifier = Modifier.testTag("radar_sort_${mode.name.lowercase()}"),
                        label = {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = glass.pillFill,
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoardFilter.entries.forEach { board ->
                    val selected = selectedBoard == board
                    FilterChip(
                        selected = selected,
                        onClick = { selectedBoard = board },
                        modifier = Modifier.testTag("radar_board_${board.name.lowercase()}"),
                        label = { Text(board.label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = glass.pillFill,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("radar_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (displayedIpos.isEmpty()) {
                    item(key = "radar_empty") {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = glass.cardFill,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = glassBorderStroke(glass = glass, width = 1.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .testTag("radar_empty_state"),
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
                                    text = "No IPOs in this board filter",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "Switch back to All boards or tap Refresh to fetch the latest verified GMP & subscription rankings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.mutedText,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = { selectedBoard = BoardFilter.ALL }) {
                                    Text("Show All IPOs", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = displayedIpos,
                        key = { _, item -> item.id },
                    ) { index, ipo ->
                        RadarRowCard(
                            rank = index + 1,
                            ipo = ipo,
                            onClick = { onIpoClick(ipo.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarRowCard(
    rank: Int,
    ipo: IpoEntity,
    onClick: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val isBullish = ipo.gmpRupees >= 0
    val gmpColor = if (isBullish) marketColors.bullish else marketColors.bearish
    val gmpBg = if (isBullish) marketColors.bullishContainer else marketColors.bearishContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("radar_card_${ipo.id}")
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
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
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "#$rank",
                            style = MonoNumericStyle.copy(
                                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    CompanyLogo(
                        logoUrl = ipo.logoUrl,
                        companyName = ipo.companyName,
                        shortCode = ipo.shortCode,
                        boardType = ipo.boardType,
                        width = 44.dp,
                        height = 32.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ipo.companyName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(status = ipo.status)
                            BoardPill(boardType = ipo.boardType, exchange = ipo.exchange)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(gmpBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatPercentSigned(ipo.gmpPercent),
                            style = MonoNumericStyle.copy(
                                fontSize = MaterialTheme.typography.titleSmall.fontSize,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = gmpColor,
                        )
                        Text(
                            text = formatRupeesSigned(ipo.gmpRupees),
                            style = MonoNumericStyle.copy(
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                            color = gmpColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.8.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Clean 2-row x 3-column metrics layout so nothing crowds or clips on compact screens
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadarStat(
                    label = "Est. List",
                    value = if (ipo.priceHigh > 0) formatRupees(ipo.estimatedListingPrice) else "TBA",
                    modifier = Modifier.weight(1f),
                )
                RadarStat(
                    label = "Issue Size",
                    value = if (ipo.issueSizeCr > 0.0) formatCrores(ipo.issueSizeCr) else "TBA",
                    modifier = Modifier.weight(1f),
                )
                RadarStat(
                    label = "Total Sub",
                    value = if (ipo.totalSubX > 0.0) formatSubX(ipo.totalSubX) else "—",
                    highlight = ipo.totalSubX >= 10.0,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadarStat(
                    label = "QIB",
                    value = if (ipo.qibSubX > 0.0) formatSubX(ipo.qibSubX) else "—",
                    modifier = Modifier.weight(1f),
                )
                RadarStat(
                    label = "NII",
                    value = if (ipo.niiCombinedSubX > 0.0) formatSubX(ipo.niiCombinedSubX) else "—",
                    modifier = Modifier.weight(1f),
                )
                RadarStat(
                    label = "Retail",
                    value = if (ipo.retailSubX > 0.0) formatSubX(ipo.retailSubX) else "—",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RadarStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val marketColors = LocalMarketColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = marketColors.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MonoNumericStyle.copy(
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontWeight = FontWeight.Bold,
            ),
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
