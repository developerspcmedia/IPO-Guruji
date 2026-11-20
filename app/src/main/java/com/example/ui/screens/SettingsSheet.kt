package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.ThemePreference
import com.example.security.BiometricVaultLock
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    currentTheme: ThemePreference,
    adImpressionCount: Int,
    onSelectTheme: (ThemePreference) -> Unit,
    onDismiss: () -> Unit,
    notifyNewIpoEnabled: Boolean = true,
    notifyOpenIpoEnabled: Boolean = true,
    notifyAllotmentEnabled: Boolean = true,
    notifyNewsAlertsEnabled: Boolean = true,
    hasNotificationPermission: Boolean = true,
    onToggleNotifyNewIpo: (Boolean) -> Unit = {},
    onToggleNotifyOpenIpo: (Boolean) -> Unit = {},
    onToggleNotifyAllotment: (Boolean) -> Unit = {},
    onToggleNotifyNewsAlerts: (Boolean) -> Unit = {},
    onOpenAllotmentAlerts: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onSendTestNotification: () -> Unit = {},
    compactCardsEnabled: Boolean = false,
    biometricLockEnabled: Boolean = false,
    onToggleCompactCards: (Boolean) -> Unit = {},
    onToggleBiometricLock: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val biometricCapability = remember(context) { BiometricVaultLock.checkCapability(context) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = glass.sheetFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Preferences & Alerts",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Appearance Theme Picker: System, Light, AMOLED Dark, iOS Transparent Glass
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "APPEARANCE",
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 0.9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = marketColors.mutedText,
                    )
                    Text(
                        text = currentTheme.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                val rows = ThemePreference.entries.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { pref ->
                                ThemeOptionCard(
                                    preference = pref,
                                    selected = currentTheme == pref,
                                    onClick = { onSelectTheme(pref) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // Reader Layout & Private Vault Security Card
            Surface(
                shape = MaterialTheme.shapes.large,
                color = glass.cardFill,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = glassBorderStroke(glass = glass, width = 1.dp),
                modifier = Modifier.testTag("reader_security_settings_card"),
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
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ViewStream,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Column {
                                Text(
                                    text = "Compact IPO Feed Cards",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Higher-density list view to scan more IPOs per screen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.mutedText,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = compactCardsEnabled,
                            onCheckedChange = onToggleCompactCards,
                            modifier = Modifier.testTag("switch_compact_cards"),
                        )
                    }

                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.7.dp)

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
                            Icon(
                                imageVector = Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                tint = marketColors.bullish,
                                modifier = Modifier.size(21.dp),
                            )
                            Column {
                                Text(
                                    text = "Biometric / PIN Vault Lock",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Protect private PAN profiles & P&L in Allotment Alerts • ${biometricCapability.statusMessage}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = marketColors.mutedText,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = biometricLockEnabled,
                            onCheckedChange = onToggleBiometricLock,
                            modifier = Modifier.testTag("switch_biometric_lock"),
                        )
                    }
                }
            }

            // Local IPO Notifications Card
            Surface(
                shape = MaterialTheme.shapes.large,
                color = glass.cardFill,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = glassBorderStroke(glass = glass, width = 1.dp),
                modifier = Modifier.testTag("notification_settings_card"),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Local IPO Alerts",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            OutlinedButton(
                                onClick = onRequestNotificationPermission,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("grant_notification_permission_button"),
                            ) {
                                Text("Allow Alerts", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onSendTestNotification,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("test_notification_button"),
                            ) {
                                Text("Test Alert", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New IPO Added",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Notify when a new Mainboard or SME IPO is listed",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = notifyNewIpoEnabled,
                            onCheckedChange = onToggleNotifyNewIpo,
                            modifier = Modifier.testTag("switch_notify_new_ipo"),
                        )
                    }

                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.7.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "IPO Opens for Subscription",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Alert when an upcoming IPO turns OPEN",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = notifyOpenIpoEnabled,
                            onCheckedChange = onToggleNotifyOpenIpo,
                            modifier = Modifier.testTag("switch_notify_open_ipo"),
                        )
                    }

                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.7.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Instant IPO Allotment Alerts",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Private on-device notifications when tracked IPOs are allotted",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = notifyAllotmentEnabled,
                            onCheckedChange = onToggleNotifyAllotment,
                            modifier = Modifier.testTag("switch_notify_allotment"),
                        )
                    }

                    HorizontalDivider(color = marketColors.border.copy(alpha = 0.7f), thickness = 0.7.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Breaking IPO News Alerts",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Notify when breaking IPO news, GMP buzz, or SEBI updates arrive",
                                style = MaterialTheme.typography.bodySmall,
                                color = marketColors.mutedText,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = notifyNewsAlertsEnabled,
                            onCheckedChange = onToggleNotifyNewsAlerts,
                            modifier = Modifier.testTag("switch_notify_news"),
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenAllotmentAlerts,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_manage_allotment_alerts_btn"),
                    ) {
                        Text("Manage Private Allotment Profiles & Alerts")
                    }
                }
            }

            // Disclaimer
            Text(
                text = "Disclaimer: IPO Guruji provides educational IPO market tracking, Grey Market Premium estimates, and restated RHP financial summaries. GMP figures are unofficial market indicators. Always consult a SEBI-registered advisor before investing.",
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
            )
        }
    }
}

