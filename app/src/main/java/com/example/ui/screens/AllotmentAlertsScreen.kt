package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ads.AdBannerSlot
import com.example.ads.BannerAdPlacement
import com.example.ads.NativeAdPlacement
import com.example.ads.NativeAdSlot
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.AllotmentWatchStatus
import com.example.data.model.IpoEntity
import com.example.data.model.PrivateUserProfileEntity
import com.example.ui.components.formatPercentSigned
import com.example.ui.components.formatRupees
import com.example.ui.components.formatRupeesSigned
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import com.example.ui.viewmodel.AllotmentAlertsUiState
import com.example.ui.viewmodel.PortfolioPnlCalculator
import com.example.ui.viewmodel.PortfolioPnlSummary

@Composable
fun AllotmentAlertsScreen(
    uiState: AllotmentAlertsUiState,
    allIpos: List<IpoEntity>,
    preselectedIpoId: String?,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onToggleAutoMonitor: (Boolean) -> Unit,
    onSelectProfileFilter: (String?) -> Unit,
    onSaveProfile: (existingId: String?, name: String, pan: String, dematId: String, upiHandle: String, onSuccess: () -> Unit) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTrackIpo: (ipo: IpoEntity, profileIds: List<String>, lots: Int, appNumber: String, onSuccess: () -> Unit) -> Unit,
    onDeleteWatch: (String) -> Unit,
    onCheckSingleWatch: (String) -> Unit,
    onCheckAllNow: () -> Unit,
    onConfirmManualResult: (watchId: String, status: AllotmentWatchStatus, shares: Int) -> Unit,
    onSendTestNotification: () -> Unit,
    onWipeAllPrivateData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    biometricLockEnabled: Boolean = false,
    isVaultUnlocked: Boolean = true,
    vaultAuthError: String? = null,
    onUnlockVault: () -> Unit = {},
    onLockVaultNow: () -> Unit = {},
    onToggleBiometricLock: (Boolean) -> Unit = {},
    onAdImpression: () -> Unit = {},
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current

    var showProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<PrivateUserProfileEntity?>(null) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var pendingPreselectIpoId by remember(preselectedIpoId) { mutableStateOf(preselectedIpoId) }
    var confirmResultWatch by remember { mutableStateOf<AllotmentWatchEntity?>(null) }
    var showWipeDialog by remember { mutableStateOf(false) }
    var revealedProfileIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // If launched from an IPO Detail page with a preselected IPO and user already has profiles, open the track sheet
    LaunchedEffect(preselectedIpoId, uiState.profiles.size) {
        if (!preselectedIpoId.isNullOrBlank() && uiState.profiles.isNotEmpty() && pendingPreselectIpoId != null) {
            showTrackDialog = true
        }
    }

    val step1Done = hasNotificationPermission && uiState.notifyAllotmentEnabled
    val step2Done = uiState.profiles.isNotEmpty()
    val step3Done = uiState.allWatches.isNotEmpty()
    val completedSteps = listOf(step1Done, step2Done, step3Done).count { it }

    fun copyPanAndOpenRegistrar(watch: AllotmentWatchEntity) {
        val profile = uiState.profiles.firstOrNull { it.id == watch.profileId }
        if (profile != null) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("PAN", profile.panNumber))
            Toast.makeText(
                context,
                "PAN copied locally for ${profile.displayName}. Paste on registrar portal.",
                Toast.LENGTH_SHORT,
            ).show()
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(watch.registrarUrl)))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("allotment_alerts_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            DrawerSubScreenTopBar(
                title = "Instant Allotment Alerts",
                subtitle = "100% Private On-Device • Multi-User Tracker",
                onBack = onBack,
                backButtonTag = "allotment_alerts_back_button",
            )

            if (biometricLockEnabled && !isVaultUnlocked) {
                PrivateVaultLockGate(
                    errorMessage = vaultAuthError,
                    onUnlockClick = onUnlockVault,
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("allotment_alerts_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Privacy Guarantee Vault Banner + Biometric Lock Control
                item(key = "privacy_vault_banner") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = marketColors.bullishContainer.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, marketColors.bullish.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy_vault_banner"),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Shield,
                                    contentDescription = null,
                                    tint = marketColors.bullish,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(22.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = "100% Private & On-Device Only",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = marketColors.bullish,
                                    )
                                    Text(
                                        text = "PAN numbers, family profiles, and IPO applications stay strictly inside your phone's local storage. Zero accounts, zero cloud upload, zero data sharing with anyone.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = if (biometricLockEnabled) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (biometricLockEnabled) {
                                            "Biometric / PIN Lock Active"
                                        } else {
                                            "Enable Fingerprint / PIN Vault Lock"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (biometricLockEnabled) {
                                        OutlinedButton(
                                            onClick = onLockVaultNow,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("lock_vault_now_btn"),
                                        ) {
                                            Text("Lock", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Switch(
                                        checked = biometricLockEnabled,
                                        onCheckedChange = onToggleBiometricLock,
                                        modifier = Modifier.testTag("allotment_biometric_lock_switch"),
                                    )
                                }
                            }
                        }
                    }
                }

                // Private On-Device IPO Portfolio & Listing-Day P&L Tracker (shown at top once user tracks IPOs)
                if (uiState.allWatches.isNotEmpty()) {
                    item(key = "private_portfolio_pnl") {
                        val iposById = remember(allIpos) { allIpos.associateBy { it.id } }
                        val portfolioSummary = remember(uiState.filteredWatches, iposById) {
                            PortfolioPnlCalculator.compute(uiState.filteredWatches, iposById)
                        }
                        val scopeLabel = uiState.profiles
                            .firstOrNull { it.id == uiState.selectedProfileFilterId }
                            ?.displayName
                            ?: "All Private Profiles"
                        PrivatePortfolioPnlCard(
                            summary = portfolioSummary,
                            scopeLabel = scopeLabel,
                        )
                    }
                }

                // Feedback / Error Banner
                if (!uiState.feedbackMessage.isNullOrBlank() || !uiState.errorMessage.isNullOrBlank()) {
                    item(key = "status_feedback") {
                        val isError = !uiState.errorMessage.isNullOrBlank()
                        val msg = uiState.errorMessage ?: uiState.feedbackMessage.orEmpty()
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (isError) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("allotment_feedback_banner"),
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                // 3-Step Guided Setup Card
                item(key = "setup_steps_card") {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, marketColors.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("allotment_setup_steps_card"),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Quick 3-Step Setup",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "$completedSteps of 3 steps complete",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (completedSteps == 3) marketColors.bullish else marketColors.mutedText,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(99.dp),
                                    color = if (completedSteps == 3) {
                                        marketColors.bullishContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                ) {
                                    Text(
                                        text = if (completedSteps == 3) "Active" else "$completedSteps/3",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (completedSteps == 3) {
                                            marketColors.bullish
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            HorizontalDivider(thickness = 0.7.dp, color = marketColors.border.copy(alpha = 0.7f))

                            // Step 1: Notifications + Auto Monitor Toggle
                            SetupStepRow(
                                stepNumber = 1,
                                completed = step1Done,
                                title = "Enable Instant Notifications",
                                subtitle = if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    "Allow Android alerts so you hear immediately when allotment drops"
                                } else {
                                    "Auto-checks every 30 mins in background while pending"
                                },
                                trailing = {
                                    if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        FilledTonalButton(
                                            onClick = onRequestNotificationPermission,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.testTag("step1_allow_notifications_btn"),
                                        ) {
                                            Text("Allow", style = MaterialTheme.typography.labelMedium)
                                        }
                                    } else {
                                        Switch(
                                            checked = uiState.notifyAllotmentEnabled,
                                            onCheckedChange = onToggleAutoMonitor,
                                            modifier = Modifier.testTag("step1_auto_monitor_switch"),
                                        )
                                    }
                                },
                            )

                            // Step 2: Add Private User Profile(s)
                            SetupStepRow(
                                stepNumber = 2,
                                completed = step2Done,
                                title = "Add Private Profile(s)",
                                subtitle = if (step2Done) {
                                    "${uiState.profiles.size} private profile(s) saved locally (supports family members)"
                                } else {
                                    "Save your PAN privately (add multiple users like Self, Spouse, Parents)"
                                },
                                trailing = {
                                    FilledTonalButton(
                                        onClick = {
                                            editingProfile = null
                                            showProfileDialog = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("step2_add_profile_btn"),
                                    ) {
                                        Text(if (step2Done) "Add More" else "Add Profile", style = MaterialTheme.typography.labelMedium)
                                    }
                                },
                            )

                            // Step 3: Track Applied IPO
                            SetupStepRow(
                                stepNumber = 3,
                                completed = step3Done,
                                title = "Track Applied IPO",
                                subtitle = if (step3Done) {
                                    "${uiState.allWatches.size} IPO application(s) tracked (${uiState.pendingCount} active)"
                                } else {
                                    "Pick your applied IPO & profiles to start automatic alerts"
                                },
                                trailing = {
                                    Button(
                                        onClick = {
                                            if (uiState.profiles.isEmpty()) {
                                                editingProfile = null
                                                showProfileDialog = true
                                            } else {
                                                pendingPreselectIpoId = preselectedIpoId
                                                showTrackDialog = true
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("step3_track_ipo_btn"),
                                    ) {
                                        Text("Track IPO", style = MaterialTheme.typography.labelMedium)
                                    }
                                },
                            )
                        }
                    }
                }

                item(key = "allotment_native_ad_2") {
                    NativeAdSlot(
                        placement = NativeAdPlacement.SECONDARY,
                        onAdImpression = onAdImpression,
                        slotTag = "allotment_native_ad",
                    )
                }

                if (uiState.allWatches.isEmpty()) {
                    item(key = "private_portfolio_pnl_empty") {
                        PrivatePortfolioPnlCard(
                            summary = PortfolioPnlSummary(),
                            scopeLabel = "All Private Profiles",
                        )
                    }
                }

                // Private Multi-User Profiles Section
                item(key = "profiles_section_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "PRIVATE PROFILES (${uiState.profiles.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = marketColors.mutedText,
                        )
                        TextButton(
                            onClick = {
                                editingProfile = null
                                showProfileDialog = true
                            },
                            modifier = Modifier.testTag("add_profile_header_btn"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Add User")
                        }
                    }
                }

                if (uiState.profiles.isEmpty()) {
                    item(key = "profiles_empty") {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, marketColors.border),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    text = "No private profiles saved yet",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Add one or multiple private Demat/PAN profiles (e.g., Self, Spouse, Parents) to get instant allotment alerts separately for each person.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.mutedText,
                                )
                            }
                        }
                    }
                } else {
                    items(items = uiState.profiles, key = { "profile_${it.id}" }) { profile ->
                        val isRevealed = profile.id in revealedProfileIds
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, marketColors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profile_card_${profile.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = profile.displayName.take(2).uppercase(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "PAN: ${if (isRevealed) profile.panNumber else profile.maskedPan}",
                                        style = MonoNumericStyle.copy(fontSize = 12.5.sp),
                                        color = marketColors.mutedText,
                                    )
                                    if (profile.dematId.isNotBlank() || profile.upiHandle.isNotBlank()) {
                                        val extra = listOfNotNull(
                                            profile.dematId.takeIf { it.isNotBlank() }?.let { "Demat: $it" },
                                            profile.upiHandle.takeIf { it.isNotBlank() }?.let { "UPI: $it" },
                                        ).joinToString(" • ")
                                        Text(
                                            text = extra,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = marketColors.mutedText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        revealedProfileIds = if (isRevealed) {
                                            revealedProfileIds - profile.id
                                        } else {
                                            revealedProfileIds + profile.id
                                        }
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = if (isRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (isRevealed) "Mask PAN" else "Show PAN",
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editingProfile = profile
                                        showProfileDialog = true
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Edit ${profile.displayName}",
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteProfile(profile.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Delete ${profile.displayName}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Tracked Applications Header + Actions
                item(key = "watches_section_header") {
                    Spacer(modifier = Modifier.height(2.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "TRACKED IPO APPLICATIONS (${uiState.filteredWatches.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = marketColors.mutedText,
                            )
                            TextButton(
                                onClick = {
                                    if (uiState.profiles.isEmpty()) {
                                        editingProfile = null
                                        showProfileDialog = true
                                    } else {
                                        pendingPreselectIpoId = null
                                        showTrackDialog = true
                                    }
                                },
                                modifier = Modifier.testTag("track_new_ipo_btn"),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Track IPO")
                            }
                        }

                        // Action Bar: Check All Now + Test Alert
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onCheckAllNow,
                                enabled = !uiState.isCheckingAll && uiState.allWatches.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("check_all_allotments_btn"),
                            ) {
                                if (uiState.isCheckingAll) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check All Now")
                            }

                            OutlinedButton(
                                onClick = {
                                    if (hasNotificationPermission) {
                                        onSendTestNotification()
                                    } else {
                                        onRequestNotificationPermission()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_allotment_alert_btn"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Alert")
                            }
                        }

                        // Multi-user profile filter pills
                        if (uiState.profiles.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "filter_all_users") {
                                    FilterChip(
                                        selected = uiState.selectedProfileFilterId == null,
                                        onClick = { onSelectProfileFilter(null) },
                                        label = { Text("All Profiles") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                }
                                items(uiState.profiles, key = { "filter_${it.id}" }) { profile ->
                                    FilterChip(
                                        selected = uiState.selectedProfileFilterId == profile.id,
                                        onClick = { onSelectProfileFilter(profile.id) },
                                        label = { Text(profile.displayName) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        ),
                                        modifier = Modifier.testTag("filter_profile_${profile.id}"),
                                    )
                                }
                            }
                        }
                    }
                }

                if (uiState.filteredWatches.isEmpty()) {
                    item(key = "watches_empty") {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, marketColors.border),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "No IPO applications tracked yet",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Tap \"Track IPO\" above (or from any IPO Details page) to receive instant notifications the moment allotment goes live or shares are allotted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.mutedText,
                                )
                            }
                        }
                    }
                } else {
                    items(items = uiState.filteredWatches, key = { "watch_${it.id}" }) { watch ->
                        AllotmentWatchCard(
                            watch = watch,
                            isChecking = uiState.checkingWatchId == watch.id,
                            onCheckNow = { onCheckSingleWatch(watch.id) },
                            onCopyPanAndOpenPortal = { copyPanAndOpenRegistrar(watch) },
                            onUpdateManualStatus = { confirmResultWatch = watch },
                            onDelete = { onDeleteWatch(watch.id) },
                        )
                    }
                }

                // Private Vault Wipe Action
                if (uiState.profiles.isNotEmpty() || uiState.allWatches.isNotEmpty()) {
                    item(key = "wipe_private_vault") {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { showWipeDialog = true },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wipe_private_data_btn"),
                        ) {
                            Text(
                                text = "Delete All Private Profiles & Trackers",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                item(key = "allotment_banner_ad_2") {
                    AdBannerSlot(
                        placement = BannerAdPlacement.SECONDARY,
                        onAdImpression = onAdImpression,
                        roundedCardStyle = true,
                        slotTag = "allotment_banner_ad_2",
                        containerTag = "allotment_banner_ad_2_container",
                    )
                }
            }
        }
    }

    if (showProfileDialog) {
        AddEditProfileDialog(
            existingProfile = editingProfile,
            onDismiss = { showProfileDialog = false },
            onSave = { name, pan, dematId, upiHandle ->
                onSaveProfile(editingProfile?.id, name, pan, dematId, upiHandle) {
                    showProfileDialog = false
                }
            },
        )
    }

    if (showTrackDialog && uiState.profiles.isNotEmpty()) {
        TrackIpoAllotmentDialog(
            ipos = allIpos,
            profiles = uiState.profiles,
            initialIpoId = pendingPreselectIpoId,
            onDismiss = {
                showTrackDialog = false
                pendingPreselectIpoId = null
            },
            onConfirmTrack = { ipo, profileIds, lots, appNo ->
                onTrackIpo(ipo, profileIds, lots, appNo) {
                    showTrackDialog = false
                    pendingPreselectIpoId = null
                }
            },
        )
    }

    confirmResultWatch?.let { target ->
        ConfirmAllotmentResultDialog(
            watch = target,
            onDismiss = { confirmResultWatch = null },
            onConfirm = { status, shares ->
                onConfirmManualResult(target.id, status, shares)
                confirmResultWatch = null
            },
        )
    }

    if (showWipeDialog) {
        WipePrivateDataDialog(
            onDismiss = { showWipeDialog = false },
            onConfirmWipe = {
                showWipeDialog = false
                onWipeAllPrivateData()
            },
        )
    }
}

@Composable
private fun SetupStepRow(
    stepNumber: Int,
    completed: Boolean,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (completed) marketColors.bullish else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (completed) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Step $stepNumber completed",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
            )
        }
        trailing()
    }
}

