package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.UsIpoEntity
import com.example.data.model.UsIpoStatus
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.glassPanel
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle

@Composable
fun UsIpoDetailScreen(
    ipo: UsIpoEntity?,
    isLookingUpSecFiling: Boolean,
    onEnsureSecFilingResolved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current

    LaunchedEffect(ipo?.dealId) {
        if (ipo != null && !ipo.hasDirectSecArchiveUrl) {
            onEnsureSecFilingResolved()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("us_ipo_detail_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassCircleIconButton(
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Navigate back to USA IPOs",
                    modifier = Modifier.testTag("us_detail_back_button"),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ipo?.companyName ?: "USA IPO Detail",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ipo?.let { "${it.displayTicker} • ${it.exchange}" } ?: "NASDAQ / NYSE",
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                    )
                }
                if (ipo != null) {
                    GlassCircleIconButton(
                        onClick = { shareUsIpoSummary(context, ipo) },
                        icon = Icons.Outlined.Share,
                        contentDescription = "Share USA IPO details",
                        modifier = Modifier.testTag("us_detail_share_button"),
                    )
                }
            }

            if (ipo == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "USA IPO details not found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Button(onClick = onBack) {
                            Text("Back to USA IPOs")
                        }
                    }
                }
            } else {
                val (statusColor, statusBg) = when (ipo.status) {
                    UsIpoStatus.UPCOMING -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
                    UsIpoStatus.PRICED -> marketColors.bullish to marketColors.bullishContainer
                    UsIpoStatus.FILED -> marketColors.smeBadge to marketColors.smeContainer
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("us_detail_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Hero Summary Card
                    item(key = "hero") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(
                                    glass = glass,
                                    shape = RoundedCornerShape(24.dp),
                                    accentTint = statusColor,
                                )
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(statusBg)
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = ipo.displayTicker,
                                        style = MonoNumericStyle.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = statusColor,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(statusBg)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = ipo.status.label.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = statusColor,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(glass.pillFill)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = ipo.exchange,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Text(
                                text = ipo.companyName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            HorizontalDivider(color = glass.hairline, thickness = 0.8.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = "Total Deal Size (USD)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = marketColors.mutedText,
                                    )
                                    Text(
                                        text = ipo.compactDealSize,
                                        style = MonoNumericStyle.copy(
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = if (ipo.dealSizeNumericUsd > 0.0) {
                                            marketColors.bullish
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.testTag("us_detail_deal_size"),
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = if (ipo.status == UsIpoStatus.PRICED) "Offer Price" else "Price Range",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = marketColors.mutedText,
                                    )
                                    Text(
                                        text = ipo.displayPrice,
                                        style = MonoNumericStyle.copy(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    // 2x2 Deal Metrics Breakdown Grid
                    item(key = "metrics_grid") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Offering Overview",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                UsDetailMetricTile(
                                    label = "Gross Deal Value",
                                    value = ipo.dealSizeUsd.ifBlank { ipo.compactDealSize },
                                    modifier = Modifier.weight(1f),
                                )
                                UsDetailMetricTile(
                                    label = "Shares Offered",
                                    value = ipo.displayShares,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                UsDetailMetricTile(
                                    label = ipo.dateLabel,
                                    value = ipo.displayEventDate,
                                    modifier = Modifier.weight(1f),
                                )
                                UsDetailMetricTile(
                                    label = "Listing Exchange",
                                    value = ipo.exchange,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // SEC S-1 / F-1 Filing & Official Links Card
                    item(key = "sec_filing_card") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(glass = glass, shape = RoundedCornerShape(22.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "SEC Registration Filing",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }

                                if (isLookingUpSecFiling) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else if (ipo.hasDirectSecArchiveUrl) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(99.dp))
                                            .background(marketColors.bullishContainer)
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Verified,
                                            contentDescription = null,
                                            tint = marketColors.bullish,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Text(
                                            text = ipo.secFormType.ifBlank { "S-1" },
                                            style = MonoNumericStyle.copy(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            color = marketColors.bullish,
                                        )
                                    }
                                }
                            }

                            Text(
                                text = if (ipo.hasDirectSecArchiveUrl) {
                                    "Direct SEC EDGAR ${ipo.secFormType.ifBlank { "Form S-1" }} prospectus matched for ${ipo.companyName}. Inspect audited financials, risk factors, underwriters, and use of proceeds directly on sec.gov."
                                } else {
                                    "Open SEC EDGAR filtered to Form S-1 and F-1 registration statements for ${ipo.companyName} to review prospectus disclosures and pricing amendments."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )

                            Button(
                                onClick = { openExternalUrl(context, ipo.resolvedSecFilingUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("us_detail_sec_s1_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (ipo.hasDirectSecArchiveUrl) {
                                        "Open SEC ${ipo.secFormType.ifBlank { "S-1" }} Filing"
                                    } else {
                                        "Search SEC S-1 / F-1 Filings"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { openExternalUrl(context, ipo.resolvedNasdaqUrl) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("us_detail_nasdaq_button"),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Public,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Nasdaq Page",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }

                                OutlinedButton(
                                    onClick = { shareUsIpoSummary(context, ipo) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("us_detail_share_summary_button"),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Share Deal",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    // Timeline & Source Metadata Card
                    item(key = "source_info") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(glass = glass, shape = RoundedCornerShape(20.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CalendarMonth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Calendar & Data Source",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            UsKeyValueRow(label = "Status", value = ipo.status.label)
                            UsKeyValueRow(label = ipo.dateLabel, value = ipo.displayEventDate)
                            UsKeyValueRow(label = "Form Type", value = ipo.secFormType.ifBlank { "S-1 / F-1" })
                            UsKeyValueRow(label = "Public Feed", value = ipo.sourceLabel)
                        }
                    }

                    // Informational Disclaimer
                    item(key = "disclaimer") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(glass.pillFill)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = marketColors.mutedText,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(16.dp),
                            )
                            Text(
                                text = "USA IPO schedules, proposed price ranges, and deal sizes are sourced from public Nasdaq and SEC EDGAR feeds and may change before final pricing. Informational only — not investment advice.",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )
                        }
                    }

                    item(key = "bottom_pad") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UsDetailMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current

    Column(
        modifier = modifier
            .glassPanel(glass = glass, shape = RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsKeyValueRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMarketColors.current.mutedText,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "No browser found to open link", Toast.LENGTH_SHORT).show()
    }
}

private fun shareUsIpoSummary(context: Context, ipo: UsIpoEntity) {
    val text = buildString {
        appendLine("${ipo.companyName} (${ipo.displayTicker}) — USA IPO")
        appendLine("Exchange: ${ipo.exchange} • Status: ${ipo.status.label}")
        appendLine("Price: ${ipo.displayPrice} • Deal Size: ${ipo.compactDealSize}")
        appendLine("Shares Offered: ${ipo.displayShares}")
        appendLine("${ipo.dateLabel}: ${ipo.displayEventDate}")
        append("SEC Filing: ${ipo.resolvedSecFilingUrl}")
    }
    runCatching {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${ipo.companyName} (${ipo.displayTicker}) USA IPO")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share USA IPO"))
    }
}
