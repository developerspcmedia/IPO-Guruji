package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.outlined.AccessAlarm
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.preferences.GmpWatchRule
import com.example.ui.components.formatCrores
import com.example.ui.components.formatPercentSigned
import com.example.ui.components.formatRupees
import com.example.ui.components.formatRupeesSigned
import com.example.ui.components.formatSubX
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import java.util.Calendar
import java.util.Locale
import kotlin.math.pow

data class CategoryOddsSnapshot(
    val categoryLabel: String,
    val subtitle: String,
    val probabilityPercent: Double,
    val capitalRequired: Int,
    val subscriptionX: Double,
)

data class AllotmentProbabilityResult(
    val singlePanRetailPercent: Double,
    val multiPanRetailPercent: Double,
    val panCount: Int,
    val sHniPercent: Double,
    val bHniPercent: Double,
    val bestStrategyHeadline: String,
    val rows: List<CategoryOddsSnapshot>,
)

object AllotmentProbabilityCalculator {
    fun calculate(ipo: IpoEntity, panCount: Int): AllotmentProbabilityResult {
        val safePans = panCount.coerceIn(1, 5)
        val retailSub = ipo.retailSubX.coerceAtLeast(0.0)
        val sHniSub = ipo.sNiiSubX.coerceAtLeast(0.0)
        val bHniSub = ipo.bNiiSubX.coerceAtLeast(0.0)

        val singleRetailProb = singleChance(retailSub)
        val multiRetailProb = if (singleRetailProb >= 1.0) {
            1.0
        } else {
            1.0 - (1.0 - singleRetailProb).pow(safePans.toDouble())
        }
        val sHniProb = singleChance(sHniSub)
        val bHniProb = singleChance(bHniSub)

        val singleRetailPct = singleRetailProb * 100.0
        val multiRetailPct = multiRetailProb * 100.0
        val sHniPct = sHniProb * 100.0
        val bHniPct = bHniProb * 100.0

        val retailCapital = ipo.minInvestment * safePans
        val sHniCapital = ipo.minInvestment * ipo.sHniMinLots
        val bHniCapital = ipo.minInvestment * ipo.bHniMinLots

        val bestStrategy = when {
            retailSub <= 1.0 && retailSub > 0.0 ->
                "Retail is under-subscribed (${formatSubX(retailSub)}): 1 lot per PAN has near-certain allotment."
            bHniSub > 0.0 && bHniPct > multiRetailPct && bHniPct > sHniPct ->
                "Big HNI (${formatOddsPct(bHniPct)}) currently beats $safePans-PAN Retail (${formatOddsPct(multiRetailPct)}) on odds."
            sHniSub > 0.0 && sHniPct > multiRetailPct ->
                "Small HNI (${formatOddsPct(sHniPct)}) offers stronger odds than $safePans-PAN Retail right now."
            safePans > 1 && multiRetailPct > singleRetailPct + 0.5 ->
                "Splitting 1 lot across $safePans Family PANs lifts allotment odds from ${formatOddsPct(singleRetailPct)} to ${formatOddsPct(multiRetailPct)}."
            else ->
                "Apply 1 minimum lot per unique PAN in Retail (applying multiple lots in 1 PAN does not boost lottery odds when oversubscribed)."
        }

        val rows = listOf(
            CategoryOddsSnapshot(
                categoryLabel = if (safePans == 1) "Retail (1 PAN)" else "Retail ($safePans PANs • 1 Lot ea)",
                subtitle = if (retailSub > 0.0) "${formatSubX(retailSub)} sub" else "Live sub pending",
                probabilityPercent = multiRetailPct,
                capitalRequired = retailCapital,
                subscriptionX = retailSub,
            ),
            CategoryOddsSnapshot(
                categoryLabel = "Small HNI (sHNI)",
                subtitle = if (sHniSub > 0.0) "${formatSubX(sHniSub)} sub" else "₹2L–₹10L quota",
                probabilityPercent = sHniPct,
                capitalRequired = sHniCapital,
                subscriptionX = sHniSub,
            ),
            CategoryOddsSnapshot(
                categoryLabel = "Big HNI (bHNI)",
                subtitle = if (bHniSub > 0.0) "${formatSubX(bHniSub)} sub" else "> ₹10L quota",
                probabilityPercent = bHniPct,
                capitalRequired = bHniCapital,
                subscriptionX = bHniSub,
            ),
        )

        return AllotmentProbabilityResult(
            singlePanRetailPercent = singleRetailPct,
            multiPanRetailPercent = multiRetailPct,
            panCount = safePans,
            sHniPercent = sHniPct,
            bHniPercent = bHniPct,
            bestStrategyHeadline = bestStrategy,
            rows = rows,
        )
    }