@Composable
private fun ThemeOptionCard(
    preference: ThemePreference,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current
    val containerColor = when {
        selected && glass.isAmoled -> MaterialTheme.colorScheme.primaryContainer
        selected -> glass.elevatedFill
        else -> glass.cardFill
    }
    val borderStroke = if (selected) {
        BorderStroke(1.6.dp, MaterialTheme.colorScheme.primary)
    } else {
        glassBorderStroke(glass = glass, width = 1.dp)
    }
    val icon = when (preference) {
        ThemePreference.SYSTEM -> Icons.Outlined.BrightnessAuto
        ThemePreference.LIGHT -> Icons.Outlined.LightMode
        ThemePreference.DARK -> Icons.Outlined.DarkMode
        ThemePreference.IOS_GLASS -> Icons.Outlined.AutoAwesome
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = borderStroke,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .testTag("theme_option_${preference.name.lowercase()}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemePreviewSwatch(preference = preference)
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "${preference.label} selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = marketColors.mutedText,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = preference.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preference.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = marketColors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ThemePreviewSwatch(preference: ThemePreference) {
    val swatchShape = RoundedCornerShape(8.dp)
    when (preference) {
        ThemePreference.SYSTEM -> {
            Row(
                modifier = Modifier
                    .size(width = 38.dp, height = 24.dp)
                    .clip(swatchShape)
                    .border(0.8.dp, Color(0xFF64748B).copy(alpha = 0.55f), swatchShape),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFFF3F6FB)),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF000000)),
                )
            }
        }

        ThemePreference.LIGHT -> {
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 24.dp)
                    .clip(swatchShape)
                    .background(Color(0xFFF3F6FB))
                    .border(0.8.dp, Color(0xFFCBD5E1), swatchShape)
                    .padding(4.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0A66FF)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White),
                    )
                }
            }
        }

        ThemePreference.DARK -> {
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 24.dp)
                    .clip(swatchShape)
                    .background(Color(0xFF000000))
                    .border(0.9.dp, Color(0xFF3B9EFF).copy(alpha = 0.7f), swatchShape)
                    .padding(4.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF171920)),
                    )
                }
            }
        }

        ThemePreference.IOS_GLASS -> {
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 24.dp)
                    .clip(swatchShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF60A5FA),
                                Color(0xFFA78BFA),
                                Color(0xFF38BDF8),
                            ),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.9f), swatchShape)
                    .padding(3.5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = 0.62f))
                        .border(0.6.dp, Color.White.copy(alpha = 0.95f), RoundedCornerShape(5.dp)),
                )
            }
        }
    }
}