@Composable
private fun AllotmentWatchCard(
    watch: AllotmentWatchEntity,
    isChecking: Boolean,
    onCheckNow: () -> Unit,
    onCopyPanAndOpenPortal: () -> Unit,
    onUpdateManualStatus: () -> Unit,
    onDelete: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val (badgeBg, badgeColor) = when (watch.status) {
        AllotmentWatchStatus.ALLOTTED -> marketColors.bullishContainer to marketColors.bullish
        AllotmentWatchStatus.RESULT_LIVE -> marketColors.smeContainer to marketColors.smeBadge
        AllotmentWatchStatus.NOT_ALLOTTED -> marketColors.bearishContainer to marketColors.bearish
        AllotmentWatchStatus.PENDING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (watch.status == AllotmentWatchStatus.ALLOTTED) 1.3.dp else 1.dp,
            color = if (watch.status == AllotmentWatchStatus.ALLOTTED) marketColors.bullish.copy(alpha = 0.5f) else marketColors.border,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("allotment_watch_card_${watch.id}"),
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
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = watch.ipoName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${watch.profileName} • PAN ${watch.maskedPan} • ${watch.lotsApplied} Lot(s)",
                        style = MonoNumericStyle.copy(fontSize = 12.sp),
                        color = marketColors.mutedText,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = badgeBg,
                ) {
                    Text(
                        text = if (watch.status == AllotmentWatchStatus.ALLOTTED && watch.sharesAllotted > 0) {
                            "Allotted (${watch.sharesAllotted})"
                        } else {
                            watch.status.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = watch.lastCheckSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Registrar: ${watch.registrarName} • Allotment: ${watch.allotmentDate}",
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
            )

            HorizontalDivider(thickness = 0.7.dp, color = marketColors.border.copy(alpha = 0.7f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onCheckNow,
                    enabled = !isChecking,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("watch_check_btn_${watch.id}"),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Check", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onCopyPanAndOpenPortal,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("watch_portal_btn_${watch.id}"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy PAN & Portal", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }

                IconButton(
                    onClick = onUpdateManualStatus,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("watch_confirm_btn_${watch.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "Update allotment status",
                        tint = marketColors.bullish,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("watch_delete_btn_${watch.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove tracker",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivatePortfolioPnlCard(
    summary: PortfolioPnlSummary,
    scopeLabel: String,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val isProfit = summary.netProfitLossRupees >= 0L
    val pnlAccent = if (isProfit) marketColors.bullish else marketColors.bearish

    Surface(
        shape = MaterialTheme.shapes.large,
        color = glass.cardFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = glassBorderStroke(
            glass = glass,
            width = 1.dp,
            accentTint = if (summary.allottedCount > 0) pnlAccent else null,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("portfolio_pnl_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "My Private IPO Portfolio & Listing P&L",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "$scopeLabel • Computed locally from your tracked IPOs",
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                summary.winRatePercent?.let { winRate ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "Win $winRate%",
                            style = MonoNumericStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PortfolioMetricBox(
                    label = "Blocked UPI",
                    value = formatRupees(summary.blockedUpiCapital.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    sublabel = "${summary.activeBlockedCount} pending",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                PortfolioMetricBox(
                    label = "Allotted Value",
                    value = formatRupees(summary.allottedCostCapital.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    sublabel = "${summary.allottedCount} allotted",
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                PortfolioMetricBox(
                    label = "Est. Listing P&L",
                    value = formatRupeesSigned(summary.netProfitLossRupees.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()),
                    sublabel = if (summary.allottedCostCapital > 0L) formatPercentSigned(summary.netProfitLossPercent) else "0.0%",
                    valueColor = if (summary.allottedCostCapital > 0L) pnlAccent else marketColors.mutedText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PortfolioMetricBox(
    label: String,
    value: String,
    sublabel: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = glass.pillFill,
        border = glassBorderStroke(glass = glass, width = 0.7.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MonoNumericStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = marketColors.mutedText,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PrivateVaultLockGate(
    errorMessage: String?,
    onUnlockClick: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("private_vault_lock_gate"),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = glass.cardFill),
            border = glassBorderStroke(glass = glass, width = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = "Private vault locked",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp),
                    )
                }

                Text(
                    text = "Private Allotment Vault Locked",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = "Your saved PAN profiles, Demat IDs, and private IPO portfolio P&L are protected on-device. Authenticate with Fingerprint, Face Unlock, or Device PIN to unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                )

                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_lock_error_banner"),
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                Button(
                    onClick = onUnlockClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("unlock_vault_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock with Fingerprint / PIN")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = marketColors.bullish,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "Auto-locks whenever the app moves to background",
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                    )
                }
            }
        }
    }
}


