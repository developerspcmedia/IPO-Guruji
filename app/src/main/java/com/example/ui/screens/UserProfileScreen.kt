package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.InvestorCategory
import com.example.data.model.IpoEntity
import com.example.data.preferences.ThemePreference
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.UserProfileUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserProfileScreen(
    uiState: UserProfileUiState,
    authErrorMessage: String?,
    isSigningIn: Boolean,
    onBack: () -> Unit,
    onGoogleSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onSaveInvestorDetails: (displayName: String, category: InvestorCategory, bio: String) -> Unit,
    onOpenIpoDetail: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onSelectTheme: (ThemePreference) -> Unit,
    onToggleCompactCards: (Boolean) -> Unit,
    onToggleNotifyNewIpo: (Boolean) -> Unit,
    onToggleNotifyOpenIpo: (Boolean) -> Unit,
    onToggleNotifyAllotment: (Boolean) -> Unit,
    onToggleNotifyNewsAlerts: (Boolean) -> Unit,
    onDeleteAccount: () -> Unit = {},
    externalStatusMessage: String? = null,
    onClearExternalStatusMessage: () -> Unit = {},
    onClearFeedback: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    var draftName by remember(uiState.displayName, uiState.isSignedIn) {
        mutableStateOf(uiState.displayName)
    }
    var draftCategory by remember(uiState.investorCategory, uiState.isSignedIn) {
        mutableStateOf(uiState.investorCategory)
    }
    var draftBio by remember(uiState.bio, uiState.isSignedIn) {
        mutableStateOf(uiState.bio)
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onClearFeedback()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            onClearFeedback()
        }
    }

    LaunchedEffect(externalStatusMessage) {
        val msg = externalStatusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onClearExternalStatusMessage()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("user_profile_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("user_profile_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Investor Profile",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                text = if (uiState.isSignedIn) {
                                    "Personal watchlist & cloud preferences"
                                } else {
                                    "Guest mode • Local watchlist & preferences"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (uiState.isSignedIn) {
                            IconButton(
                                onClick = onSignOutClick,
                                modifier = Modifier.testTag("user_profile_sign_out_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                                    contentDescription = "Sign out",
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.8.dp)
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .testTag("user_profile_content_list"),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 14.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Account Identity / Optional Guest Sign-In Card
                item(key = "profile_identity_header") {
                    ProfileIdentityHeaderCard(
                        uiState = uiState,
                        authErrorMessage = authErrorMessage,
                        isSigningIn = isSigningIn,
                        onGoogleSignInClick = onGoogleSignInClick,
                    )
                }

                // 2. Investor Details Card (Editable when signed in)
                item(key = "investor_details_card") {
                    ProfileSectionCard(
                        title = "Investor Details",
                        icon = Icons.Rounded.Person,
                        testTag = "user_profile_investor_details_card",
                    ) {
                        if (!uiState.isSignedIn) {
                            Text(
                                text = "Sign in with Google above to customize your investor display name, bidding category (Retail / S-HNI / B-HNI / Shareholder), and personal note across devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )
                        } else {
                            OutlinedTextField(
                                value = draftName,
                                onValueChange = { draftName = it },
                                label = { Text("Display Name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("user_profile_display_name_input"),
                            )

                            Text(
                                text = "Investor Category",
                                style = MaterialTheme.typography.labelMedium,
                                color = marketColors.mutedText,
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                InvestorCategory.entries.forEach { category ->
                                    FilterChip(
                                        selected = draftCategory == category,
                                        onClick = { draftCategory = category },
                                        label = {
                                            Text("${category.displayLabel} (${category.description})")
                                        },
                                        modifier = Modifier.testTag("user_category_${category.name.lowercase()}"),
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = draftBio,
                                onValueChange = { if (it.length <= 240) draftBio = it },
                                label = { Text("Investor Note / Strategy (optional)") },
                                placeholder = { Text("e.g. Tracking Mainboard IPOs with strong QIB demand") },
                                minLines = 2,
                                maxLines = 3,
                                supportingText = {
                                    Text("${draftBio.length}/240")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("user_profile_bio_input"),
                            )

                            Button(
                                onClick = {
                                    onSaveInvestorDetails(draftName, draftCategory, draftBio)
                                },
                                enabled = !uiState.isSaving && draftName.trim().isNotEmpty(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("user_profile_save_details_button"),
                            ) {
                                if (uiState.isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Saving…")
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Save Profile", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // 3. Saved Watchlist & Bookmarked IPOs
                item(key = "saved_watchlist_card") {
                    ProfileSectionCard(
                        title = "Saved Watchlist (${uiState.bookmarkedIpos.size})",
                        icon = Icons.Rounded.Bookmarks,
                        testTag = "user_profile_watchlist_card",
                    ) {
                        if (uiState.bookmarkedIpos.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("user_profile_watchlist_empty"),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "No bookmarked IPOs yet",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Tap the bookmark icon on any IPO card to track its GMP, dates, and subscription here.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = marketColors.mutedText,
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.bookmarkedIpos.forEach { ipo ->
                                    WatchlistIpoRow(
                                        ipo = ipo,
                                        onOpen = { onOpenIpoDetail(ipo.id) },
                                        onRemove = { onRemoveBookmark(ipo.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Cloud-Synced Theme & Alert Preferences
                item(key = "synced_preferences_card") {
                    ProfileSectionCard(
                        title = if (uiState.isSignedIn) {
                            "Cloud-Synced Theme & Alerts"
                        } else {
                            "Theme & Alert Preferences"
                        },
                        icon = Icons.Rounded.Palette,
                        testTag = "user_profile_preferences_card",
                    ) {
                        Text(
                            text = "App Appearance",
                            style = MaterialTheme.typography.labelMedium,
                            color = marketColors.mutedText,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ThemePreference.entries.forEach { option ->
                                FilterChip(
                                    selected = uiState.preferences.themePreference == option,
                                    onClick = { onSelectTheme(option) },
                                    label = { Text(option.label) },
                                    modifier = Modifier.testTag("profile_theme_${option.name.lowercase()}"),
                                )
                            }
                        }

                        HorizontalDivider(color = marketColors.border.copy(alpha = 0.55f), thickness = 0.7.dp)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Alerts & Layout",
                                style = MaterialTheme.typography.labelMedium,
                                color = marketColors.mutedText,
                            )
                        }

                        ProfileToggleRow(
                            title = "New IPO Announcements",
                            subtitle = "Alert when newly filed Mainboard or SME IPOs appear",
                            checked = uiState.preferences.notifyNewIpo,
                            onCheckedChange = onToggleNotifyNewIpo,
                            testTag = "profile_toggle_new_ipo",
                        )
                        ProfileToggleRow(
                            title = "IPO Opening Alerts",
                            subtitle = "Reminder when an upcoming IPO opens for bidding",
                            checked = uiState.preferences.notifyOpenIpo,
                            onCheckedChange = onToggleNotifyOpenIpo,
                            testTag = "profile_toggle_open_ipo",
                        )
                        ProfileToggleRow(
                            title = "Allotment Status Alerts",
                            subtitle = "Notifications when allotment results are declared",
                            checked = uiState.preferences.notifyAllotment,
                            onCheckedChange = onToggleNotifyAllotment,
                            testTag = "profile_toggle_allotment",
                        )
                        ProfileToggleRow(
                            title = "Market & GMP News Alerts",
                            subtitle = "Important IPO filings and grey market updates",
                            checked = uiState.preferences.notifyNewsAlerts,
                            onCheckedChange = onToggleNotifyNewsAlerts,
                            testTag = "profile_toggle_news",
                        )
                        ProfileToggleRow(
                            title = "Compact IPO Cards",
                            subtitle = "Show denser cards to scan more IPOs at once",
                            checked = uiState.preferences.compactCards,
                            onCheckedChange = onToggleCompactCards,
                            testTag = "profile_toggle_compact",
                        )
                    }
                }

                // 5. Account & Cloud Data Deletion (Google Play Account Deletion Compliance)
                if (uiState.isSignedIn) {
                    item(key = "account_data_deletion_card") {
                        ProfileSectionCard(
                            title = "Account & Cloud Data",
                            icon = Icons.Rounded.DeleteForever,
                            testTag = "user_profile_account_data_card",
                        ) {
                            Text(
                                text = "Permanently delete your cloud Investor Profile, synced watchlist, and preferences from IPO Guruji servers. Saved PAN profiles stay strictly on your device and can be managed inside Allotment Alerts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )
                            OutlinedButton(
                                onClick = { showDeleteAccountDialog = true },
                                enabled = !uiState.isDeletingAccount,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("user_profile_delete_account_button"),
                            ) {
                                if (uiState.isDeletingAccount) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Deleting…")
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteForever,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Delete Account & Cloud Data", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            modifier = Modifier.testTag("user_profile_delete_account_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = "Delete Account & Cloud Data?",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "This permanently removes your cloud Investor Profile, synced watchlist, and preferences and signs you out. You can continue using IPO Guruji anytime as a guest. On-device PAN profiles are not affected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("user_profile_confirm_delete_button"),
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAccountDialog = false },
                    modifier = Modifier.testTag("user_profile_cancel_delete_button"),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ProfileIdentityHeaderCard(
    uiState: UserProfileUiState,
    authErrorMessage: String?,
    isSigningIn: Boolean,
    onGoogleSignInClick: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val initials = remember(uiState.displayName, uiState.isSignedIn) {
        if (!uiState.isSignedIn) {
            "G"
        } else {
            uiState.displayName.trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "U" }
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, marketColors.border.copy(alpha = 0.8f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("user_profile_identity_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (uiState.isSignedIn) {
                                uiState.displayName.ifBlank { "Investor" }
                            } else {
                                "Guest Investor"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (uiState.isSignedIn) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = marketColors.smeContainer,
                            ) {
                                Text(
                                    text = uiState.investorCategory.badgeLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = marketColors.smeBadge,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Text(
                        text = if (uiState.isSignedIn) {
                            uiState.email
                        } else {
                            "Browsing freely • Sign in anytime to sync across devices"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = if (uiState.isCloudSynced) {
                            marketColors.bullishContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (uiState.isCloudSynced) {
                                    Icons.Rounded.CloudDone
                                } else {
                                    Icons.Rounded.CloudOff
                                },
                                contentDescription = null,
                                tint = if (uiState.isCloudSynced) {
                                    marketColors.bullish
                                } else {
                                    marketColors.mutedText
                                },
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = when {
                                    uiState.isCloudSynced -> "Synced to Cloud"
                                    uiState.isSignedIn -> "Connecting Cloud Sync…"
                                    else -> "Local Guest Profile"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (uiState.isCloudSynced) {
                                    marketColors.bullish
                                } else {
                                    marketColors.mutedText
                                },
                            )
                        }
                    }
                }
            }

            if (!authErrorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = marketColors.bearishContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = authErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.bearish,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (!uiState.isSignedIn) {
                Button(
                    onClick = onGoogleSignInClick,
                    enabled = !isSigningIn,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("user_profile_google_sign_in_button"),
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Signing in…")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sign in with Google to Sync Profile",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchlistIpoRow(
    ipo: IpoEntity,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val priceBand = when {
        ipo.priceLow > 0 && ipo.priceHigh > ipo.priceLow -> "₹${ipo.priceLow}–₹${ipo.priceHigh}"
        ipo.priceHigh > 0 -> "₹${ipo.priceHigh}"
        else -> "TBA"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .testTag("watchlist_ipo_row_${ipo.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ipo.companyName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${ipo.boardType.displayLabel} • ${ipo.status.displayLabel} • $priceBand • GMP ₹${ipo.gmpRupees}",
                    style = MaterialTheme.typography.bodySmall,
                    color = marketColors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("watchlist_remove_${ipo.id}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkRemove,
                    contentDescription = "Remove ${ipo.companyName} from watchlist",
                    tint = marketColors.mutedText,
                    modifier = Modifier.size(20.dp),
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = marketColors.mutedText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ProfileToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val marketColors = LocalMarketColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun ProfileSectionCard(
    title: String,
    icon: ImageVector,
    testTag: String,
    content: @Composable () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, marketColors.border.copy(alpha = 0.75f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            content()
        }
    }
}
