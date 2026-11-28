package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Publish
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.BoardType
import com.example.data.model.IpoEntity
import com.example.data.model.IpoOverrideDocument
import com.example.data.model.IpoOverrideMerger
import com.example.data.model.IpoStatus
import com.example.data.repository.AdminOverrideRepository
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.AdminIpoRowItem
import java.util.Locale
import kotlin.math.abs

private data class RegistrarPreset(
    val shortLabel: String,
    val fullName: String,
    val portalUrl: String,
)

private val REGISTRAR_PRESETS = listOf(
    RegistrarPreset(
        shortLabel = "Link Intime",
        fullName = "MUFG Intime India Pvt Ltd (Link Intime)",
        portalUrl = "https://in.mpms.mufg.com/Initial_Offer/public-issues.html",
    ),
    RegistrarPreset(
        shortLabel = "KFinTech",
        fullName = "KFin Technologies Ltd",
        portalUrl = "https://kosmic.kfintech.com/ipostatus/",
    ),
    RegistrarPreset(
        shortLabel = "Bigshare",
        fullName = "Bigshare Services Pvt Ltd",
        portalUrl = "https://ipo.bigshareonline.com/IPO_Status.html",
    ),
    RegistrarPreset(
        shortLabel = "Skyline",
        fullName = "Skyline Financial Services Pvt Ltd",
        portalUrl = "https://www.skylinerta.com/ipo.php",
    ),
    RegistrarPreset(
        shortLabel = "Maashitla",
        fullName = "Maashitla Securities Pvt Ltd",
        portalUrl = "https://maashitla.com/allotment-status/public-issues",
    ),
    RegistrarPreset(
        shortLabel = "Cameo",
        fullName = "Cameo Corporate Services Ltd",
        portalUrl = "https://ipo.cameoindia.com/",
    ),
    RegistrarPreset(
        shortLabel = "Purva",
        fullName = "Purva Sharegistry (India) Pvt Ltd",
        portalUrl = "https://www.purvashare.com/investor-service/ipo-query",
    ),
)

private fun doublesEqual(a: Double?, b: Double?): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    return abs(a - b) < 0.0001
}

