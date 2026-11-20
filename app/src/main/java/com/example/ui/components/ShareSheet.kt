package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import java.util.Locale

fun buildIpoShareMessage(ipo: IpoEntity): String = buildString {
    appendLine("${ipo.companyName} (${ipo.exchange}) • ${ipo.status.displayLabel.uppercase(Locale.US)}")
    appendLine("Offer Dates: ${ipo.openDate} – ${ipo.closeDate}")
    appendLine("Allotment: ${ipo.allotmentDate} | Listing: ${ipo.listingDate}")
    val sizeText = if (ipo.issueSizeCr > 0.0) " | Issue Size: ${formatCrores(ipo.issueSizeCr)}" else ""
    appendLine("Price Band: ₹${ipo.priceLow}–₹${ipo.priceHigh} | Lot: ${ipo.lotSize} shares (Min ${formatRupees(ipo.minInvestment)})$sizeText")
    appendLine(
        "Latest GMP: ${formatRupeesSigned(ipo.gmpRupees)} (${formatPercentSigned(ipo.gmpPercent)}) • Est. Listing ${formatRupees(ipo.estimatedListingPrice)}",
    )
    if (ipo.totalSubX > 0.0) {
        appendLine(
            "Subscription: ${formatSubX(ipo.totalSubX)} Total (QIB ${formatSubX(ipo.qibSubX)} | NII ${formatSubX(ipo.niiCombinedSubX)} | Retail ${formatSubX(ipo.retailSubX)})",
        )
    }
    append("Source: ${ipo.gmpSourceLabel} • Tracked on IPO Guruji")
}

fun buildBoardShareMessage(ipos: List<IpoEntity>): String {
    val active = ipos
        .filter { it.status == IpoStatus.LIVE || it.status == IpoStatus.PRE_APPLY }
        .ifEmpty { ipos }
        .take(6)

    return buildString {
        appendLine("IPO Guruji • Latest Indian IPOs & Live GMP")
        appendLine()
        active.forEachIndexed { idx, ipo ->
            val sizeLabel = if (ipo.issueSizeCr > 0.0) " | Size ${formatCrores(ipo.issueSizeCr)}" else ""
            appendLine(
                "${idx + 1}. ${ipo.companyName} [${ipo.status.displayLabel.uppercase(Locale.US)}] • ₹${ipo.priceLow}–₹${ipo.priceHigh}$sizeLabel",
            )
            appendLine(
                "   Dates: ${ipo.openDate} – ${ipo.closeDate} | GMP: ${formatRupeesSigned(ipo.gmpRupees)} (${formatPercentSigned(ipo.gmpPercent)})",
            )
        }
        append("Tracked live via IPO Guruji")
    }
}

@Composable
fun ShareIpoBottomSheet(
    ipo: IpoEntity,
    onDismiss: () -> Unit,
) {
    val shareText = remember(ipo) { buildIpoShareMessage(ipo) }
    ShareSummaryBottomSheet(
        title = "Share IPO Summary",
        subtitle = "Send live GMP, offer dates, price band, and issue details via your social apps.",
        subject = "${ipo.companyName} IPO Dates & Live GMP",
        shareText = shareText,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSummaryBottomSheet(
    title: String,
    subtitle: String,
    subject: String,
    shareText: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val marketColors = LocalMarketColors.current
    var copied by remember(shareText) { mutableStateOf(false) }

    fun launchShareIntent(targetPackage: String? = null) {
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        if (targetPackage != null) {
            val targeted = Intent(baseIntent).apply { setPackage(targetPackage) }
            val launched = runCatching { context.startActivity(targeted) }.isSuccess
            if (launched) {
                onDismiss()
                return
            }
        }
        runCatching {
            context.startActivity(Intent.createChooser(baseIntent, "Share via"))
        }
        onDismiss()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val glass = LocalGlassStyle.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = glass.sheetFill,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("share_ipo_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, marketColors.border),
            ) {
                Text(
                    text = shareText,
                    style = MonoNumericStyle.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(12.dp)
                        .testTag("share_preview_text"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { launchShareIntent("com.whatsapp") },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("share_whatsapp_button"),
                ) {
                    Text("WhatsApp", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { launchShareIntent("org.telegram.messenger") },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("share_telegram_button"),
                ) {
                    Text("Telegram", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { launchShareIntent("com.twitter.android") },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("share_x_button"),
                ) {
                    Text("X (Twitter)", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(shareText))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("share_copy_button"),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy Text",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Button(
                onClick = { launchShareIntent(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("share_system_chooser_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share via Social & Messaging Apps")
            }
        }
    }
}
