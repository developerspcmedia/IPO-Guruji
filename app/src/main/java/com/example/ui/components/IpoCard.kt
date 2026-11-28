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
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.BookmarkBorder
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
import com.example.ui.theme.InterSans
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
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
    onCompareClick: (() -> Unit)? = null,
    compactMode: Boolean = false,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val isListed = isListedTab || IpoDateUtils.isListingDateArrived(
        listingDate = ipo.listingDate,
        closeDate = ipo.closeDate,
        status = ipo.status,
    )
    val isAllotmentPhase = !isListed && IpoDateUtils.isInAllotmentPhase(
        closeDate = ipo.closeDate,
        listingDate = ipo.listingDate,
        status = ipo.status,
    )
    val isBiddingOpen = !isListed && !isAllotmentPhase && IpoDateUtils.isBiddingOpenToday(
        openDate = ipo.openDate,
        closeDate = ipo.closeDate,
        listingDate = ipo.listingDate,
        status = ipo.status,
    )
    val isBullishGmp = ipo.gmpRupees > 0
    val isBearishGmp = ipo.gmpRupees < 0
    val gmpTint = when {
        isBullishGmp -> marketColors.bullish
        isBearishGmp -> marketColors.bearish
        else -> MaterialTheme.colorScheme.onSurface
    }

    val startDateDisplay = formatSingleCardDate(ipo.openDate)
    val closeDateDisplay = formatSingleCardDate(ipo.closeDate)
    val subscriptionLabel = if (ipo.totalSubX > 0.0) formatSubX(ipo.totalSubX) else "—"
    val issueSizeLabel = if (ipo.issueSizeCr > 0.0) formatCrores(ipo.issueSizeCr) else "TBA"
    val gmpValueLabel = if (ipo.priceHigh == 0 && ipo.gmpRupees == 0) {
        "TBA"
    } else {
        "${formatRupeesSigned(ipo.gmpRupees)} (${formatPercentSigned(ipo.gmpPercent)})"
    }
    val urgencyBadgeLabel = remember(ipo.closeDate, isBiddingOpen) {
        if (isBiddingOpen) computeClosingUrgencyLabel(ipo.closeDate) else null
    }
    val neutralSubtleSurface = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (glass.isDark) 0.42f else 0.55f,
    )

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
            accentTint = null,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (glass.isDark || glass.isGlass) 0.dp else 1.dp,
        ),
    ) {
        if (compactMode) {
            CompactIpoCardBody(
                ipo = ipo,
                startDateLabel = startDateDisplay,
                closeDateLabel = closeDateDisplay,
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
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            ) {
                // Row 1: Logo + Company Name + Board/Status on Left, Quiet Monochrome Actions on Right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CompanyLogo(
                            logoUrl = ipo.logoUrl,
                            companyName = ipo.companyName,
                            shortCode = ipo.shortCode,
                            boardType = ipo.boardType,
                            width = 50.dp,
                            height = 38.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = ipo.companyName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = InterSans,
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.15).sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusPill(
                                    status = ipo.status,
                                    isAllotmentPhase = isAllotmentPhase,
                                    isBiddingOpen = isBiddingOpen,
                                )
                                BoardPill(boardType = ipo.boardType, exchange = ipo.exchange)
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (onToggleBookmark != null) {
                            Surface(
                                onClick = onToggleBookmark,
                                shape = CircleShape,
                                color = if (isBookmarked) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    neutralSubtleSurface
                                },
                                border = BorderStroke(0.8.dp, marketColors.border),
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
                                        tint = if (isBookmarked) {
                                            MaterialTheme.colorScheme.surface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        Surface(
                            onClick = onShareClick,
                            shape = CircleShape,
                            color = neutralSubtleSurface,
                            border = BorderStroke(0.8.dp, marketColors.border),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("share_btn_${ipo.id}"),
                        ) {
                            Box(
                                modifier = Modifier.padding(7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Share,
                                    contentDescription = "Share ${ipo.companyName} IPO",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Row 2: Highlighted Start Date (Green Box) & Close Date (Red Box)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DateHighlightBox(
                        label = "Start Date",
                        dateValue = startDateDisplay,
                        accentColor = marketColors.bullish,
                        containerColor = marketColors.bullishContainer,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_date_box_${ipo.id}"),
                    )
                    DateHighlightBox(
                        label = urgencyBadgeLabel ?: "Close Date",
                        dateValue = closeDateDisplay,
                        accentColor = marketColors.bearish,
                        containerColor = marketColors.bearishContainer,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("close_date_box_${ipo.id}"),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 3: Prominent GMP Highlight + Calm Neutral Secondary Metrics (Subs & Issue Size)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = neutralSubtleSurface,
                    border = BorderStroke(0.8.dp, marketColors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1.35f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = if (isListed) "Listing Gain" else "GMP",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = InterSans,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = marketColors.mutedText,
                                maxLines = 1,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = gmpValueLabel,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = InterSans,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = gmpTint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("gmp_value_${ipo.id}"),
                                )
                                if (ipo.gmpHistory.size >= 2) {
                                    GmpMiniSparkline(
                                        history = ipo.gmpHistory,
                                        lineColor = gmpTint,
                                        modifier = Modifier
                                            .width(32.dp)
                                            .height(15.dp)
                                            .testTag("gmp_sparkline_${ipo.id}"),
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .width(1.dp)
                                .height(28.dp)
                                .background(marketColors.border),
                        )

                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NeutralMetricCell(
                                label = "Subs",
                                value = subscriptionLabel,
                                valueColor = if (ipo.totalSubX > 0.0) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    marketColors.mutedText
                                },
                                horizontalAlignment = Alignment.Start,
                            )
                            NeutralMetricCell(
                                label = "Issue Size",
                                value = issueSizeLabel,
                                horizontalAlignment = Alignment.End,
                            )
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(neutralSubtleSurface)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .testTag("listed_price_strip_${ipo.id}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Issue ₹${ipo.priceHigh} → Listed ₹${ipo.estimatedListingPrice}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = InterSans,
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Pre-GMP ${formatPercentSigned(preListingPct)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = InterSans,
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = gmpTint,
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.6.dp, color = glass.hairline)

            // Calm Monochrome Card Footer Strip: Expected Listing Price + Compare + View Details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = if (glass.isDark) 0.30f else 0.42f,
                        ),
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isListed) {
                    Text(
                        text = "Listed: ${ipo.listingDate}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = InterSans,
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val expectedPriceText = if (ipo.priceHigh > 0) {
                        formatRupees(ipo.estimatedListingPrice)
                    } else {
                        "TBA"
                    }
                    val listingDateShort = formatSingleCardDate(ipo.listingDate)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                            .testTag("expected_listing_price_${ipo.id}"),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = "Expected Listing Price",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = InterSans,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = marketColors.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = expectedPriceText,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = InterSans,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            if (listingDateShort != "TBA") {
                                Text(
                                    text = "• $listingDateShort",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = InterSans,
                                        fontWeight = FontWeight.Medium,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = marketColors.mutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (onCompareClick != null) {
                        Surface(
                            onClick = onCompareClick,
                            shape = RoundedCornerShape(99.dp),
                            color = glass.cardFill,
                            border = BorderStroke(0.9.dp, marketColors.border),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("compare_btn_${ipo.id}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Balance,
                                    contentDescription = "Compare ${ipo.companyName} IPO",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "Compare",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = InterSans,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    val isActionHighlight = isBiddingOpen || ipo.status == IpoStatus.LIVE || ipo.status == IpoStatus.PRE_APPLY
                    Surface(
                        onClick = onClick,
                        shape = RoundedCornerShape(99.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("card_cta_${ipo.id}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = if (isActionHighlight) "View IPO" else "Details",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = InterSans,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.surface,
                                maxLines = 1,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateHighlightBox(
    label: String,
    dateValue: String,
    accentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.34f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = InterSans,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp,
                    ),
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = dateValue,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = InterSans,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NeutralMetricCell(
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
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = InterSans,
                fontWeight = FontWeight.Medium,
            ),
            color = marketColors.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = InterSans,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatSingleCardDate(rawDate: String): String {
    val trimmed = rawDate.trim()
    if (trimmed.isBlank() ||
        trimmed.equals("TBA", ignoreCase = true) ||
        trimmed.equals("Check RHP", ignoreCase = true)
    ) {
        return "TBA"
    }
    return trimmed
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
    startDateLabel: String,
    closeDateLabel: String,
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
            width = 46.dp,
            height = 36.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = ipo.companyName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = InterSans,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                BoardPill(boardType = ipo.boardType, exchange = ipo.exchange)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = marketColors.bullishContainer,
                    border = BorderStroke(0.7.dp, marketColors.bullish.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = startDateLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = InterSans,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = marketColors.bullish,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = marketColors.bearishContainer,
                    border = BorderStroke(0.7.dp, marketColors.bearish.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = closeDateLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = InterSans,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = marketColors.bearish,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (urgencyBadgeLabel != null) {
                Text(
                    text = urgencyBadgeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = InterSans,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = marketColors.bearish,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = gmpValueLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = InterSans,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
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
                        color = if (isBookmarked) MaterialTheme.colorScheme.onSurface else glass.pillFill,
                        modifier = Modifier.testTag("bookmark_btn_${ipo.id}"),
                    ) {
                        Box(modifier = Modifier.padding(4.dp)) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "Toggle bookmark",
                                tint = if (isBookmarked) MaterialTheme.colorScheme.surface else marketColors.mutedText,
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
fun StatusPill(
    status: IpoStatus,
    isAllotmentPhase: Boolean = false,
    isBiddingOpen: Boolean = false,
) {
    val marketColors = LocalMarketColors.current
    if (isAllotmentPhase) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(marketColors.smeContainer)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = "ALLOTMENT",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = InterSans,
                    fontWeight = FontWeight.Bold,
                ),
                color = marketColors.smeBadge,
            )
        }
        return
    }
    val effectiveStatus = if (isBiddingOpen) IpoStatus.LIVE else status
    when (effectiveStatus) {
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
                    .background(marketColors.bullishContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.5.dp)
                        .alpha(pulseAlpha)
                        .clip(CircleShape)
                        .background(marketColors.bullish),
                )
                Text(
                    text = "OPEN",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = InterSans,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = marketColors.bullish,
                )
            }
        }

        IpoStatus.PRE_APPLY -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "PRE-APPLY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = InterSans,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IpoStatus.UPCOMING -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "UPCOMING",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = InterSans,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IpoStatus.CLOSED -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "CLOSED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = InterSans,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun BoardPill(boardType: BoardType, exchange: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = exchange,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = InterSans,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
