package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.firebase.AdminAuthManager
import com.example.data.firebase.AdminAuthSession
import com.example.data.model.IpoOverrideDocument
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.AdminDashboardUiState
import com.example.ui.viewmodel.AdminFilterTab
import com.example.ui.viewmodel.AdminIpoRowItem

@Composable
fun AdminDashboardScreen(
    authSession: AdminAuthSession,
    authErrorMessage: String?,
    isSigningIn: Boolean,
    uiState: AdminDashboardUiState,
    onGoogleSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (AdminFilterTab) -> Unit,
    onRefreshCloudOverrides: () -> Unit,
    onSaveOverride: (IpoOverrideDocument, onSaved: () -> Unit) -> Unit,
    onToggleHideIpo: (AdminIpoRowItem) -> Unit,
    onResetToLiveSync: (overrideId: String, companyName: String, onReset: () -> Unit) -> Unit,
    onClearFeedback: () -> Unit,
    onLoadAdminNote: suspend (overrideId: String) -> String? = { null },
) {
    val marketColors = LocalMarketColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateSheet by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<AdminIpoRowItem?>(null) }
    var pendingResetRow by remember { mutableStateOf<AdminIpoRowItem?>(null) }

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

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("admin_dashboard_screen"),
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
                            modifier = Modifier.testTag("admin_dashboard_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Admin Dashboard",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (authSession.isAuthorizedAdmin) {
                                    "Cloud IPO Override Console"
                                } else {
                                    "Owner Authentication Required"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                                maxLines = 1,
                            )
                        }
                        if (authSession.isAuthorizedAdmin) {
                            IconButton(
                                onClick = onRefreshCloudOverrides,
                                enabled = !uiState.isSyncingCloud,
                                modifier = Modifier.testTag("admin_sync_cloud_button"),
                            ) {
                                if (uiState.isSyncingCloud) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.CloudSync,
                                        contentDescription = "Sync cloud overrides",
                                    )
                                }
                            }
                        }
                        if (authSession.isSignedIn) {
                            IconButton(
                                onClick = onSignOutClick,
                                modifier = Modifier.testTag("admin_sign_out_icon_button"),
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
        floatingActionButton = {
            if (authSession.isAuthorizedAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateSheet = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add IPO", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("admin_add_ipo_fab"),
                )
            }
        },
    ) { innerPadding ->
        when {
            !authSession.isSignedIn -> {
                AdminSignInGate(
                    isSigningIn = isSigningIn,
                    authErrorMessage = authErrorMessage,
                    onGoogleSignInClick = onGoogleSignInClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            !authSession.isAuthorizedAdmin -> {
                AdminUnauthorizedGate(
                    authSession = authSession,
                    onSignOutClick = onSignOutClick,
                    onBack = onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            else -> {
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
                            .testTag("admin_ipo_list"),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 14.dp,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Admin Profile Banner
                        item(key = "admin_profile_banner") {
                            AdminProfileBannerCard(
                                authSession = authSession,
                                uiState = uiState,
                            )
                        }

                        // Search Bar
                        item(key = "admin_search") {
                            OutlinedTextField(
                                value = uiState.searchQuery,
                                onValueChange = onSearchQueryChange,
                                placeholder = { Text("Search IPO name, code, or registrar…") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = {
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { onSearchQueryChange("") }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Clear search",
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("admin_search_input"),
                            )
                        }

                        // Filter Chips
                        item(key = "admin_filter_chips") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AdminFilterTab.entries.forEach { tab ->
                                    val count = when (tab) {
                                        AdminFilterTab.ALL -> uiState.totalCount
                                        AdminFilterTab.OVERRIDDEN -> uiState.overriddenCount
                                        AdminFilterTab.CUSTOM -> uiState.customCount
                                        AdminFilterTab.HIDDEN -> uiState.hiddenCount
                                    }
                                    FilterChip(
                                        selected = uiState.selectedFilter == tab,
                                        onClick = { onSelectFilter(tab) },
                                        label = { Text("${tab.label} ($count)") },
                                        modifier = Modifier.testTag("admin_filter_${tab.name.lowercase()}"),
                                    )
                                }
                            }
                        }

                        if (uiState.rows.isEmpty()) {
                            item(key = "admin_empty_state") {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(0.8.dp, marketColors.border.copy(alpha = 0.7f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "No matching IPO entries",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Try a different filter or tap Add IPO to publish a new entry manually.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = marketColors.mutedText,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        } else {
                            items(
                                items = uiState.rows,
                                key = { it.id },
                            ) { row ->
                                AdminIpoManageCard(
                                    row = row,
                                    isSaving = uiState.isSaving,
                                    onEdit = { editingRow = row },
                                    onToggleHide = { onToggleHideIpo(row) },
                                    onResetRequest = { pendingResetRow = row },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        AdminIpoEditorSheet(
            editingRow = null,
            isSaving = uiState.isSaving,
            onDismiss = { showCreateSheet = false },
            onSave = { doc ->
                onSaveOverride(doc) { showCreateSheet = false }
            },
        )
    }

    editingRow?.let { row ->
        AdminIpoEditorSheet(
            editingRow = row,
            isSaving = uiState.isSaving,
            onDismiss = { editingRow = null },
            onSave = { doc ->
                onSaveOverride(doc) { editingRow = null }
            },
            onResetToLiveSync = { overrideId, name ->
                onResetToLiveSync(overrideId, name) { editingRow = null }
            },
            onLoadAdminNote = onLoadAdminNote,
        )
    }

    pendingResetRow?.let { row ->
        val targetId = row.overrideDoc?.id?.ifBlank { row.id } ?: row.id
        AlertDialog(
            onDismissRequest = { pendingResetRow = null },
            title = {
                Text(
                    text = if (row.isCustom) "Delete Custom IPO?" else "Reset to Live Sync?",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = if (row.isCustom) {
                        "Remove \"${row.effectiveIpo.companyName}\" permanently from the cloud catalog?"
                    } else {
                        "Delete your manual override for \"${row.effectiveIpo.companyName}\" and restore automatic live feeds?"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingResetRow = null
                        onResetToLiveSync(targetId, row.effectiveIpo.companyName) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = marketColors.bearish),
                    modifier = Modifier.testTag("admin_row_confirm_reset"),
                ) {
                    Text(if (row.isCustom) "Delete" else "Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetRow = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AdminSignInGate(
    isSigningIn: Boolean,
    authErrorMessage: String?,
    onGoogleSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    Box(
        modifier = modifier.padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.8.dp, marketColors.border.copy(alpha = 0.8f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .testTag("admin_sign_in_gate"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
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
                        imageVector = Icons.Rounded.AdminPanelSettings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp),
                    )
                }

                Text(
                    text = "Admin Dashboard Sign-In",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = "Sign in with your authorized owner Google account to edit IPO prices, GMP, dates, status, add custom IPOs, or hide invalid listings across all devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                )

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
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Button(
                    onClick = onGoogleSignInClick,
                    enabled = !isSigningIn,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("admin_google_sign_in_button"),
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Signing in…")
                    } else {
                        Text(
                            text = "Sign in with Google",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminUnauthorizedGate(
    authSession: AdminAuthSession,
    onSignOutClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    Box(
        modifier = modifier.padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.8.dp, marketColors.border.copy(alpha = 0.8f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .testTag("admin_unauthorized_gate"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(marketColors.smeContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = marketColors.smeBadge,
                        modifier = Modifier.size(30.dp),
                    )
                }

                Text(
                    text = "Read-Only Viewer Account",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = "Signed in as ${authSession.email.ifBlank { authSession.displayName }}.\n\nAdmin write access is restricted strictly to the verified owner account (${AdminAuthManager.AUTHORIZED_ADMIN_EMAIL}).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("Back to IPOs")
                    }
                    Button(
                        onClick = onSignOutClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("admin_unauthorized_sign_out_button"),
                    ) {
                        Text("Switch Account")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminProfileBannerCard(
    authSession: AdminAuthSession,
    uiState: AdminDashboardUiState,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, marketColors.border.copy(alpha = 0.75f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_profile_banner"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = uiState.adminProfile?.displayName?.ifBlank { authSession.displayName }
                                ?: authSession.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = marketColors.bullishContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = marketColors.bullish,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "ADMIN",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = marketColors.bullish,
                                )
                            }
                        }
                    }
                    Text(
                        text = authSession.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdminKpiPill(
                    label = "Manual Edits",
                    value = uiState.overriddenCount.toString(),
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                AdminKpiPill(
                    label = "Custom IPOs",
                    value = uiState.customCount.toString(),
                    accentColor = marketColors.bullish,
                    modifier = Modifier.weight(1f),
                )
                AdminKpiPill(
                    label = "Hidden",
                    value = uiState.hiddenCount.toString(),
                    accentColor = marketColors.smeBadge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AdminKpiPill(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AdminIpoManageCard(
    row: AdminIpoRowItem,
    isSaving: Boolean,
    onEdit: () -> Unit,
    onToggleHide: () -> Unit,
    onResetRequest: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val ipo = row.effectiveIpo

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (row.isOverridden) 1.dp else 0.8.dp,
            color = when {
                row.isHidden -> marketColors.bearish.copy(alpha = 0.55f)
                row.isOverridden -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else -> marketColors.border.copy(alpha = 0.7f)
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_ipo_card_${row.id}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ipo.companyName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${ipo.boardType.displayLabel} • ${ipo.status.displayLabel} • ${ipo.openDate} → ${ipo.closeDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Origin / Override State Badges
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (row.isHidden) {
                        StatusMiniPill(
                            text = "HIDDEN",
                            bg = marketColors.bearishContainer,
                            fg = marketColors.bearish,
                        )
                    } else if (row.isCustom) {
                        StatusMiniPill(
                            text = "CUSTOM",
                            bg = marketColors.bullishContainer,
                            fg = marketColors.bullish,
                        )
                        StatusMiniPill(
                            text = "SUB LIVE",
                            bg = marketColors.bullishContainer.copy(alpha = 0.7f),
                            fg = marketColors.bullish,
                            modifier = Modifier.testTag("admin_card_sub_live_${row.id}"),
                        )
                    } else if (row.isOverridden) {
                        StatusMiniPill(
                            text = if (row.manualFieldCount > 0) {
                                "MANUAL (${row.manualFieldCount})"
                            } else {
                                "MANUAL"
                            },
                            bg = MaterialTheme.colorScheme.primaryContainer,
                            fg = MaterialTheme.colorScheme.primary,
                        )
                        StatusMiniPill(
                            text = "SUB LIVE",
                            bg = marketColors.bullishContainer,
                            fg = marketColors.bullish,
                            modifier = Modifier.testTag("admin_card_sub_live_${row.id}"),
                        )
                    } else {
                        StatusMiniPill(
                            text = "LIVE SYNC",
                            bg = MaterialTheme.colorScheme.surfaceVariant,
                            fg = marketColors.mutedText,
                        )
                    }
                }
            }

            // Key Metrics Strip
            val priceBandLabel = when {
                ipo.priceLow > 0 && ipo.priceHigh > ipo.priceLow -> "₹${ipo.priceLow}–₹${ipo.priceHigh}"
                ipo.priceHigh > 0 -> "₹${ipo.priceHigh}"
                else -> "TBA"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricMiniColumn(
                    label = "Price Band",
                    value = priceBandLabel,
                )
                MetricMiniColumn(
                    label = "GMP",
                    value = if (ipo.gmpRupees != 0) "₹${ipo.gmpRupees}" else "₹0",
                    valueColor = if (ipo.gmpRupees > 0) marketColors.bullish else MaterialTheme.colorScheme.onSurface,
                )
                MetricMiniColumn(
                    label = "Lot",
                    value = if (ipo.lotSize > 0) "${ipo.lotSize}" else "TBA",
                )
                MetricMiniColumn(
                    label = "Sub",
                    value = if (ipo.totalSubX > 0.0) "${"%.2f".format(ipo.totalSubX)}x" else "—",
                )
            }

            HorizontalDivider(color = marketColors.border.copy(alpha = 0.5f), thickness = 0.7.dp)

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onEdit,
                    enabled = !isSaving,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("admin_edit_button_${row.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onToggleHide,
                    enabled = !isSaving,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("admin_hide_button_${row.id}"),
                ) {
                    Icon(
                        imageVector = if (row.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (row.isHidden) "Unhide" else "Hide",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (row.isOverridden) {
                    TextButton(
                        onClick = onResetRequest,
                        enabled = !isSaving,
                        colors = ButtonDefaults.textButtonColors(contentColor = marketColors.bearish),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("admin_reset_button_${row.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (row.isCustom) "Delete" else "Reset")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMiniPill(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            ),
            color = fg,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun MetricMiniColumn(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val marketColors = LocalMarketColors.current
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = marketColors.mutedText,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
        )
    }
}