    private fun singleChance(subscriptionX: Double): Double {
        if (subscriptionX <= 1.0) return 1.0
        return (1.0 / subscriptionX).coerceIn(0.0, 1.0)
    }

    fun formatOddsPct(percent: Double): String {
        return when {
            percent >= 99.9 -> "100%"
            percent < 0.1 -> "<0.1%"
            percent < 10.0 -> String.format(Locale.US, "%.1f%%", percent)
            else -> String.format(Locale.US, "%.0f%%", percent)
        }
    }
}

data class FreshVsOfsBreakdown(
    val freshCr: Double,
    val ofsCr: Double,
    val totalCr: Double,
    val freshPercent: Double,
    val ofsPercent: Double,
    val verdictLabel: String,
    val verdictDetail: String,
    val isBullishSignal: Boolean,
)

object FreshVsOfsAnalyzer {
    fun analyze(ipo: IpoEntity): FreshVsOfsBreakdown? {
        val rawFresh = ipo.freshIssueCr.coerceAtLeast(0.0)
        val rawOfs = ipo.ofsCr.coerceAtLeast(0.0)
        val total = when {
            rawFresh + rawOfs > 0.0 -> rawFresh + rawOfs
            ipo.issueSizeCr > 0.0 -> ipo.issueSizeCr
            else -> return null
        }
        val effectiveFresh = if (rawFresh + rawOfs > 0.0) rawFresh else total
        val effectiveOfs = if (rawFresh + rawOfs > 0.0) rawOfs else 0.0
        val freshPct = ((effectiveFresh / total) * 100.0).coerceIn(0.0, 100.0)
        val ofsPct = (100.0 - freshPct).coerceIn(0.0, 100.0)

        val (verdict, detail, bullish) = when {
            freshPct >= 99.5 -> Triple(
                "100% Fresh Capital",
                "Entire IPO proceeds flow directly into company growth, capex, or debt reduction.",
                true,
            )
            freshPct >= 65.0 -> Triple(
                "Growth-Focused",
                "Majority fresh issue strengthens the balance sheet with limited promoter exit.",
                true,
            )
            freshPct >= 35.0 -> Triple(
                "Balanced Mix",
                "Combines fresh growth capital with partial early investor/promoter liquidity.",
                true,
            )
            freshPct > 0.5 -> Triple(
                "OFS-Heavy",
                "Most proceeds go to selling shareholders rather than company operations.",
                false,
            )
            else -> Triple(
                "100% Offer for Sale",
                "Pure secondary exit — company receives zero fresh proceeds from this IPO.",
                false,
            )
        }

        return FreshVsOfsBreakdown(
            freshCr = effectiveFresh,
            ofsCr = effectiveOfs,
            totalCr = total,
            freshPercent = freshPct,
            ofsPercent = ofsPct,
            verdictLabel = verdict,
            verdictDetail = detail,
            isBullishSignal = bullish,
        )
    }
}

