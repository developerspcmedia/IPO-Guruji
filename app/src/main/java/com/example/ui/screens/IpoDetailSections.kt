package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.ui.components.BoardPill
import com.example.ui.components.CompanyBannerImage
import com.example.ui.components.CompanyLogo
import com.example.ui.components.GmpSparklineChart
import com.example.ui.components.StatusPill
import com.example.ui.components.SubscriptionProgressBar
import com.example.ui.components.formatCount
import com.example.ui.components.formatCrores
import com.example.ui.components.formatLongCount
import com.example.ui.components.formatPercentSigned
import com.example.ui.components.formatRupees
import com.example.ui.components.formatRupeesSigned
import com.example.ui.components.formatSubX
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import java.util.Locale

@Composable
private fun DetailSectionCardContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlassStyle.current
    Card(
        modifier = modifier.fillMaxWidth(),
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
        content()
    }
}

@Composable
internal fun DetailHeroSummaryCard(
    ipo: IpoEntity,
    isRefreshingGmp: Boolean,
    onRefreshGmp: () -> Unit,
    onShareIpo: () -> Unit,
    onVerifyOnline: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val offerDateRange = formatDetailOfferDateRange(ipo.openDate, ipo.closeDate)
    val priceBandText = if (ipo.priceHigh > 0) {
        if (ipo.priceLow > 0 && ipo.priceLow != ipo.priceHigh) {
            "${ipo.priceLow}-${ipo.priceHigh}"
        } else {
            "${ipo.priceHigh}"
        }
    } else {
        "TBA"
    }

    DetailSectionCardContainer(
        modifier = Modifier.testTag("detail_hero_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CompanyBannerImage(
                bannerUrl = ipo.bannerImageUrl,
                contentDescriptionName = ipo.companyName,
                headline = ipo.sector,
                subheadline = "${ipo.shortCode} • ${ipo.exchange}",
                height = 76.dp,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Top row: Crisp logo pill on left + Board & Status badges on right
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
                    width = 72.dp,
                    height = 46.dp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoardPill(boardType = ipo.boardType, exchange = ipo.boardType.displayLabel)
                    StatusPill(status = ipo.status)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = ipo.companyName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = marketColors.mutedText,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Offer Date: $offerDateRange",
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.8.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // 2-Column Offer Price & Lot Size Row (matching Screenshot 1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CurrencyRupee,
                            contentDescription = null,
                            tint = marketColors.mutedText,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Offer Price",
                            style = MaterialTheme.typography.labelMedium,
                            color = marketColors.mutedText,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = priceBandText,
                        style = MonoNumericStyle.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(marketColors.border),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.GridView,
                            contentDescription = null,
                            tint = marketColors.mutedText,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Lot Size",
                            style = MaterialTheme.typography.labelMedium,
                            color = marketColors.mutedText,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (ipo.lotSize > 0) "${ipo.lotSize}" else "TBA",
                        style = MonoNumericStyle.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action strip: Refresh GMP | Share | Verify
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onRefreshGmp,
                    enabled = !isRefreshingGmp,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1.35f)
                        .minimumInteractiveComponentSize()
                        .testTag("detail_refresh_gmp_button"),
                ) {
                    if (isRefreshingGmp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh latest GMP",
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isRefreshingGmp) "Syncing" else "Refresh GMP",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = onShareIpo,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("hero_share_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share IPO summary",
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Share",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }

                OutlinedButton(
                    onClick = onVerifyOnline,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("detail_verify_gmp_web_button"),
                ) {
                    Text(
                        text = "Verify",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "Verify live GMP online",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

private data class TimelineStageInfo(
    val shortDate: String,
    val fullDate: String,
    val label: String,
    val tipTitle: String,
    val tipBody: String,
    val calendarHour: Int,
)

@Composable
internal fun IpoScheduleTimelineCard(
    ipo: IpoEntity,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val activeStages = remember(ipo.status, ipo.openDate, ipo.closeDate, ipo.allotmentDate, ipo.listingDate) {
        computeCompletedScheduleStages(ipo)
    }
    val currentStageIdx = (activeStages - 1).coerceIn(0, 3)
    var selectedStageIdx by remember(ipo.id, currentStageIdx) { mutableIntStateOf(currentStageIdx) }

    val stages = remember(ipo.id, ipo.openDate, ipo.closeDate, ipo.allotmentDate, ipo.listingDate, ipo.registrarName) {
        listOf(
            TimelineStageInfo(
                shortDate = stripCurrentYear(ipo.openDate),
                fullDate = ipo.openDate,
                label = "Open",
                tipTitle = "Bidding Opens • 10:00 AM IST",
                tipBody = "Apply via ASBA or UPI mandate at upper cut-off ₹${ipo.priceHigh}. Watch Day-1 retail & NII demand.",
                calendarHour = 10,
            ),
            TimelineStageInfo(
                shortDate = stripCurrentYear(ipo.closeDate),
                fullDate = ipo.closeDate,
                label = "Close",
                tipTitle = "Final Day • 2:00 PM QIB & 5:00 PM UPI Cutoff",
                tipBody = "Check Day-3 QIB subscription at 2:00 PM IST and approve your UPI mandate before 5:00 PM.",
                calendarHour = 14,
            ),
            TimelineStageInfo(
                shortDate = stripCurrentYear(ipo.allotmentDate),
                fullDate = ipo.allotmentDate,
                label = "Allotment*",
                tipTitle = "Basis of Allotment • ${ipo.registrarName}",
                tipBody = "Check allotment status late evening via ${ipo.registrarName}. Unallotted UPI blocks release the next working day.",
                calendarHour = 18,
            ),
            TimelineStageInfo(
                shortDate = stripCurrentYear(ipo.listingDate),
                fullDate = ipo.listingDate,
                label = "Listing*",
                tipTitle = "Exchange Debut • 10:00 AM IST (${ipo.exchange})",
                tipBody = "Pre-open price discovery runs 9:00–9:45 AM; regular trading begins at 10:00 AM on ${ipo.exchange}.",
                calendarHour = 10,
            ),
        )
    }

    val stageStatusPill = when {
        activeStages >= 4 -> "LISTED"
        activeStages == 3 -> "ALLOTMENT PHASE"
        ipo.status == IpoStatus.LIVE -> "OPEN NOW"
        activeStages == 2 -> "CLOSED"
        else -> "UPCOMING"
    }

    val pulseTransition = rememberInfiniteTransition(label = "timeline_stage_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stage_halo_alpha",
    )

    DetailSectionCardContainer(
        modifier = modifier.testTag("ipo_schedule_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "IPO Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = if (activeStages >= 4 || ipo.status == IpoStatus.LIVE) {
                            marketColors.bullishContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    ) {
                        Text(
                            text = stageStatusPill,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (activeStages >= 4 || ipo.status == IpoStatus.LIVE) {
                                marketColors.bullish
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp),
                        )
                    }
                }
                Text(
                    text = "${stripCurrentYear(ipo.openDate)} – ${stripCurrentYear(ipo.listingDate)}",
                    style = MonoNumericStyle.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = marketColors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Interactive Visual Timeline Stepper (Open -> Close -> Allotment* -> Listing*)
            val completedColor = marketColors.bullish
            val currentColor = MaterialTheme.colorScheme.primary
            val pendingTrackColor = MaterialTheme.colorScheme.surfaceVariant

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("visual_timeline_stepper"),
            ) {
                // Connecting horizontal rail behind the milestone nodes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (seg in 0 until 3) {
                        val segDone = (seg + 1) < activeStages
                        val segActive = (seg + 1) == activeStages
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(
                                    when {
                                        segDone -> completedColor
                                        segActive -> currentColor.copy(alpha = 0.65f)
                                        else -> pendingTrackColor
                                    },
                                ),
                        )
                    }
                }

                // 4 Tappable Stage Nodes + Dates + Labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    stages.forEachIndexed { idx, stage ->
                        val isCompleted = idx < activeStages - 1 || activeStages >= 4
                        val isCurrent = !isCompleted && idx == currentStageIdx && activeStages > 0
                        val isSelected = idx == selectedStageIdx

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedStageIdx = idx }
                                .padding(vertical = 2.dp)
                                .testTag("timeline_stage_$idx"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.size(30.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isCurrent || isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .alpha(if (isCurrent) pulseAlpha else 0.3f)
                                            .clip(CircleShape)
                                            .background(if (isCompleted) completedColor else currentColor),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isCompleted -> completedColor
                                                isCurrent -> currentColor
                                                else -> MaterialTheme.colorScheme.surface
                                            },
                                        )
                                        .border(
                                            width = if (isSelected) 2.dp else 1.4.dp,
                                            color = when {
                                                isCompleted -> completedColor
                                                isCurrent || isSelected -> currentColor
                                                else -> marketColors.border
                                            },
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isCompleted) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "${stage.label} completed",
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    } else {
                                        Text(
                                            text = "${idx + 1}",
                                            style = MonoNumericStyle.copy(
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            color = if (isCurrent) Color.White else marketColors.mutedText,
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = stage.shortDate,
                                style = MonoNumericStyle.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected || isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                ),
                                color = if (isCompleted || isCurrent || isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    marketColors.mutedText
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stage.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    isCompleted -> marketColors.bullish
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    else -> marketColors.mutedText
                                },
                                fontWeight = if (isSelected || isCompleted || isCurrent) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Stage Guidance Callout (updates when user taps any stage node)
            AnimatedContent(
                targetState = stages[selectedStageIdx],
                label = "timeline_stage_detail_anim",
            ) { stage ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (glass.isAmoled) 0.85f else 0.55f),
                    border = BorderStroke(0.8.dp, marketColors.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("timeline_stage_detail"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stage.tipTitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = {
                                    launchAddIpoEventToCalendar(
                                        context = context,
                                        title = "${ipo.companyName} IPO ${stage.label.replace("*", "")}",
                                        dateText = stage.fullDate,
                                        description = "${stage.tipTitle} — ${stage.tipBody}",
                                        hourOfDay = stage.calendarHour,
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("timeline_stage_calendar_btn"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.EventAvailable,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Save Date",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(
                            text = stage.tipBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = marketColors.mutedText,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveSubscriptionCard(
    ipo: IpoEntity,
    initiallyExpandedBars: Boolean = false,
    isRefreshingSubscription: Boolean = false,
    subscriptionStatusMessage: String? = null,
    subscriptionSyncLabel: String? = null,
    isAutoPollingActive: Boolean = false,
    onRefreshSubscription: () -> Unit = {},
    onVerifySubscriptionOnline: () -> Unit = {},
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    var showProgressBars by remember { mutableStateOf(initiallyExpandedBars) }
    val maxScale = maxOf(
        ipo.qibSubX,
        ipo.bNiiSubX,
        ipo.sNiiSubX,
        ipo.retailSubX,
        ipo.totalSubX,
        1.0,
    )

    DetailSectionCardContainer(
        modifier = Modifier.testTag("live_subscription_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Live Subscription",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (ipo.status == IpoStatus.LIVE) {
                            Surface(
                                shape = RoundedCornerShape(99.dp),
                                color = marketColors.bullishContainer,
                            ) {
                                Text(
                                    text = if (isAutoPollingActive) "AUTO-LIVE" else "LIVE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = marketColors.bullish,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = "Category-Wise Subscription Status",
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = onRefreshSubscription,
                        enabled = !isRefreshingSubscription,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, marketColors.bullish.copy(alpha = 0.55f)),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_live_subscription_button"),
                    ) {
                        if (isRefreshingSubscription) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color = marketColors.bullish,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh live subscription",
                                tint = marketColors.bullish,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isRefreshingSubscription) "Syncing" else "Refresh",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = marketColors.bullish,
                        )
                    }

                    OutlinedButton(
                        onClick = { showProgressBars = !showProgressBars },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_subscription_bars_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showProgressBars) "Hide" else "Bars",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (!subscriptionStatusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (glass.isAmoled) 0.85f else 0.55f),
                    border = BorderStroke(0.7.dp, marketColors.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_subscription_sync_status"),
                ) {
                    Text(
                        text = subscriptionStatusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f))

            SubscriptionKeyValueRow(
                label = "Date:",
                value = if (!subscriptionSyncLabel.isNullOrBlank() && ipo.updatedAgoLabel.isBlank()) {
                    subscriptionSyncLabel
                } else {
                    ipo.updatedAgoLabel
                },
            )
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.45f), thickness = 0.6.dp)

            SubscriptionKeyValueRow(
                label = "QIB:",
                value = formatSubX(ipo.qibSubX),
            )
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.45f), thickness = 0.6.dp)

            // Multi-line NII breakdown matching Screenshot 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "NII:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(0.36f),
                )
                Column(
                    modifier = Modifier.weight(0.64f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "Bids above ₹10L: ${formatSubX(ipo.bNiiSubX)}",
                        style = MonoNumericStyle.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Bids below ₹10L: ${formatSubX(ipo.sNiiSubX)}",
                        style = MonoNumericStyle.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "NII Total: ${formatSubX(ipo.niiCombinedSubX)}",
                        style = MonoNumericStyle.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.45f), thickness = 0.6.dp)

            SubscriptionKeyValueRow(
                label = "Retail:",
                value = formatSubX(ipo.retailSubX),
            )

            if (ipo.employeeSubX > 0.0) {
                HorizontalDivider(color = marketColors.border.copy(alpha = 0.45f), thickness = 0.6.dp)
                SubscriptionKeyValueRow(
                    label = "Employee:",
                    value = formatSubX(ipo.employeeSubX),
                )
            }

            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f))

            SubscriptionKeyValueRow(
                label = "Total:",
                value = if (ipo.totalSubX > 0.0) {
                    "${String.format(Locale.US, "%.2f", ipo.totalSubX)} times"
                } else {
                    "Yet to Open"
                },
                emphasize = true,
                valueColor = if (ipo.totalSubX >= 1.0) marketColors.bullish else MaterialTheme.colorScheme.onSurface,
            )

            AnimatedVisibility(visible = showProgressBars) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f))
                    SubscriptionProgressBar(
                        label = "QIB (Qualified Institutional Buyers)",
                        multiplier = ipo.qibSubX,
                        maxScale = maxScale,
                        barColor = MaterialTheme.colorScheme.primary,
                    )
                    SubscriptionProgressBar(
                        label = "bNII (Non-Institutional > ₹10L)",
                        multiplier = ipo.bNiiSubX,
                        maxScale = maxScale,
                        barColor = marketColors.smeBadge,
                    )
                    SubscriptionProgressBar(
                        label = "sNII (Non-Institutional ₹2L–₹10L)",
                        multiplier = ipo.sNiiSubX,
                        maxScale = maxScale,
                        barColor = marketColors.smeBadge,
                    )
                    SubscriptionProgressBar(
                        label = "Retail Individual Investors (RII)",
                        multiplier = ipo.retailSubX,
                        maxScale = maxScale,
                        barColor = marketColors.bullish,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.45f), thickness = 0.6.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (ipo.status == IpoStatus.LIVE) {
                        "Auto-syncs on open & during 10 AM–5 PM IST"
                    } else {
                        "Verify official NSE/BSE exchange bidding"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onVerifySubscriptionOnline,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("verify_subscription_exchange_btn"),
                ) {
                    Text(
                        text = "Verify NSE/BSE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "Verify live subscription on exchange",
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionKeyValueRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val marketColors = LocalMarketColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasize) MaterialTheme.colorScheme.onSurface else marketColors.mutedText,
            modifier = Modifier.weight(0.36f),
        )
        Text(
            text = value,
            style = MonoNumericStyle.copy(
                fontSize = if (emphasize) 15.sp else 14.sp,
                fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = valueColor,
            modifier = Modifier.weight(0.64f),
        )
    }
}

@Composable
internal fun ExpectedPremiumCard(
    ipo: IpoEntity,
) {
    val marketColors = LocalMarketColors.current
    val isBullish = ipo.gmpRupees >= 0
    val gmpTint = if (isBullish) marketColors.bullish else marketColors.bearish
    var showChart by remember { mutableStateOf(true) }

    DetailSectionCardContainer(
        modifier = Modifier.testTag("expected_premium_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Expected Premium",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = { showChart = !showChart },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("toggle_gmp_chart_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (showChart) "Hide Chart" else "View Chart",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Exp. Premium*:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(0.38f),
                )
                Row(
                    modifier = Modifier.weight(0.62f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = if (isBullish) {
                            Icons.AutoMirrored.Rounded.TrendingUp
                        } else {
                            Icons.AutoMirrored.Rounded.TrendingDown
                        },
                        contentDescription = null,
                        tint = gmpTint,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "${formatRupeesSigned(ipo.gmpRupees)} (${formatPercentSigned(ipo.gmpPercent)}) / Share",
                        style = MonoNumericStyle.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = gmpTint,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Est. Listing:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(0.38f),
                )
                Text(
                    text = "${formatRupees(ipo.estimatedListingPrice)} (${ipo.gmpSourceLabel})",
                    style = MonoNumericStyle.copy(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.62f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "As Heard On:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(0.38f),
                )
                Text(
                    text = ipo.updatedAgoLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.62f),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "*Expected Premium provided in this app is derived from grey market trends. It is not a guaranteed indicator of the actual listing price and should be interpreted with caution.",
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
                lineHeight = 15.sp,
            )

            AnimatedVisibility(visible = showChart && ipo.gmpHistory.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = "7-DAY GMP TREND",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = marketColors.mutedText,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GmpSparklineChart(
                        points = ipo.gmpHistory,
                        isBullish = isBullish,
                    )
                }
            }
        }
    }
}

@Composable
internal fun IpoDetailsBreakdownCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    val totalShares = ipo.totalIssueShares

    val issuePriceLabel = when {
        ipo.priceHigh <= 0 -> "TBA"
        ipo.priceLow <= 0 || ipo.priceLow == ipo.priceHigh -> "₹${ipo.priceHigh} per share"
        else -> "₹${ipo.priceLow}–₹${ipo.priceHigh} per share"
    }

    DetailSectionCardContainer(
        modifier = Modifier.testTag("ipo_details_breakdown_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "IPO Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f))

            // 2-column Issue Price | Face Value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Issue Price",
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = issuePriceLabel,
                        style = MonoNumericStyle.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .width(1.dp)
                        .height(38.dp)
                        .background(marketColors.border),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Face Value",
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "₹${ipo.faceValue} per equity share",
                        style = MonoNumericStyle.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)

            // Issue Size with Fresh Issue / OFS breakdown
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = "Issue Size",
                    style = MaterialTheme.typography.labelMedium,
                    color = marketColors.mutedText,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (totalShares > 0L) {
                        "${formatLongCount(totalShares)} shares (aggregating up to ${formatCrores(ipo.issueSizeCr)})"
                    } else {
                        formatCrores(ipo.issueSizeCr)
                    },
                    style = MonoNumericStyle.copy(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Fresh Issue: ${formatCrores(ipo.freshIssueCr)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "• Offer for Sale (OFS): ${formatCrores(ipo.ofsCr)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)

            // 2-column Lot Size | Listing At
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lot Size",
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${formatCount(ipo.lotSize)} Shares",
                        style = MonoNumericStyle.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .width(1.dp)
                        .height(38.dp)
                        .background(marketColors.border),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Listing At",
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = ipo.exchange,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (ipo.leadManagers.isNotEmpty()) {
                HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.6.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text(
                        text = "Book Running Lead Managers",
                        style = MaterialTheme.typography.labelMedium,
                        color = marketColors.mutedText,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = ipo.leadManagers.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ApplicationsReservedCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    val breakup = ipo.applicationWiseBreakup
    if (breakup.isEmpty()) return

    val retailCount = breakup.firstOrNull { it.id == "retail" }?.reservedApplications ?: 0L
    val sHniCount = breakup.firstOrNull { it.id == "shni" }?.reservedApplications ?: 0L
    val bHniCount = breakup.firstOrNull { it.id == "bhni" }?.reservedApplications ?: 0L

    DetailSectionCardContainer(
        modifier = Modifier.testTag("applications_reserved_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Applications Reserved",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = "Retail",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "sHNI",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "bHNI",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 2.dp),
            ) {
                Text(
                    text = formatLongCount(retailCount),
                    style = MonoNumericStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatLongCount(sHniCount),
                    style = MonoNumericStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatLongCount(bHniCount),
                    style = MonoNumericStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun ReservationCategoryWiseCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    val rows = ipo.reservationCategoryBreakdown
    if (rows.isEmpty()) return

    DetailSectionCardContainer(
        modifier = Modifier.testTag("reservation_category_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Reservation Category Wise",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(1.35f),
                )
                Text(
                    text = "Shares",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.1f),
                )
                Text(
                    text = "Quota",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.75f),
                )
                Text(
                    text = "Max Apps",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.85f),
                )
            }

            rows.forEachIndexed { idx, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (row.isSubRow) "   ${row.category}" else row.category,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (row.isTotal) FontWeight.Bold else if (row.isSubRow) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (row.isSubRow) marketColors.mutedText else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.35f),
                    )
                    Text(
                        text = formatLongCount(row.sharesOffered),
                        style = MonoNumericStyle.copy(
                            fontSize = 12.sp,
                            fontWeight = if (row.isTotal) FontWeight.Bold else FontWeight.Medium,
                        ),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.1f),
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.1f", row.percentage)}%",
                        style = MonoNumericStyle.copy(
                            fontSize = 12.sp,
                            fontWeight = if (row.isTotal) FontWeight.Bold else FontWeight.Medium,
                        ),
                        textAlign = TextAlign.End,
                        color = if (row.isTotal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.75f),
                    )
                    Text(
                        text = row.maxAllottees?.let { formatLongCount(it) } ?: "—",
                        style = MonoNumericStyle.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        textAlign = TextAlign.End,
                        color = if (row.maxAllottees != null) marketColors.bullish else marketColors.mutedText,
                        modifier = Modifier.weight(0.85f),
                    )
                }
                if (idx < rows.lastIndex) {
                    HorizontalDivider(
                        color = marketColors.border.copy(alpha = if (row.isTotal) 0.8f else 0.45f),
                        thickness = 0.6.dp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun KnowBeforeInvestingCard(ipo: IpoEntity) {
    val marketColors = LocalMarketColors.current
    if (ipo.strengths.isEmpty() && ipo.risks.isEmpty()) return
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Strengths, 1 = Risks

    DetailSectionCardContainer(
        modifier = Modifier.testTag("know_before_investing_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Know Before Investing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 2-Tab Bar (Strengths | Risks) matching Screenshot 4
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Strengths" to 0, "Risks" to 1).forEach { (label, idx) ->
                    val isSelected = selectedTab == idx
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = idx }
                            .testTag("kbi_tab_${label.lowercase()}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else marketColors.mutedText,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(
                                    if (isSelected) {
                                        if (idx == 0) marketColors.ctaGold else marketColors.bearish
                                    } else {
                                        marketColors.border.copy(alpha = 0.5f)
                                    },
                                ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val itemsToShow = if (selectedTab == 0) ipo.strengths else ipo.risks
            val iconTint = if (selectedTab == 0) marketColors.bullish else marketColors.bearish
            val bulletIcon = if (selectedTab == 0) Icons.Outlined.ThumbUp else Icons.Outlined.WarningAmber

            if (itemsToShow.isEmpty()) {
                Text(
                    text = if (selectedTab == 0) {
                        "Key strengths will populate once the RHP analysis is published."
                    } else {
                        "Key risk factors will populate once the RHP analysis is published."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsToShow.forEach { point ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = bulletIcon,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp),
                            )
                            Text(
                                text = point,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RegistrarContactDetailsCard(
    ipo: IpoEntity,
    onOpenAllotmentPortal: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    DetailSectionCardContainer(
        modifier = Modifier.testTag("registrar_contact_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Registrar Contact Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = marketColors.border.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "Name:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(0.30f),
                )
                Text(
                    text = ipo.registrarName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.70f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "Allotment:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    modifier = Modifier.weight(0.30f),
                )
                Text(
                    text = ipo.allotmentDate,
                    style = MonoNumericStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.70f),
                )
            }

            if (ipo.registrarUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "Website:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = marketColors.mutedText,
                        modifier = Modifier.weight(0.30f),
                    )
                    Text(
                        text = ipo.registrarUrl,
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(0.70f)
                            .clickable(onClick = onOpenAllotmentPortal),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onOpenAllotmentPortal,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("check_allotment_button"),
            ) {
                Text("Check Allotment Status", fontWeight = FontWeight.SemiBold)
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

@Composable
internal fun IpoDisclaimerCard() {
    val marketColors = LocalMarketColors.current
    DetailSectionCardContainer(
        modifier = Modifier.testTag("ipo_disclaimer_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Disclaimer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No financial information published within IPO Guruji should be considered advice to buy or sell securities or invest in IPOs. All content is provided strictly for educational and informational purposes.",
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "We are not a SEBI Registered analyst. Please consult a qualified financial advisor and review the official SEBI Red Herring Prospectus (RHP) before making any investment decision.",
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
                lineHeight = 18.sp,
            )
        }
    }
}

private fun formatDetailOfferDateRange(openDate: String, closeDate: String): String {
    val open = openDate.trim()
    val close = closeDate.trim()
    if (open.isBlank() || open.equals("TBA", ignoreCase = true)) return "To Be Announced"
    if (close.isBlank() || close.equals("TBA", ignoreCase = true)) return open
    val yearMatch = Regex("\\b(20\\d{2})\\b").find(close)?.value
    val openClean = if (yearMatch != null) open.replace(" $yearMatch", "").trim() else open
    return "$openClean - $close"
}

private fun stripCurrentYear(raw: String): String {
    return raw.trim().replace(Regex("\\s+20\\d{2}$"), "")
}

internal fun computeCompletedScheduleStages(
    ipo: IpoEntity,
    nowMs: Long = System.currentTimeMillis(),
): Int {
    val today = IpoDateUtils.startOfDayMs(nowMs)
    val openMs = IpoDateUtils.parseFlexibleDateMs(ipo.openDate, nowMs)
    val closeMs = IpoDateUtils.parseFlexibleDateMs(ipo.closeDate, nowMs)
    val allotMs = IpoDateUtils.parseFlexibleDateMs(ipo.allotmentDate, nowMs)
    val listMs = IpoDateUtils.parseFlexibleDateMs(ipo.listingDate, nowMs)

    if (listMs != null && today >= listMs) return 4
    if (allotMs != null && today >= allotMs) return 3
    if (closeMs != null && today > closeMs) return 2
    if (openMs != null && today >= openMs) return 1

    return when (ipo.status) {
        IpoStatus.CLOSED -> 4
        IpoStatus.LIVE -> 1
        IpoStatus.PRE_APPLY -> 1
        IpoStatus.UPCOMING -> 0
    }
}
