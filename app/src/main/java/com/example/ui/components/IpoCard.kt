package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BoardType
import com.example.data.model.GmpPoint
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import java.util.Calendar

@Composable
fun IpoCard(
    ipo: IpoEntity,
    onClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
    isListedTab: Boolean = false,
    isBookmarked: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null,
    compactMode: Boolean = false,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val isListed = isListedTab || IpoDateUtils.isCloseDatePassed(
        closeDate = ipo.closeDate,
        status = ipo.status,
        listingDate = ipo.listingDate,
    )
    val isBullishGmp = ipo.gmpRupees >= 0
    val gmpTint = if (isBullishGmp) marketColors.bullish else marketColors.bearish

    val offerDateLabel = formatOfferDateLine(ipo.openDate, ipo.closeDate)
    val priceBandLabel = if (ipo.priceHigh > 0) {
        if (ipo.priceLow > 0 && ipo.priceLow != ipo.priceHigh) {
            "₹${ipo.priceLow}–₹${ipo.priceHigh}"
        } else {
            "₹${ipo.priceHigh}"
        }
    } else {
        "N/A"
    }
    val lotSizeLabel = if (ipo.lotSize > 0) ipo.lotSize.toString() else "N/A"
    val subscriptionLabel = if (ipo.totalSubX > 0.0) formatSubX(ipo.totalSubX) else "N/A"
    val issueSizeLabel = if (ipo.issueSizeCr > 0.0) formatCrores(ipo.issueSizeCr) else "TBA"
    val gmpValueLabel = "${formatRupeesSigned(ipo.gmpRupees)} (${formatPercentSigned(ipo.gmpPercent)})"
    val urgencyBadgeLabel = remember(ipo.status, ipo.closeDate, isListed) {
        if (!isListed && ipo.status == IpoStatus.LIVE) computeClosingUrgencyLabel(ipo.closeDate) else null
    }

    val accentEdge = when {
        isBookmarked -> marketColors.smeBadge
        ipo.status == IpoStatus.LIVE -> marketColors.bullish
        ipo.status == IpoStatus.PRE_APPLY -> marketColors.smeBadge
        else -> null
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ipo_card_${ipo.id}")
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = glass.cardFill,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = glassBorderStroke(
            glass = glass,
            width = 1.dp,
            accentTint = accentEdge,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (glass.isDark || glass.isGlass) 0.dp else 1.5.dp,
        ),
    ) {
        if (compactMode) {
            CompactIpoCardBody(
                ipo = ipo,
                offerDateLabel = offerDateLabel,
                priceBandLabel = priceBandLabel,
                subscriptionLabel = subscriptionLabel,
                gmpValueLabel = gmpValueLabel,
                gmpTint = gmpTint,
                urgencyBadgeLabel = urgencyBadgeLabel,
                isBookmarked = isBookmarked,
                onToggleBookmark = onToggleBookmark,
            )
            return@Card
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
            ) {
                // Row 1: Crisp Logo Pill on Left + Status, Bookmark & Share Pills on Right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompanyLogo(
                        logoUrl = ipo.logoUrl,
                        companyName = ipo.companyName,
                        shortCode = ipo.shortCode,
                        boardType = ipo.boardType,
                        width = 64.dp,
                        height = 42.dp,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusPill(status = ipo.status)
                        if (onToggleBookmark != null) {
                            Surface(
                                onClick = onToggleBookmark,
                                shape = CircleShape,
                                color = if (isBookmarked) marketColors.smeContainer else glass.pillFill,
                                border = if (isBookmarked) {
                                    BorderStroke(0.9.dp, marketColors.smeBadge.copy(alpha = 0.55f))
                                } else {
                                    glassBorderStroke(glass = glass, width = 0.8.dp)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("bookmark_btn_${ipo.id}"),
                            ) {
                                Box(
                                    modifier = Modifier.padding(7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                                        contentDescription = if (isBookmarked) {
                                            "Remove ${ipo.companyName} from saved watchlist"
                                        } else {
                                            "Save ${ipo.companyName} to watchlist"
                                        },
                                        tint = if (isBookmarked) marketColors.smeBadge else marketColors.mutedText,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        Surface(
                            onClick = onShareClick,
                            shape = RoundedCornerShape(99.dp),
                            color = glass.pillFill,
                            border = glassBorderStroke(glass = glass, width = 0.8.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("share_btn_${ipo.id}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Share,
                                    contentDescription = "Share ${ipo.companyName} IPO",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Share",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 2: Company Name + Board Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = ipo.companyName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BoardPill(boardType = ipo.boardType, exchange = ipo.exchange)
                }

                Spacer(modifier = Modifier.height(5.dp))

                // Offer Date Line + Closing Urgency Countdown Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = marketColors.mutedText,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Offer Date: $offerDateLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = marketColors.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (urgencyBadgeLabel != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(marketColors.liveContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .testTag("urgency_badge_${ipo.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = marketColors.liveAccent,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = urgencyBadgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = marketColors.liveAccent,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    thickness = 0.6.dp,
                    color = glass.hairline,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Row 3: 3-Column Metrics (Offer Price | Lot Size | Subs)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IconMetricCell(
                        icon = Icons.Outlined.CurrencyRupee,
                        label = "Offer Price",
                        value = priceBandLabel,
                        modifier = Modifier.weight(1.1f),
                        horizontalAlignment = Alignment.Start,
                    )
                    IconMetricCell(
                        icon = Icons.Outlined.GridView,
                        label = "Lot Size",
                        value = lotSizeLabel,
                        modifier = Modifier.weight(0.85f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    )
                    IconMetricCell(
                        icon = Icons.Outlined.Groups,
                        label = "Subs",
                        value = subscriptionLabel,
                        valueColor = if (ipo.totalSubX > 0.0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            marketColors.mutedText
                        },
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Row 4: 3-Column Metrics (Exp. Premium | Issue Size | View CTA Pill)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1.22f),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = marketColors.mutedText,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = if (isListed) "Listing Gain" else "Exp. Premium",
                                style = MaterialTheme.typography.labelSmall,
                                color = marketColors.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = gmpValueLabel,
                                style = MonoNumericStyle.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = gmpTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (ipo.gmpHistory.size >= 2) {
                                GmpMiniSparkline(
                                    history = ipo.gmpHistory,
                                    lineColor = gmpTint,
                                    modifier = Modifier
                                        .width(34.dp)
                                        .height(16.dp)
                                        .testTag("gmp_sparkline_${ipo.id}"),
                                )
                            }
                        }
                    }
                    IconMetricCell(
                        icon = Icons.Outlined.AccountBalance,
                        label = "Issue Size",
                        value = issueSizeLabel,
                        modifier = Modifier.weight(0.88f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    )
                    Box(
                        modifier = Modifier.weight(0.95f),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        val isActionHighlight = ipo.status == IpoStatus.LIVE || ipo.status == IpoStatus.PRE_APPLY
                        Surface(
                            onClick = onClick,
                            shape = RoundedCornerShape(99.dp),
                            color = if (isActionHighlight) {
                                marketColors.ctaGold
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            border = if (isActionHighlight) null else BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            modifier = Modifier.testTag("card_cta_${ipo.id}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = if (isActionHighlight) "View IPO" else "Details",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isActionHighlight) {
                                        marketColors.ctaGoldInk
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    maxLines = 1,
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = if (isActionHighlight) {
                                        marketColors.ctaGoldInk
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }

                if (isListed && ipo.priceHigh > 0) {
                    val preListingGmp = ipo.gmpHistory.lastOrNull()?.gmpRupees ?: ipo.gmpRupees
                    val preListingPct = if (ipo.priceHigh > 0) {
                        (preListingGmp.toDouble() / ipo.priceHigh.toDouble()) * 100.0
                    } else {
                        0.0
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isBullishGmp) {
                                    marketColors.bullishContainer.copy(alpha = 0.65f)
                                } else {
                                    marketColors.bearishContainer.copy(alpha = 0.65f)
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .testTag("listed_price_strip_${ipo.id}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Issue ₹${ipo.priceHigh} → Listed ₹${ipo.estimatedListingPrice}",
                            style = MonoNumericStyle.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Pre-GMP ${formatPercentSigned(preListingPct)}",
                            style = MonoNumericStyle.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = gmpTint,
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = glass.hairline)

            // Minimalist Frosted Card Footer Strip: Listing / Listed Date & Min Investment
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (glass.isDark) 0.14f else 0.07f))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isListed) "Listed: ${ipo.listingDate}" else "Listing: ${ipo.listingDate}",
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.labelMedium.fontSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ipo.minInvestment > 0) {
                    Text(
                        text = "Min: ${formatRupees(ipo.minInvestment)}",
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = marketColors.mutedText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatOfferDateLine(openDate: String, closeDate: String): String {
    val open = openDate.trim()
    val close = closeDate.trim()
    if (open.isBlank() || open.equals("TBA", ignoreCase = true) || open.equals("Check RHP", ignoreCase = true)) {
        return "To Be Announced"
    }
    if (close.isBlank() || close.equals("TBA", ignoreCase = true)) {
        return open
    }
    val yearMatch = Regex("\\b(20\\d{2})\\b").find(close)?.value
    val openClean = if (yearMatch != null) open.replace(" $yearMatch", "").trim() else open
    return "$openClean - $close"
}

internal fun computeClosingUrgencyLabel(
    closeDate: String,
    nowMs: Long = System.currentTimeMillis(),
): String? {
    val closeMs = IpoDateUtils.parseFlexibleDateMs(closeDate) ?: return null
    val todayStart = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayDiff = ((closeMs - todayStart) / 86_400_000L).toInt()
    return when (dayDiff) {
        0 -> "Closes Today"
        1 -> "Closes Tomorrow"
        else -> null
    }
}

@Composable
private fun CompactIpoCardBody(
    ipo: IpoEntity,
    offerDateLabel: String,
    priceBandLabel: String,
    subscriptionLabel: String,
    gmpValueLabel: String,
    gmpTint: Color,
    urgencyBadgeLabel: String?,
    isBookmarked: Boolean,
    onToggleBookmark: (() -> Unit)?,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .testTag("compact_ipo_card_${ipo.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CompanyLogo(
            logoUrl = ipo.logoUrl,
            companyName = ipo.companyName,
            shortCode = ipo.shortCode,
            boardType = ipo.boardType,
            width = 48.dp,
            height = 36.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = ipo.companyName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                BoardPill(boardType = ipo.boardType, exchange = ipo.exchange)
            }
            Text(
                text = "$offerDateLabel • $priceBandLabel • Subs $subscriptionLabel",
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (urgencyBadgeLabel != null) {
                Text(
                    text = urgencyBadgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = marketColors.liveAccent,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = gmpValueLabel,
                style = MonoNumericStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                color = gmpTint,
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (ipo.gmpHistory.size >= 2) {
                    GmpMiniSparkline(
                        history = ipo.gmpHistory,
                        lineColor = gmpTint,
                        modifier = Modifier
                            .width(34.dp)
                            .height(14.dp),
                    )
                }
                if (onToggleBookmark != null) {
                    Surface(
                        onClick = onToggleBookmark,
                        shape = CircleShape,
                        color = if (isBookmarked) marketColors.smeContainer else glass.pillFill,
                        modifier = Modifier.testTag("bookmark_btn_${ipo.id}"),
                    ) {
                        Box(modifier = Modifier.padding(4.dp)) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "Toggle bookmark",
                                tint = if (isBookmarked) marketColors.smeBadge else marketColors.mutedText,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GmpMiniSparkline(
    history: List<GmpPoint>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val values = remember(history) { history.takeLast(7).map { it.gmpRupees.toFloat() } }
    if (values.size < 2) return

    Canvas(modifier = modifier) {
        val minV = values.minOrNull() ?: 0f
        val maxV = values.maxOrNull() ?: 0f
        val range = (maxV - minV).coerceAtLeast(1f)
        val padY = 2.dp.toPx()
        val usableH = (size.height - padY * 2f).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()
        var lastPoint = Offset.Zero

        values.forEachIndexed { index, v ->
            val x = index * stepX
            val norm = (v - minV) / range
            val y = size.height - padY - (norm * usableH)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastPoint = Offset(x, y)
        }
        fillPath.lineTo(lastPoint.x, size.height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent),
            ),
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(
            color = lineColor,
            radius = 2.dp.toPx(),
            center = lastPoint,
        )
    }
}

@Composable
private fun IconMetricCell(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    val marketColors = LocalMarketColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = marketColors.mutedText,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MonoNumericStyle.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CompanyMonogram(
    shortCode: String,
    boardType: BoardType,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val gradient = if (boardType == BoardType.MAINBOARD) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
            ),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                marketColors.smeBadge,
                MaterialTheme.colorScheme.primary,
            ),
        )
    }

    Box(
        modifier = modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = shortCode.take(3),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
fun StatusPill(status: IpoStatus) {
    val marketColors = LocalMarketColors.current
    when (status) {
        IpoStatus.LIVE -> {
            val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse_alpha",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(marketColors.liveContainer)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(pulseAlpha)
                        .clip(CircleShape)
                        .background(marketColors.liveAccent),
                )
                Text(
                    text = "OPEN",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = marketColors.liveAccent,
                )
            }
        }

        IpoStatus.PRE_APPLY -> {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(marketColors.smeContainer)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(marketColors.smeBadge),
                )
                Text(
                    text = "PRE-APPLY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = marketColors.smeBadge,
                )
            }
        }

        IpoStatus.UPCOMING -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "UPCOMING",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        IpoStatus.CLOSED -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "CLOSED",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun BoardPill(boardType: BoardType, exchange: String) {
    val marketColors = LocalMarketColors.current
    val bgColor = if (boardType == BoardType.SME) {
        marketColors.smeContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (boardType == BoardType.SME) {
        marketColors.smeBadge
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = exchange,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
    }
}