fun launchAddIpoEventToCalendar(
    context: Context,
    title: String,
    dateText: String,
    description: String,
    hourOfDay: Int = 10,
    allDay: Boolean = false,
) {
    val parsedMs = IpoDateUtils.parseFlexibleDateMs(dateText)
    val startCal = Calendar.getInstance().apply {
        if (parsedMs != null) {
            timeInMillis = parsedMs
        }
        set(Calendar.HOUR_OF_DAY, hourOfDay)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val endCal = (startCal.clone() as Calendar).apply {
        add(Calendar.HOUR_OF_DAY, 1)
    }

    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.Events.DESCRIPTION, description)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startCal.timeInMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endCal.timeInMillis)
    }

    val launched = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    if (!launched) {
        Toast.makeText(
            context,
            "No calendar app found on this device.",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

@Composable
fun FreshVsOfsVisualBarCard(
    ipo: IpoEntity,
    modifier: Modifier = Modifier,
) {
    val breakdown = remember(ipo.id, ipo.freshIssueCr, ipo.ofsCr, ipo.issueSizeCr) {
        FreshVsOfsAnalyzer.analyze(ipo)
    } ?: return

    val marketColors = LocalMarketColors.current
    val verdictBg = if (breakdown.isBullishSignal) {
        marketColors.bullishContainer
    } else {
        marketColors.smeContainer
    }
    val verdictFg = if (breakdown.isBullishSignal) {
        marketColors.bullish
    } else {
        marketColors.smeBadge
    }

    InvestorToolCard(
        title = "Fresh Issue vs OFS Split",
        modifier = modifier.testTag("fresh_ofs_visual_card"),
        trailing = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(verdictBg)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    text = breakdown.verdictLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = verdictFg,
                )
            }
        },
    ) {
        // Proportional visual split bar
        val freshWeight = (breakdown.freshPercent / 100.0).toFloat().coerceIn(0f, 1f)
        val ofsWeight = (1f - freshWeight).coerceIn(0f, 1f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (freshWeight > 0.005f) {
                Box(
                    modifier = Modifier
                        .weight(freshWeight)
                        .fillMaxHeight()
                        .background(marketColors.bullish),
                )
            }
            if (ofsWeight > 0.005f) {
                Box(
                    modifier = Modifier
                        .weight(ofsWeight)
                        .fillMaxHeight()
                        .background(marketColors.smeBadge),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(marketColors.bullish),
                    )
                    Text(
                        text = "Fresh Issue",
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${formatCrores(breakdown.freshCr)} (${String.format(Locale.US, "%.0f%%", breakdown.freshPercent)})",
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.titleSmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = marketColors.bullish,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(marketColors.smeBadge),
                    )
                    Text(
                        text = "Offer for Sale (OFS)",
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${formatCrores(breakdown.ofsCr)} (${String.format(Locale.US, "%.0f%%", breakdown.ofsPercent)})",
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.titleSmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (breakdown.ofsCr > 0.0) marketColors.smeBadge else marketColors.mutedText,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = breakdown.verdictDetail,
            style = MaterialTheme.typography.bodySmall,
            color = marketColors.mutedText,
        )
    }
}

@Composable
fun AllotmentProbabilityCard(
    ipo: IpoEntity,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    var selectedPans by remember(ipo.id) { mutableIntStateOf(1) }
    val result = remember(ipo.id, ipo.retailSubX, ipo.sNiiSubX, ipo.bNiiSubX, selectedPans) {
        AllotmentProbabilityCalculator.calculate(ipo, selectedPans)
    }

    InvestorToolCard(
        title = "Allotment Probability & Multi-PAN Estimator",
        modifier = modifier.testTag("allotment_odds_card"),
        trailing = {
            Icon(
                imageVector = Icons.Outlined.PieChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        },
    ) {
        Text(
            text = "How many Family PAN accounts will you apply from? (1 min Retail lot per PAN)",
            style = MaterialTheme.typography.bodySmall,
            color = marketColors.mutedText,
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1..5).toList()) { pan ->
                val selected = selectedPans == pan
                FilterChip(
                    selected = selected,
                    onClick = { selectedPans = pan },
                    modifier = Modifier.testTag("odds_pan_chip_$pan"),
                    label = {
                        Text(
                            text = if (pan == 1) "1 PAN" else "$pan PANs",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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

        Spacer(modifier = Modifier.height(12.dp))

        result.rows.forEachIndexed { idx, row ->
            val pctColor = when {
                row.probabilityPercent >= 50.0 -> marketColors.bullish
                row.probabilityPercent >= 15.0 -> MaterialTheme.colorScheme.primary
                else -> marketColors.smeBadge
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.categoryLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${row.subtitle} • Capital: ${formatRupees(row.capitalRequired)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = marketColors.mutedText,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = pctColor.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = AllotmentProbabilityCalculator.formatOddsPct(row.probabilityPercent),
                            style = MonoNumericStyle.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = pctColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    val fill = (row.probabilityPercent / 100.0).toFloat().coerceIn(0.02f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fill)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(99.dp))
                            .background(pctColor),
                    )
                }
            }
            if (idx < result.rows.lastIndex) {
                HorizontalDivider(color = glass.hairline, thickness = 0.6.dp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(16.dp),
                )
                Text(
                    text = result.bestStrategyHeadline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun SmartIpoAlertsCard(
    ipo: IpoEntity,
    isDay3ReminderEnabled: Boolean,
    gmpWatchRule: GmpWatchRule?,
    onToggleDay3Reminder: (Boolean) -> Unit,
    onSetGmpWatchRule: (minPercent: Int?, maxPercent: Int?) -> Unit,
    onCompareIpo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val isListed = IpoDateUtils.isCloseDatePassed(
        closeDate = ipo.closeDate,
        status = ipo.status,
        listingDate = ipo.listingDate,
    )

    val highPresets = listOf(15, 25, 40, 60)
    val lowPresets = listOf(0, 5, 10)

    InvestorToolCard(
        title = "Smart Reminders, GMP Watch & Tools",
        modifier = modifier.testTag("smart_ipo_alerts_card"),
        trailing = {
            Icon(
                imageVector = Icons.Outlined.AccessAlarm,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        },
    ) {
        if (!isListed) {
            // Day-3 2:00 PM QIB Closing Reminder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Day-3 Closing Alert (2:00 PM QIB Check)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Reminds you on ${ipo.closeDate} at 2 PM IST with live QIB & Total demand before UPI cutoff.",
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isDay3ReminderEnabled,
                    onCheckedChange = onToggleDay3Reminder,
                    modifier = Modifier.testTag("day3_reminder_switch"),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = glass.hairline, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // GMP Alert Threshold Watch
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gmp_watch_card"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            tint = marketColors.bullish,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "GMP % Alert Thresholds",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (gmpWatchRule?.isActive == true) {
                        TextButton(
                            onClick = { onSetGmpWatchRule(null, null) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("gmp_watch_clear_btn"),
                        ) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Text(
                    text = "Alert me if GMP rises above:",
                    style = MaterialTheme.typography.labelSmall,
                    color = marketColors.mutedText,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(highPresets) { target ->
                        val selected = gmpWatchRule?.maxGmpPercent == target
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val nextMax = if (selected) null else target
                                onSetGmpWatchRule(gmpWatchRule?.minGmpPercent, nextMax)
                            },
                            modifier = Modifier.testTag("gmp_high_chip_$target"),
                            label = {
                                Text(
                                    text = "≥ +$target%",
                                    style = MonoNumericStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = marketColors.bullishContainer,
                                selectedLabelColor = marketColors.bullish,
                            ),
                        )
                    }
                }

                Text(
                    text = "Or warn me if GMP cools down to:",
                    style = MaterialTheme.typography.labelSmall,
                    color = marketColors.mutedText,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lowPresets) { target ->
                        val selected = gmpWatchRule?.minGmpPercent == target
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val nextMin = if (selected) null else target
                                onSetGmpWatchRule(nextMin, gmpWatchRule?.maxGmpPercent)
                            },
                            modifier = Modifier.testTag("gmp_low_chip_$target"),
                            label = {
                                Text(
                                    text = "≤ ${if (target > 0) "+$target%" else "$target%"}",
                                    style = MonoNumericStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = marketColors.bearishContainer,
                                selectedLabelColor = marketColors.bearish,
                            ),
                        )
                    }
                }

                if (gmpWatchRule?.isActive == true) {
                    val activeSummary = buildList {
                        gmpWatchRule.maxGmpPercent?.let { add("Breakout ≥ +$it%") }
                        gmpWatchRule.minGmpPercent?.let { add("Drop ≤ ${if (it > 0) "+$it%" else "$it%"}") }
                    }.joinToString(" • ")
                    Text(
                        text = "Active GMP Watch: $activeSummary",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = marketColors.bullish,
                        modifier = Modifier.testTag("gmp_watch_active_summary"),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = glass.hairline, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Action Row: One-Tap Add to Phone Calendar + Compare with Another IPO
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    val targetDate = if (!isListed) ipo.closeDate else ipo.listingDate
                    val eventTitle = if (!isListed) {
                        "${ipo.companyName} IPO Closes (2 PM QIB Check)"
                    } else {
                        "${ipo.companyName} IPO Listing Day"
                    }
                    val desc = "${ipo.boardType.displayLabel} IPO • Band ₹${ipo.priceLow}–₹${ipo.priceHigh} • Lot ${ipo.lotSize} • Allotment ${ipo.allotmentDate} • Listing ${ipo.listingDate}"
                    launchAddIpoEventToCalendar(
                        context = context,
                        title = eventTitle,
                        dateText = targetDate,
                        description = desc,
                        hourOfDay = if (!isListed) 14 else 10,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("detail_add_to_calendar_btn"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.EventAvailable,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Add to Calendar",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = onCompareIpo,
                modifier = Modifier
                    .weight(1f)
                    .testTag("detail_compare_ipo_btn"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Balance,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Compare IPOs",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ListedPerformanceVsGmpStrip(
    ipo: IpoEntity,
    modifier: Modifier = Modifier,
) {
    val isListed = IpoDateUtils.isListingDateArrived(
        listingDate = ipo.listingDate,
        closeDate = ipo.closeDate,
        status = ipo.status,
    )
    if (!isListed || ipo.priceHigh <= 0) return

    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val isBullish = ipo.gmpRupees >= 0
    val accent = if (isBullish) marketColors.bullish else marketColors.bearish
    val preListingGmp = ipo.gmpHistory.lastOrNull()?.gmpRupees ?: ipo.gmpRupees
    val preListingPct = if (ipo.priceHigh > 0) (preListingGmp.toDouble() / ipo.priceHigh.toDouble()) * 100.0 else 0.0
    val deltaRupees = ipo.gmpRupees - preListingGmp

    Surface(
        shape = MaterialTheme.shapes.large,
        color = glass.cardFill,
        border = glassBorderStroke(glass = glass, width = 1.dp, accentTint = accent),
        modifier = modifier
            .fillMaxWidth()
            .testTag("listed_vs_gmp_strip"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "Listing Gain vs Pre-Listing GMP",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (isBullish) marketColors.bullishContainer else marketColors.bearishContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = formatPercentSigned(ipo.gmpPercent),
                        style = MonoNumericStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                        color = accent,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Issue → Listing Price",
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                    )
                    Text(
                        text = "₹${ipo.priceHigh} → ₹${ipo.estimatedListingPrice}",
                        style = MonoNumericStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = "Last Pre-Listing GMP",
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                    )
                    Text(
                        text = "${formatRupeesSigned(preListingGmp)} (${formatPercentSigned(preListingPct)})",
                        style = MonoNumericStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                }
            }

            Text(
                text = if (deltaRupees == 0) {
                    "Listing debut aligned tightly with final pre-listing GMP trend (${ipo.listingDate})."
                } else {
                    "Listing variance vs tracked GMP: ${formatRupeesSigned(deltaRupees)}/share on ${ipo.listingDate}."
                },
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
            )
        }
    }
}

@Composable
private fun InvestorToolCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlassStyle.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = glass.cardFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = glassBorderStroke(glass = glass, width = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailing()
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

