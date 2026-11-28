package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.IpoEntity
import com.example.ui.components.formatCrores
import com.example.ui.components.formatPercentSigned
import com.example.ui.components.formatRupees
import com.example.ui.components.formatRupeesSigned
import com.example.ui.components.formatSubX
import com.example.ui.components.glassBorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ads.ConsentManager
import com.example.ads.NativeAdPlacement
import com.example.ads.NativeAdSlot
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import java.util.Locale

private enum class CompareWinnerRule {
    HIGHER_IS_BETTER,
    LOWER_IS_BETTER,
    NONE,
}

private data class CompareMetricRow(
    val id: String,
    val label: String,
    val values: List<String>,
    val numericScores: List<Double?>,
    val rule: CompareWinnerRule,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareIposScreen(
    allIpos: List<IpoEntity>,
    initialIpoId: String? = null,
    onIpoClick: (String) -> Unit,
    onBack: () -> Unit,
    onAdImpression: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val canRequestAds by ConsentManager.canRequestAds.collectAsStateWithLifecycle()

    val initialSelection = remember(allIpos, initialIpoId) {
        val ordered = mutableListOf<String>()
        if (!initialIpoId.isNullOrBlank() && allIpos.any { it.id == initialIpoId }) {
            ordered.add(initialIpoId)
        }
        allIpos.forEach { ipo ->
            if (ordered.size < 2 && ipo.id !in ordered) {
                ordered.add(ipo.id)
            }
        }
        ordered.toList()
    }

    var selectedIds by remember(initialSelection) { mutableStateOf(initialSelection) }
    var pickerSlotIndex by remember { mutableStateOf<Int?>(null) }

    val selectedIpos = remember(selectedIds, allIpos) {
        selectedIds.mapNotNull { id -> allIpos.firstOrNull { it.id == id } }
    }

    val comparisonRows = remember(selectedIpos) {
        buildComparisonRows(selectedIpos)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("compare_ipos_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 820.dp),
        ) {
            DrawerSubScreenTopBar(
                title = "Compare IPOs",
                subtitle = "Side-by-side GMP, Valuation, Fresh vs OFS & Demand",
                onBack = onBack,
                backButtonTag = "compare_ipos_back_button",
            )

            if (allIpos.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "At least 2 verified IPOs are required for side-by-side comparison.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = marketColors.mutedText,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("compare_ipos_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Slot selector strip (2 or 3 IPOs)
                item(key = "slot_selector") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = glass.cardFill,
                        border = glassBorderStroke(glass = glass, width = 1.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
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
                                        imageVector = Icons.Outlined.Balance,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "Comparing ${selectedIpos.size} IPOs",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                if (selectedIpos.size < 3 && allIpos.size >= 3) {
                                    OutlinedButton(
                                        onClick = { pickerSlotIndex = selectedIpos.size },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("compare_add_third_btn"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add 3rd IPO", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                selectedIpos.forEachIndexed { slotIndex, ipo ->
                                    Surface(
                                        onClick = { pickerSlotIndex = slotIndex },
                                        shape = RoundedCornerShape(12.dp),
                                        color = glass.pillFill,
                                        border = glassBorderStroke(
                                            glass = glass,
                                            width = 1.dp,
                                            accentTint = MaterialTheme.colorScheme.primary,
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("compare_slot_$slotIndex"),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = ipo.boardType.displayLabel.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                if (selectedIpos.size > 2) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Close,
                                                        contentDescription = "Remove ${ipo.companyName}",
                                                        tint = marketColors.mutedText,
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .clickable {
                                                                selectedIds = selectedIds.filterIndexed { i, _ -> i != slotIndex }
                                                            }
                                                            .testTag("compare_remove_slot_$slotIndex"),
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Outlined.SwapHoriz,
                                                        contentDescription = "Swap IPO",
                                                        tint = marketColors.mutedText,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            Text(
                                                text = ipo.companyName,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "GMP ${formatPercentSigned(ipo.gmpPercent)}",
                                                style = MonoNumericStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                                color = if (ipo.gmpRupees >= 0) marketColors.bullish else marketColors.bearish,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Side-by-Side Comparison Matrix Card
                item(key = "comparison_matrix") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = glass.cardFill,
                        border = glassBorderStroke(glass = glass, width = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("compare_matrix_card"),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Header Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "METRIC",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = marketColors.mutedText,
                                    modifier = Modifier.weight(1.05f),
                                )
                                selectedIpos.forEach { ipo ->
                                    Text(
                                        text = ipo.shortCode.ifBlank { ipo.companyName.take(8) },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onIpoClick(ipo.id) },
                                    )
                                }
                            }

                            HorizontalDivider(color = glass.hairline)

                            comparisonRows.forEachIndexed { rowIdx, row ->
                                val winnerIndex = resolveWinnerIndex(row.numericScores, row.rule)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                        .testTag("compare_row_${row.id}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = row.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = marketColors.mutedText,
                                        modifier = Modifier.weight(1.05f),
                                    )
                                    row.values.forEachIndexed { colIdx, value ->
                                        val isWinner = winnerIndex == colIdx
                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(
                                                        if (isWinner) marketColors.bullishContainer else Color.Transparent,
                                                    )
                                                    .padding(horizontal = if (isWinner) 6.dp else 2.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = value,
                                                    style = MonoNumericStyle.copy(
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isWinner) FontWeight.Bold else FontWeight.SemiBold,
                                                    ),
                                                    color = if (isWinner) {
                                                        marketColors.bullish
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                    textAlign = TextAlign.End,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                                if (rowIdx < comparisonRows.lastIndex) {
                                    HorizontalDivider(color = glass.hairline.copy(alpha = 0.65f), thickness = 0.6.dp)
                                }
                            }
                        }
                    }
                }

                // Quick Open Report links
                item(key = "open_reports_row") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selectedIpos.forEach { ipo ->
                            OutlinedButton(
                                onClick = { onIpoClick(ipo.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("compare_open_${ipo.id}"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = "Open ${ipo.shortCode.ifBlank { ipo.companyName.take(8) }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (canRequestAds) {
                    item(key = "compare_native_ad_2") {
                        NativeAdSlot(
                            placement = NativeAdPlacement.SECONDARY,
                            onAdImpression = onAdImpression,
                            slotTag = "compare_native_ad",
                        )
                    }
                }
            }
        }
    }

    val activeSlot = pickerSlotIndex
    if (activeSlot != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { pickerSlotIndex = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("compare_picker_sheet"),
            ) {
                Text(
                    text = if (activeSlot >= selectedIds.size) "Add 3rd IPO to Compare" else "Select IPO for Slot #${activeSlot + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(allIpos, key = { it.id }) { candidate ->
                        val alreadyPicked = candidate.id in selectedIds
                        Surface(
                            onClick = {
                                if (!alreadyPicked) {
                                    val mutable = selectedIds.toMutableList()
                                    if (activeSlot < mutable.size) {
                                        mutable[activeSlot] = candidate.id
                                    } else if (mutable.size < 3) {
                                        mutable.add(candidate.id)
                                    }
                                    selectedIds = mutable
                                    pickerSlotIndex = null
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (alreadyPicked) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                glass.pillFill
                            },
                            border = BorderStroke(
                                1.dp,
                                if (alreadyPicked) MaterialTheme.colorScheme.primary else marketColors.border,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("compare_pick_${candidate.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.companyName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${candidate.boardType.displayLabel} • ${candidate.status.displayLabel} • Size ${formatCrores(candidate.issueSizeCr)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = marketColors.mutedText,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = formatPercentSigned(candidate.gmpPercent),
                                        style = MonoNumericStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                                        color = if (candidate.gmpRupees >= 0) marketColors.bullish else marketColors.bearish,
                                    )
                                    if (alreadyPicked) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
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
}

private fun buildComparisonRows(ipos: List<IpoEntity>): List<CompareMetricRow> {
    if (ipos.isEmpty()) return emptyList()
    return listOf(
        CompareMetricRow(
            id = "gmp_pct",
            label = "Live GMP %",
            values = ipos.map { "${formatRupeesSigned(it.gmpRupees)} (${formatPercentSigned(it.gmpPercent)})" },
            numericScores = ipos.map { it.gmpPercent },
            rule = CompareWinnerRule.HIGHER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "price_band",
            label = "Offer Price",
            values = ipos.map {
                if (it.priceLow > 0 && it.priceLow != it.priceHigh) "₹${it.priceLow}–₹${it.priceHigh}" else "₹${it.priceHigh}"
            },
            numericScores = ipos.map { null },
            rule = CompareWinnerRule.NONE,
        ),
        CompareMetricRow(
            id = "min_lot",
            label = "Min Lot Amt",
            values = ipos.map { formatRupees(it.minInvestment) },
            numericScores = ipos.map { it.minInvestment.toDouble().takeIf { v -> v > 0 } },
            rule = CompareWinnerRule.LOWER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "issue_size",
            label = "Issue Size",
            values = ipos.map { formatCrores(it.issueSizeCr) },
            numericScores = ipos.map { it.issueSizeCr.takeIf { v -> v > 0.0 } },
            rule = CompareWinnerRule.NONE,
        ),
        CompareMetricRow(
            id = "fresh_pct",
            label = "Fresh Issue %",
            values = ipos.map { ipo ->
                val split = FreshVsOfsAnalyzer.analyze(ipo)
                if (split != null) String.format(Locale.US, "%.0f%%", split.freshPercent) else "—"
            },
            numericScores = ipos.map { ipo -> FreshVsOfsAnalyzer.analyze(ipo)?.freshPercent },
            rule = CompareWinnerRule.HIGHER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "pe_ratio",
            label = "P/E Ratio",
            values = ipos.map {
                if (it.valuation.peRatio > 0.0) String.format(Locale.US, "%.1fx", it.valuation.peRatio) else "—"
            },
            numericScores = ipos.map { it.valuation.peRatio.takeIf { v -> v > 0.0 } },
            rule = CompareWinnerRule.LOWER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "roe_pct",
            label = "ROE / RoNW",
            values = ipos.map {
                val metric = it.valuation.roePercent.takeIf { v -> v > 0.0 } ?: it.valuation.ronwPercent
                if (metric > 0.0) String.format(Locale.US, "%.1f%%", metric) else "—"
            },
            numericScores = ipos.map {
                (it.valuation.roePercent.takeIf { v -> v > 0.0 } ?: it.valuation.ronwPercent).takeIf { v -> v > 0.0 }
            },
            rule = CompareWinnerRule.HIGHER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "qib_sub",
            label = "QIB Sub",
            values = ipos.map { if (it.qibSubX > 0.0) formatSubX(it.qibSubX) else "—" },
            numericScores = ipos.map { it.qibSubX.takeIf { v -> v > 0.0 } },
            rule = CompareWinnerRule.HIGHER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "nii_sub",
            label = "NII Sub",
            values = ipos.map { if (it.niiCombinedSubX > 0.0) formatSubX(it.niiCombinedSubX) else "—" },
            numericScores = ipos.map { it.niiCombinedSubX.takeIf { v -> v > 0.0 } },
            rule = CompareWinnerRule.HIGHER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "retail_sub",
            label = "Retail Sub",
            values = ipos.map { if (it.retailSubX > 0.0) formatSubX(it.retailSubX) else "—" },
            numericScores = ipos.map { it.retailSubX.takeIf { v -> v > 0.0 } },
            rule = CompareWinnerRule.HIGHER_IS_BETTER,
        ),
        CompareMetricRow(
            id = "total_sub",
            label = "Total Sub",
            values = ipos.map { if (it.totalSubX > 0.0) formatSubX(it.totalSubX) else "—" },
            numericScores = ipos.map { it.totalSubX.takeIf { v -> v > 0.0 } },
            rule = CompareWinnerRule.HIGHER_IS_BETTER,
        ),
    )
}

private fun resolveWinnerIndex(
    scores: List<Double?>,
    rule: CompareWinnerRule,
): Int? {
    if (rule == CompareWinnerRule.NONE) return null
    val indexed = scores.mapIndexedNotNull { idx, score -> score?.let { idx to it } }
    if (indexed.size < 2) return null
    val distinctValues = indexed.map { it.second }.distinct()
    if (distinctValues.size < 2) return null
    return when (rule) {
        CompareWinnerRule.HIGHER_IS_BETTER -> indexed.maxByOrNull { it.second }?.first
        CompareWinnerRule.LOWER_IS_BETTER -> indexed.minByOrNull { it.second }?.first
        CompareWinnerRule.NONE -> null
    }
}