private fun formatSubMultiplier(value: Double): String =
    if (value > 0.0) String.format(Locale.US, "%.2fx", value) else "0.00x"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminIpoEditorSheet(
    editingRow: AdminIpoRowItem?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (IpoOverrideDocument) -> Unit,
    onResetToLiveSync: ((overrideId: String, companyName: String) -> Unit)? = null,
    onLoadAdminNote: (suspend (overrideId: String) -> String?)? = null,
) {
    val marketColors = LocalMarketColors.current
    val isCreateNew = editingRow == null
    val initialIpo = editingRow?.effectiveIpo
    val initialOverride = editingRow?.overrideDoc
    val baseLive: IpoEntity? = editingRow?.baseLiveIpo?.takeIf { !editingRow.isCustom }

    // Baseline helpers for diff-only locking against live-synced IPO values
    val basePriceLow = baseLive?.priceLow?.takeIf { it > 0 }
    val basePriceHigh = baseLive?.priceHigh?.takeIf { it > 0 }
    val baseGmp = baseLive?.gmpRupees
    val baseKostak = baseLive?.kostakRupees?.takeIf { it != 0 }
    val baseLotSize = baseLive?.lotSize?.takeIf { it > 0 }
    val baseFaceValue = baseLive?.faceValue
    val baseIssueSize = baseLive?.issueSizeCr?.takeIf { it > 0.0 }
    val baseFreshIssue = baseLive?.freshIssueCr?.takeIf { it > 0.0 }
    val baseOfs = baseLive?.ofsCr?.takeIf { it > 0.0 }

    var companyName by remember(editingRow) { mutableStateOf(initialIpo?.companyName.orEmpty()) }
    var shortCode by remember(editingRow) { mutableStateOf(initialIpo?.shortCode.orEmpty()) }
    var sector by remember(editingRow) { mutableStateOf(initialIpo?.sector.orEmpty()) }
    var exchange by remember(editingRow) { mutableStateOf(initialIpo?.exchange ?: "NSE, BSE") }
    var boardType by remember(editingRow) { mutableStateOf(initialIpo?.boardType ?: BoardType.MAINBOARD) }
    var status by remember(editingRow) { mutableStateOf(initialIpo?.status ?: IpoStatus.UPCOMING) }

    var priceLowText by remember(editingRow) {
        mutableStateOf(initialIpo?.priceLow?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var priceHighText by remember(editingRow) {
        mutableStateOf(initialIpo?.priceHigh?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var gmpText by remember(editingRow) {
        mutableStateOf(initialIpo?.gmpRupees?.toString().orEmpty())
    }
    var kostakText by remember(editingRow) {
        mutableStateOf(initialIpo?.kostakRupees?.takeIf { it != 0 }?.toString().orEmpty())
    }
    var lotSizeText by remember(editingRow) {
        mutableStateOf(initialIpo?.lotSize?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var faceValueText by remember(editingRow) {
        mutableStateOf((initialIpo?.faceValue ?: 10).toString())
    }
    var issueSizeText by remember(editingRow) {
        mutableStateOf(initialIpo?.issueSizeCr?.takeIf { it > 0.0 }?.toString().orEmpty())
    }
    var freshIssueText by remember(editingRow) {
        mutableStateOf(initialIpo?.freshIssueCr?.takeIf { it > 0.0 }?.toString().orEmpty())
    }
    var ofsText by remember(editingRow) {
        mutableStateOf(initialIpo?.ofsCr?.takeIf { it > 0.0 }?.toString().orEmpty())
    }

    var openDate by remember(editingRow) { mutableStateOf(initialIpo?.openDate ?: "TBA") }
    var closeDate by remember(editingRow) { mutableStateOf(initialIpo?.closeDate ?: "TBA") }
    var allotmentDate by remember(editingRow) { mutableStateOf(initialIpo?.allotmentDate ?: "TBA") }
    var listingDate by remember(editingRow) { mutableStateOf(initialIpo?.listingDate ?: "TBA") }

    var registrarName by remember(editingRow) {
        mutableStateOf(initialIpo?.registrarName ?: "MUFG Intime India Pvt Ltd (Link Intime)")
    }
    var registrarUrl by remember(editingRow) {
        mutableStateOf(initialIpo?.registrarUrl ?: "https://in.mpms.mufg.com/Initial_Offer/public-issues.html")
    }

    var overview by remember(editingRow) { mutableStateOf(initialIpo?.overview.orEmpty()) }
    var adminNote by remember(editingRow) { mutableStateOf("") }
    var hasEditedAdminNote by remember(editingRow) { mutableStateOf(false) }
    var hidden by remember(editingRow) { mutableStateOf(initialOverride?.hidden ?: false) }

    LaunchedEffect(editingRow?.id, initialOverride?.id) {
        val overrideId = initialOverride?.id?.ifBlank { editingRow?.id.orEmpty() }
            ?: editingRow?.id.orEmpty()
        if (editingRow != null && overrideId.isNotBlank() && onLoadAdminNote != null) {
            val loadedNote = onLoadAdminNote(overrideId)
            if (!hasEditedAdminNote && !loadedNote.isNullOrBlank()) {
                adminNote = loadedNote
            }
        }
    }

    var validationError by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // Per-field live-diff flags (only meaningful when editing an existing live-synced IPO)
    val isCompanyNameMod = baseLive != null && companyName.trim() != baseLive.companyName.trim()
    val isShortCodeMod = baseLive != null && shortCode.trim() != baseLive.shortCode.trim()
    val isSectorMod = baseLive != null && sector.trim() != baseLive.sector.trim()
    val isExchangeMod = baseLive != null && exchange.trim() != baseLive.exchange.trim()
    val isBoardMod = baseLive != null && boardType != baseLive.boardType
    val isStatusMod = baseLive != null && status != baseLive.status

    val isPriceLowMod = baseLive != null && priceLowText.trim().toIntOrNull() != basePriceLow
    val isPriceHighMod = baseLive != null && priceHighText.trim().toIntOrNull() != basePriceHigh
    val isGmpMod = baseLive != null && gmpText.trim().toIntOrNull() != baseGmp
    val isKostakMod = baseLive != null && kostakText.trim().toIntOrNull() != baseKostak
    val isLotSizeMod = baseLive != null && lotSizeText.trim().toIntOrNull() != baseLotSize
    val isFaceValueMod = baseLive != null && faceValueText.trim().toIntOrNull() != baseFaceValue
    val isIssueSizeMod = baseLive != null && !doublesEqual(issueSizeText.trim().toDoubleOrNull(), baseIssueSize)
    val isFreshIssueMod = baseLive != null && !doublesEqual(freshIssueText.trim().toDoubleOrNull(), baseFreshIssue)
    val isOfsMod = baseLive != null && !doublesEqual(ofsText.trim().toDoubleOrNull(), baseOfs)

    val isOpenDateMod = baseLive != null && openDate.trim() != baseLive.openDate.trim()
    val isCloseDateMod = baseLive != null && closeDate.trim() != baseLive.closeDate.trim()
    val isAllotmentDateMod = baseLive != null && allotmentDate.trim() != baseLive.allotmentDate.trim()
    val isListingDateMod = baseLive != null && listingDate.trim() != baseLive.listingDate.trim()

    val isRegistrarNameMod = baseLive != null && registrarName.trim() != baseLive.registrarName.trim()
    val isRegistrarUrlMod = baseLive != null && registrarUrl.trim() != baseLive.registrarUrl.trim()
    val isOverviewMod = baseLive != null && overview.trim() != baseLive.overview.trim()

    val identityModCount = listOf(
        isCompanyNameMod, isShortCodeMod, isSectorMod, isExchangeMod, isBoardMod, isStatusMod,
    ).count { it }
    val pricingModCount = listOf(
        isPriceLowMod, isPriceHighMod, isGmpMod, isKostakMod,
        isLotSizeMod, isFaceValueMod, isIssueSizeMod, isFreshIssueMod, isOfsMod,
    ).count { it }
    val scheduleModCount = listOf(
        isOpenDateMod, isCloseDateMod, isAllotmentDateMod, isListingDateMod,
    ).count { it }
    val registrarModCount = listOf(isRegistrarNameMod, isRegistrarUrlMod).count { it }
    val overviewModCount = if (isOverviewMod) 1 else 0
    val totalModFieldCount = identityModCount + pricingModCount + scheduleModCount + registrarModCount + overviewModCount

    fun parseIntField(raw: String, label: String): Pair<Int?, String?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null to null
        val parsed = trimmed.toIntOrNull()
        return if (parsed == null) null to "$label must be a whole number." else parsed to null
    }

    fun parseDoubleField(raw: String, label: String): Pair<Double?, String?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null to null
        val parsed = trimmed.toDoubleOrNull()
        return if (parsed == null) null to "$label must be a valid number." else parsed to null
    }

    fun attemptSave() {
        val (priceLow, err1) = parseIntField(priceLowText, "Lower Price")
        val (priceHigh, err2) = parseIntField(priceHighText, "Upper Price")
        val (gmp, err3) = parseIntField(gmpText, "GMP")
        val (kostak, err4) = parseIntField(kostakText, "Kostak")
        val (lotSize, err5) = parseIntField(lotSizeText, "Lot Size")
        val (faceValue, err6) = parseIntField(faceValueText, "Face Value")
        val (issueCr, err7) = parseDoubleField(issueSizeText, "Issue Size")
        val (freshCr, err8) = parseDoubleField(freshIssueText, "Fresh Issue")
        val (ofsCr, err9) = parseDoubleField(ofsText, "OFS Size")

        val parseError = listOfNotNull(
            err1, err2, err3, err4, err5, err6, err7, err8, err9,
        ).firstOrNull()
        if (parseError != null) {
            validationError = parseError
            return
        }

        val cleanName = companyName.trim()
        val rawDocId = when {
            editingRow != null -> editingRow.overrideDoc?.id?.ifBlank { editingRow.id } ?: editingRow.id
            else -> "custom_${IpoOverrideMerger.normalizeCompanyKey(cleanName).take(48)}"
        }
        val safeId = IpoOverrideMerger.sanitizeDocumentId(rawDocId)
        val normalizedKey = IpoOverrideMerger.normalizeCompanyKey(cleanName)

        val doc: IpoOverrideDocument = if (!isCreateNew && baseLive != null) {
            // Validate effective combined price band before saving diff-only payload
            val effectiveLow = priceLow ?: baseLive.priceLow
            val effectiveHigh = priceHigh ?: baseLive.priceHigh
            if (effectiveHigh > 0 && effectiveHigh < effectiveLow) {
                validationError = "Upper price band (₹$effectiveHigh) cannot be lower than lower price band (₹$effectiveLow)."
                return
            }

            // Diff-only override: ONLY fields actually modified from live baseline are saved; rest stay null (LIVE SYNC)
            IpoOverrideDocument(
                id = safeId,
                targetIpoId = safeId,
                normalizedKey = normalizedKey,
                companyName = cleanName,
                published = true,
                customIpo = false,
                hidden = hidden,
                shortCode = shortCode.trim().take(24).takeIf { it.isNotEmpty() && it != baseLive.shortCode.trim() },
                sector = sector.trim().take(80).takeIf { it.isNotEmpty() && it != baseLive.sector.trim() },
                boardType = boardType.name.takeIf { boardType != baseLive.boardType },
                exchange = exchange.trim().take(40).takeIf { it.isNotEmpty() && it != baseLive.exchange.trim() },
                status = status.name.takeIf { status != baseLive.status },
                openDate = openDate.trim().take(40).takeIf { it.isNotEmpty() && it != baseLive.openDate.trim() },
                closeDate = closeDate.trim().take(40).takeIf { it.isNotEmpty() && it != baseLive.closeDate.trim() },
                allotmentDate = allotmentDate.trim().take(40).takeIf { it.isNotEmpty() && it != baseLive.allotmentDate.trim() },
                listingDate = listingDate.trim().take(40).takeIf { it.isNotEmpty() && it != baseLive.listingDate.trim() },
                priceLow = priceLow.takeIf { it != basePriceLow },
                priceHigh = priceHigh.takeIf { it != basePriceHigh },
                faceValue = faceValue.takeIf { it != baseFaceValue },
                lotSize = lotSize.takeIf { it != baseLotSize },
                issueSizeCr = issueCr.takeIf { !doublesEqual(it, baseIssueSize) },
                freshIssueCr = freshCr.takeIf { !doublesEqual(it, baseFreshIssue) },
                ofsCr = ofsCr.takeIf { !doublesEqual(it, baseOfs) },
                gmpRupees = gmp.takeIf { it != baseGmp },
                kostakRupees = kostak.takeIf { it != baseKostak },
                // Subscription fields are ALWAYS null so live sync always manages subscription data
                qibSubX = null,
                bNiiSubX = null,
                sNiiSubX = null,
                retailSubX = null,
                employeeSubX = null,
                totalSubX = null,
                registrarName = registrarName.trim().take(120).takeIf { it.isNotEmpty() && it != baseLive.registrarName.trim() },
                registrarUrl = registrarUrl.trim().take(300).takeIf { it.isNotEmpty() && it != baseLive.registrarUrl.trim() },
                overview = overview.trim().take(2000).takeIf { it.isNotEmpty() && it != baseLive.overview.trim() },
                adminNote = adminNote.trim().take(400).takeIf { it.isNotEmpty() },
            )
        } else {
            // Brand-new custom IPO or override without a live catalog record
            IpoOverrideDocument(
                id = safeId,
                targetIpoId = safeId,
                normalizedKey = normalizedKey,
                companyName = cleanName,
                published = true,
                customIpo = true,
                hidden = hidden,
                shortCode = shortCode.trim().take(24).takeIf { it.isNotEmpty() },
                sector = sector.trim().take(80).takeIf { it.isNotEmpty() },
                boardType = boardType.name,
                exchange = exchange.trim().take(40).takeIf { it.isNotEmpty() },
                status = status.name,
                openDate = openDate.trim().take(40).takeIf { it.isNotEmpty() },
                closeDate = closeDate.trim().take(40).takeIf { it.isNotEmpty() },
                allotmentDate = allotmentDate.trim().take(40).takeIf { it.isNotEmpty() },
                listingDate = listingDate.trim().take(40).takeIf { it.isNotEmpty() },
                priceLow = priceLow,
                priceHigh = priceHigh,
                faceValue = faceValue,
                lotSize = lotSize,
                issueSizeCr = issueCr,
                freshIssueCr = freshCr,
                ofsCr = ofsCr,
                gmpRupees = gmp,
                kostakRupees = kostak,
                qibSubX = null,
                bNiiSubX = null,
                sNiiSubX = null,
                retailSubX = null,
                employeeSubX = null,
                totalSubX = null,
                registrarName = registrarName.trim().take(120).takeIf { it.isNotEmpty() },
                registrarUrl = registrarUrl.trim().take(300).takeIf { it.isNotEmpty() },
                overview = overview.trim().take(2000).takeIf { it.isNotEmpty() },
                adminNote = adminNote.trim().take(400).takeIf { it.isNotEmpty() },
            )
        }

        // If admin reverted every edited field back to live sync on an existing live IPO, cleanly reset instead of saving an empty override
        if (!isCreateNew && baseLive != null && !doc.hasAnyManualChanges(baseLive)) {
            validationError = null
            if (editingRow.isOverridden && onResetToLiveSync != null) {
                onResetToLiveSync(safeId, cleanName)
            } else {
                onDismiss()
            }
            return
        }

        val ruleError = AdminOverrideRepository.validateOverrideAgainstRules(doc)
        if (ruleError != null) {
            validationError = ruleError
            return
        }

        validationError = null
        onSave(doc)
    }

    val headerSubtitle = when {
        isCreateNew -> "Publishes immediately • Subscription auto-syncs live"
        baseLive == null -> "Custom IPO • Subscription auto-syncs live"
        totalModFieldCount == 0 -> "All fields live-synced • Edit any field to override"
        else -> "$totalModFieldCount manual ${if (totalModFieldCount == 1) "field" else "fields"} • Subscription & rest live-synced"
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("admin_ipo_editor_sheet"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding(),
            ) {
                // Header Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { if (!isSaving) onDismiss() },
                                modifier = Modifier.testTag("admin_editor_close_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close IPO editor",
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isCreateNew) "Add Custom IPO" else "Edit IPO Override",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = headerSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (totalModFieldCount > 0 && baseLive != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        marketColors.mutedText
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("admin_editor_header_subtitle"),
                                )
                            }
                        }
                        HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.8.dp)
                    }
                }

                // Scrollable Form Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!validationError.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = marketColors.bearishContainer,
                            border = BorderStroke(1.dp, marketColors.bearish.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_editor_validation_error"),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = marketColors.bearish,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = validationError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.bearish,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    // 1. Identity & Classification
                    EditorSectionCard(
                        title = "Identity & Classification",
                        modifiedCount = if (baseLive != null) identityModCount else null,
                        onResetSection = if (baseLive != null && identityModCount > 0) {
                            {
                                companyName = baseLive.companyName
                                shortCode = baseLive.shortCode
                                sector = baseLive.sector
                                exchange = baseLive.exchange
                                boardType = baseLive.boardType
                                status = baseLive.status
                            }
                        } else {
                            null
                        },
                    ) {
                        OutlinedTextField(
                            value = companyName,
                            onValueChange = { companyName = it },
                            label = { Text("Company Name *") },
                            singleLine = true,
                            trailingIcon = fieldResetIcon(isCompanyNameMod, "Company Name") {
                                if (baseLive != null) companyName = baseLive.companyName
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_field_company_name"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = shortCode,
                                onValueChange = { shortCode = it.uppercase(Locale.US) },
                                label = { Text("Short Code") },
                                singleLine = true,
                                trailingIcon = fieldResetIcon(isShortCodeMod, "Short Code") {
                                    if (baseLive != null) shortCode = baseLive.shortCode
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_short_code"),
                            )
                            OutlinedTextField(
                                value = exchange,
                                onValueChange = { exchange = it },
                                label = { Text("Exchange") },
                                singleLine = true,
                                trailingIcon = fieldResetIcon(isExchangeMod, "Exchange") {
                                    if (baseLive != null) exchange = baseLive.exchange
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_exchange"),
                            )
                        }
                        OutlinedTextField(
                            value = sector,
                            onValueChange = { sector = it },
                            label = { Text("Sector / Industry") },
                            singleLine = true,
                            trailingIcon = fieldResetIcon(isSectorMod, "Sector") {
                                if (baseLive != null) sector = baseLive.sector
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_field_sector"),
                        )

                        Text(
                            text = "Board Type",
                            style = MaterialTheme.typography.labelMedium,
                            color = marketColors.mutedText,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoardType.entries.forEach { option ->
                                FilterChip(
                                    selected = boardType == option,
                                    onClick = {
                                        boardType = option
                                        if (exchange == "NSE, BSE" && option == BoardType.SME) {
                                            exchange = "NSE SME"
                                        } else if (exchange == "NSE SME" && option == BoardType.MAINBOARD) {
                                            exchange = "NSE, BSE"
                                        }
                                    },
                                    label = { Text(option.displayLabel) },
                                    modifier = Modifier.testTag("admin_board_${option.name.lowercase()}"),
                                )
                            }
                        }

                        Text(
                            text = "IPO Status",
                            style = MaterialTheme.typography.labelMedium,
                            color = marketColors.mutedText,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IpoStatus.entries.forEach { option ->
                                FilterChip(
                                    selected = status == option,
                                    onClick = { status = option },
                                    label = { Text(option.displayLabel) },
                                    modifier = Modifier.testTag("admin_status_${option.name.lowercase()}"),
                                )
                            }
                        }
                    }

                    // 2. Pricing, GMP & Lot Size
                    EditorSectionCard(
                        title = "Pricing, GMP & Lot Size",
                        modifiedCount = if (baseLive != null) pricingModCount else null,
                        onResetSection = if (baseLive != null && pricingModCount > 0) {
                            {
                                priceLowText = basePriceLow?.toString().orEmpty()
                                priceHighText = basePriceHigh?.toString().orEmpty()
                                gmpText = baseGmp?.toString().orEmpty()
                                kostakText = baseKostak?.toString().orEmpty()
                                lotSizeText = baseLotSize?.toString().orEmpty()
                                faceValueText = (baseFaceValue ?: 10).toString()
                                issueSizeText = baseIssueSize?.toString().orEmpty()
                                freshIssueText = baseFreshIssue?.toString().orEmpty()
                                ofsText = baseOfs?.toString().orEmpty()
                            }
                        } else {
                            null
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = priceLowText,
                                onValueChange = { priceLowText = it },
                                label = { Text("Price Low (₹)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                trailingIcon = fieldResetIcon(isPriceLowMod, "Lower Price") {
                                    priceLowText = basePriceLow?.toString().orEmpty()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_price_low"),
                            )
                            OutlinedTextField(
                                value = priceHighText,
                                onValueChange = { priceHighText = it },
                                label = { Text("Price High (₹)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                trailingIcon = fieldResetIcon(isPriceHighMod, "Upper Price") {
                                    priceHighText = basePriceHigh?.toString().orEmpty()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_price_high"),
                            )
                        }

                        if (baseLive != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isGmpMod || isKostakMod) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("admin_gmp_sync_banner"),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Sync,
                                            contentDescription = null,
                                            tint = if (isGmpMod || isKostakMod) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                marketColors.bullish
                                            },
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = if (isGmpMod || isKostakMod) {
                                                "GMP Manual • Live feed: ₹${baseLive.gmpRupees}"
                                            } else {
                                                "GMP & Kostak stay Live-Synced unless edited"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isGmpMod || isKostakMod) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                marketColors.mutedText
                                            },
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    if (isGmpMod || isKostakMod) {
                                        TextButton(
                                            onClick = {
                                                gmpText = baseLive.gmpRupees.toString()
                                                kostakText = baseKostak?.toString().orEmpty()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("admin_reset_field_gmp"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Restore,
                                                contentDescription = "Reset GMP to live sync",
                                                modifier = Modifier.size(15.dp),
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Reset GMP", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = gmpText,
                                onValueChange = { gmpText = it },
                                label = { Text("GMP (₹)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_gmp"),
                            )
                            OutlinedTextField(
                                value = kostakText,
                                onValueChange = { kostakText = it },
                                label = { Text("Kostak (₹)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_kostak"),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = lotSizeText,
                                onValueChange = { lotSizeText = it },
                                label = { Text("Lot Size (Shares)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                trailingIcon = fieldResetIcon(isLotSizeMod, "Lot Size") {
                                    lotSizeText = baseLotSize?.toString().orEmpty()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_lot_size"),
                            )
                            OutlinedTextField(
                                value = faceValueText,
                                onValueChange = { faceValueText = it },
                                label = { Text("Face Value (₹)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                trailingIcon = fieldResetIcon(isFaceValueMod, "Face Value") {
                                    faceValueText = (baseFaceValue ?: 10).toString()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_face_value"),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = issueSizeText,
                                onValueChange = { issueSizeText = it },
                                label = { Text("Issue (₹ Cr)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_issue_size"),
                            )
                            OutlinedTextField(
                                value = freshIssueText,
                                onValueChange = { freshIssueText = it },
                                label = { Text("Fresh (Cr)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_fresh_issue"),
                            )
                            OutlinedTextField(
                                value = ofsText,
                                onValueChange = { ofsText = it },
                                label = { Text("OFS (Cr)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_ofs"),
                            )
                        }
                    }

                    // 3. Schedule Dates
                    EditorSectionCard(
                        title = "Schedule Dates",
                        modifiedCount = if (baseLive != null) scheduleModCount else null,
                        onResetSection = if (baseLive != null && scheduleModCount > 0) {
                            {
                                openDate = baseLive.openDate
                                closeDate = baseLive.closeDate
                                allotmentDate = baseLive.allotmentDate
                                listingDate = baseLive.listingDate
                            }
                        } else {
                            null
                        },
                        trailingAction = {
                            TextButton(
                                onClick = {
                                    openDate = "TBA"
                                    closeDate = "TBA"
                                    allotmentDate = "TBA"
                                    listingDate = "TBA"
                                },
                                modifier = Modifier.testTag("admin_set_tba_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Set All TBA")
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = openDate,
                                onValueChange = { openDate = it },
                                label = { Text("Open Date") },
                                placeholder = { Text("18 Nov 2026") },
                                singleLine = true,
                                trailingIcon = fieldResetIcon(isOpenDateMod, "Open Date") {
                                    if (baseLive != null) openDate = baseLive.openDate
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_open_date"),
                            )
                            OutlinedTextField(
                                value = closeDate,
                                onValueChange = { closeDate = it },
                                label = { Text("Close Date") },
                                placeholder = { Text("20 Nov 2026") },
                                singleLine = true,
                                trailingIcon = fieldResetIcon(isCloseDateMod, "Close Date") {
                                    if (baseLive != null) closeDate = baseLive.closeDate
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_close_date"),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = allotmentDate,
                                onValueChange = { allotmentDate = it },
                                label = { Text("Allotment Date") },
                                placeholder = { Text("21 Nov 2026") },
                                singleLine = true,
                                trailingIcon = fieldResetIcon(isAllotmentDateMod, "Allotment Date") {
                                    if (baseLive != null) allotmentDate = baseLive.allotmentDate
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_allotment_date"),
                            )
                            OutlinedTextField(
                                value = listingDate,
                                onValueChange = { listingDate = it },
                                label = { Text("Listing Date") },
                                placeholder = { Text("25 Nov 2026") },
                                singleLine = true,
                                trailingIcon = fieldResetIcon(isListingDateMod, "Listing Date") {
                                    if (baseLive != null) listingDate = baseLive.listingDate
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_field_listing_date"),
                            )
                        }
                    }

                    // 4. Live Subscription (Always Auto-Synced — Read-Only Preview Card)
                    LiveSubscriptionReadOnlyCard(
                        liveSource = baseLive ?: initialIpo,
                        isCustomNew = isCreateNew || baseLive == null,
                    )

                    // 5. Registrar & Allotment Portal
                    EditorSectionCard(
                        title = "Registrar & Allotment Portal",
                        modifiedCount = if (baseLive != null) registrarModCount else null,
                        onResetSection = if (baseLive != null && registrarModCount > 0) {
                            {
                                registrarName = baseLive.registrarName
                                registrarUrl = baseLive.registrarUrl
                            }
                        } else {
                            null
                        },
                    ) {
                        Text(
                            text = "Quick Registrar Presets",
                            style = MaterialTheme.typography.labelMedium,
                            color = marketColors.mutedText,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            REGISTRAR_PRESETS.forEach { preset ->
                                val selected = registrarName.contains(preset.shortLabel, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        registrarName = preset.fullName
                                        registrarUrl = preset.portalUrl
                                    },
                                    label = { Text(preset.shortLabel) },
                                    colors = FilterChipDefaults.filterChipColors(),
                                )
                            }
                        }

                        OutlinedTextField(
                            value = registrarName,
                            onValueChange = { registrarName = it },
                            label = { Text("Registrar Name") },
                            singleLine = true,
                            trailingIcon = fieldResetIcon(isRegistrarNameMod, "Registrar Name") {
                                if (baseLive != null) registrarName = baseLive.registrarName
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_field_registrar_name"),
                        )
                        OutlinedTextField(
                            value = registrarUrl,
                            onValueChange = { registrarUrl = it },
                            label = { Text("Registrar Portal URL (https://)") },
                            singleLine = true,
                            trailingIcon = fieldResetIcon(isRegistrarUrlMod, "Registrar URL") {
                                if (baseLive != null) registrarUrl = baseLive.registrarUrl
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_field_registrar_url"),
                        )
                    }

                    // 6. Overview, Visibility & Admin Note
                    EditorSectionCard(
                        title = "Overview, Visibility & Audit Note",
                        modifiedCount = if (baseLive != null) overviewModCount else null,
                        onResetSection = if (baseLive != null && isOverviewMod) {
                            { overview = baseLive.overview }
                        } else {
                            null
                        },
                    ) {
                        OutlinedTextField(
                            value = overview,
                            onValueChange = { overview = it },
                            label = { Text("Company Overview") },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_field_overview"),
                        )
                        OutlinedTextField(
                            value = adminNote,
                            onValueChange = {
                                hasEditedAdminNote = true
                                adminNote = it
                            },
                            label = { Text("Internal Admin Note (optional)") },
                            placeholder = { Text("e.g. Fixed price band from NSE RHP") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_field_admin_note"),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hide from Public IPO List",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Suppresses invalid or duplicate entries across all devices",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.mutedText,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = hidden,
                                onCheckedChange = { hidden = it },
                                modifier = Modifier.testTag("admin_switch_hidden"),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Sticky Bottom Action Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.8.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (editingRow?.isOverridden == true && onResetToLiveSync != null) {
                                OutlinedButton(
                                    onClick = { showResetConfirm = true },
                                    enabled = !isSaving,
                                    border = BorderStroke(1.dp, marketColors.bearish.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = marketColors.bearish,
                                    ),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("admin_editor_reset_button"),
                                ) {
                                    Icon(
                                        imageVector = if (editingRow.isCustom) Icons.Rounded.DeleteOutline else Icons.Rounded.Restore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (editingRow.isCustom) "Delete" else "Reset All")
                                }
                            }

                            Button(
                                onClick = ::attemptSave,
                                enabled = !isSaving,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("admin_editor_save_button"),
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Publishing…")
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Publish,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            isCreateNew -> "Publish Custom IPO"
                                            baseLive != null && totalModFieldCount > 0 ->
                                                "Save $totalModFieldCount ${if (totalModFieldCount == 1) "Edit" else "Edits"}"
                                            else -> "Save & Publish"
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirm && editingRow != null && onResetToLiveSync != null) {
        val targetId = editingRow.overrideDoc?.id?.ifBlank { editingRow.id } ?: editingRow.id
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = {
                Text(
                    text = if (editingRow.isCustom) "Delete Custom IPO?" else "Reset All Edits to Live Sync?",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = if (editingRow.isCustom) {
                        "This removes \"${editingRow.effectiveIpo.companyName}\" from the cloud database for all users."
                    } else {
                        "This deletes all manual overrides for \"${editingRow.effectiveIpo.companyName}\" so automatic live feeds resume updating every field."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        onResetToLiveSync(targetId, editingRow.effectiveIpo.companyName)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = marketColors.bearish,
                    ),
                    modifier = Modifier.testTag("admin_confirm_reset_button"),
                ) {
                    Text(if (editingRow.isCustom) "Delete IPO" else "Reset to Live")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun fieldResetIcon(
    isModified: Boolean,
    fieldLabel: String,
    onReset: () -> Unit,
): (@Composable () -> Unit)? {
    if (!isModified) return null
    return {
        IconButton(onClick = onReset) {
            Icon(
                imageVector = Icons.Rounded.Restore,
                contentDescription = "Reset $fieldLabel to live value",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LiveSubscriptionReadOnlyCard(
    liveSource: IpoEntity?,
    isCustomNew: Boolean,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, marketColors.bullish.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_live_subscription_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Live Subscription (Auto-Synced)",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = marketColors.bullishContainer,
                    modifier = Modifier.testTag("admin_subscription_always_live_badge"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = marketColors.bullish,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "ALWAYS LIVE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                            ),
                            color = marketColors.bullish,
                        )
                    }
                }
            }

            // 2x3 Read-Only Live Subscription Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubscriptionReadOnlyCell(
                    label = "Total",
                    value = formatSubMultiplier(liveSource?.totalSubX ?: 0.0),
                    highlight = (liveSource?.totalSubX ?: 0.0) > 0.0,
                    modifier = Modifier.weight(1f),
                )
                SubscriptionReadOnlyCell(
                    label = "QIB",
                    value = formatSubMultiplier(liveSource?.qibSubX ?: 0.0),
                    modifier = Modifier.weight(1f),
                )
                SubscriptionReadOnlyCell(
                    label = "Retail",
                    value = formatSubMultiplier(liveSource?.retailSubX ?: 0.0),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubscriptionReadOnlyCell(
                    label = "bNII",
                    value = formatSubMultiplier(liveSource?.bNiiSubX ?: 0.0),
                    modifier = Modifier.weight(1f),
                )
                SubscriptionReadOnlyCell(
                    label = "sNII",
                    value = formatSubMultiplier(liveSource?.sNiiSubX ?: 0.0),
                    modifier = Modifier.weight(1f),
                )
                SubscriptionReadOnlyCell(
                    label = "Employee",
                    value = formatSubMultiplier(liveSource?.employeeSubX ?: 0.0),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = if (isCustomNew) {
                    "Subscription starts at 0.00x and updates automatically from live exchange feeds as soon as bidding opens."
                } else {
                    "Live exchange polling always manages subscription data. Manual edits to other IPO details never lock subscription."
                },
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
            )
        }
    }
}

@Composable
private fun SubscriptionReadOnlyCell(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) {
            marketColors.bullishContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlight) marketColors.bullish else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EditorSectionCard(
    title: String,
    modifiedCount: Int? = null,
    onResetSection: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val hasManualEdits = (modifiedCount ?: 0) > 0

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (hasManualEdits) 1.dp else 0.8.dp,
            color = if (hasManualEdits) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                marketColors.border.copy(alpha = 0.7f)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (modifiedCount != null) {
                        EditorSectionSyncPill(
                            text = if (modifiedCount > 0) "MANUAL ($modifiedCount)" else "LIVE SYNC",
                            bg = if (modifiedCount > 0) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            fg = if (modifiedCount > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                marketColors.mutedText
                            },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onResetSection != null) {
                        TextButton(
                            onClick = onResetSection,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Restore,
                                contentDescription = "Reset $title to live sync",
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    trailingAction?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
private fun EditorSectionSyncPill(
    text: String,
    bg: Color,
    fg: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.35.sp,
            ),
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
