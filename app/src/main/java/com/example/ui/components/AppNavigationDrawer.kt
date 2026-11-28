package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Person
import com.example.R
import com.example.data.firebase.AdminAuthSession
import com.example.data.model.InvestorCategory
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors

enum class DrawerMenuItemId {
    USER_PROFILE,
    ADMIN_DASHBOARD,
    HOME,
    MAINBOARD,
    SME,
    USA_IPOS,
    LISTED,
    IPO_CALENDAR,
    ALLOTMENT_ALERTS,
    ALLOTMENT_HUB,
    IPO_NEWS,
    IPO_GMP,
    COMPARE_IPOS,
    IPO_LIST_YEARWISE,
    SUBSCRIPTION_YEARWISE,
    FAQS,
    SETTINGS,
    CONTACT_US,
    SHARE_FRIENDS,
    PRIVACY_POLICY,
    TERMS_CONDITIONS,
    EXIT_APP,
}

@Composable
fun AppNavigationDrawerSheet(
    activeItem: DrawerMenuItemId,
    liveIpoCount: Int,
    lastSyncedLabel: String,
    drawerWidth: Dp,
    onSelectItem: (DrawerMenuItemId) -> Unit,
    onQuickSyncGmp: () -> Unit,
    adminSession: AdminAuthSession = AdminAuthSession(),
    userDisplayName: String = "",
    investorCategory: InvestorCategory = InvestorCategory.RETAIL,
    bookmarkedCount: Int = 0,
    activeOverrideCount: Int = 0,
    isAdminSigningIn: Boolean = false,
    onOpenUserProfile: () -> Unit = { onSelectItem(DrawerMenuItemId.USER_PROFILE) },
    onOpenAdminDashboard: () -> Unit = { onSelectItem(DrawerMenuItemId.ADMIN_DASHBOARD) },
    onAdminGoogleSignIn: () -> Unit = {},
    onAdminSignOut: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val badgeOrange = Color(0xFFFF5A26)

    ModalDrawerSheet(
        modifier = modifier
            .width(drawerWidth)
            .fillMaxHeight()
            .testTag("app_navigation_drawer"),
        drawerContainerColor = glass.sheetFill,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            // Top Profile / Brand Header mirroring reference drawer layout
            DrawerBrandHeader(
                liveIpoCount = liveIpoCount,
                lastSyncedLabel = lastSyncedLabel,
                onQuickSyncGmp = onQuickSyncGmp,
            )

            // Unified Investor Profile Card (Admin Dashboard button is strictly invisible to guests & normal users)
            DrawerUserProfileCard(
                authSession = adminSession,
                resolvedDisplayName = userDisplayName,
                investorCategory = investorCategory,
                bookmarkedCount = bookmarkedCount,
                activeOverrideCount = activeOverrideCount,
                isSigningIn = isAdminSigningIn,
                isProfileSelected = activeItem == DrawerMenuItemId.USER_PROFILE,
                isAdminSelected = activeItem == DrawerMenuItemId.ADMIN_DASHBOARD,
                onOpenUserProfile = onOpenUserProfile,
                onOpenAdminDashboard = onOpenAdminDashboard,
                onGoogleSignIn = onAdminGoogleSignIn,
                onSignOut = onAdminSignOut,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Primary Menu Items
            DrawerMenuRow(
                icon = Icons.Outlined.Home,
                label = "Home",
                selected = activeItem == DrawerMenuItemId.HOME,
                testTag = "drawer_item_home",
                onClick = { onSelectItem(DrawerMenuItemId.HOME) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.Business,
                label = "Mainboard IPOs",
                selected = activeItem == DrawerMenuItemId.MAINBOARD,
                testTag = "drawer_item_mainboard",
                onClick = { onSelectItem(DrawerMenuItemId.MAINBOARD) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.RocketLaunch,
                label = "SME IPOs",
                selected = activeItem == DrawerMenuItemId.SME,
                testTag = "drawer_item_sme",
                onClick = { onSelectItem(DrawerMenuItemId.SME) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.Public,
                label = "USA IPOs",
                selected = activeItem == DrawerMenuItemId.USA_IPOS,
                badgeText = "NEW",
                badgeColor = badgeOrange,
                testTag = "drawer_item_usa_ipos",
                onClick = { onSelectItem(DrawerMenuItemId.USA_IPOS) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.CheckCircle,
                label = "Listed IPOs",
                selected = activeItem == DrawerMenuItemId.LISTED,
                testTag = "drawer_item_listed",
                onClick = { onSelectItem(DrawerMenuItemId.LISTED) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.CalendarMonth,
                label = "IPO Event Calendar",
                selected = activeItem == DrawerMenuItemId.IPO_CALENDAR,
                testTag = "drawer_item_ipo_calendar",
                onClick = { onSelectItem(DrawerMenuItemId.IPO_CALENDAR) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.NotificationsActive,
                label = "Allotment Alerts",
                selected = activeItem == DrawerMenuItemId.ALLOTMENT_ALERTS,
                badgeText = "NEW",
                badgeColor = badgeOrange,
                testTag = "drawer_item_allotment_alerts",
                onClick = { onSelectItem(DrawerMenuItemId.ALLOTMENT_ALERTS) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.Description,
                label = "Allotment Hub",
                selected = activeItem == DrawerMenuItemId.ALLOTMENT_HUB,
                testTag = "drawer_item_allotment_hub",
                onClick = { onSelectItem(DrawerMenuItemId.ALLOTMENT_HUB) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.Newspaper,
                label = "Market News",
                selected = activeItem == DrawerMenuItemId.IPO_NEWS,
                testTag = "drawer_item_news",
                onClick = { onSelectItem(DrawerMenuItemId.IPO_NEWS) },
            )

            // REPORTS Section
            DrawerSectionHeader(title = "REPORTS")

            DrawerMenuRow(
                icon = Icons.AutoMirrored.Outlined.ShowChart,
                label = "IPO GMP",
                selected = activeItem == DrawerMenuItemId.IPO_GMP,
                testTag = "drawer_item_ipo_gmp",
                onClick = { onSelectItem(DrawerMenuItemId.IPO_GMP) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.Balance,
                label = "Compare IPOs",
                selected = activeItem == DrawerMenuItemId.COMPARE_IPOS,
                badgeText = "NEW",
                badgeColor = badgeOrange,
                testTag = "drawer_item_compare_ipos",
                onClick = { onSelectItem(DrawerMenuItemId.COMPARE_IPOS) },
            )
            DrawerMenuRow(
                icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
                label = "IPO List (Year-wise)",
                selected = activeItem == DrawerMenuItemId.IPO_LIST_YEARWISE,
                testTag = "drawer_item_ipo_list_yearwise",
                onClick = { onSelectItem(DrawerMenuItemId.IPO_LIST_YEARWISE) },
            )
            DrawerMenuRow(
                icon = Icons.AutoMirrored.Outlined.TrendingUp,
                label = "Subscription (Year-wise)",
                selected = activeItem == DrawerMenuItemId.SUBSCRIPTION_YEARWISE,
                testTag = "drawer_item_subscription_yearwise",
                onClick = { onSelectItem(DrawerMenuItemId.SUBSCRIPTION_YEARWISE) },
            )

            // OTHER Section
            DrawerSectionHeader(title = "OTHER")

            DrawerMenuRow(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                label = "FAQs",
                selected = activeItem == DrawerMenuItemId.FAQS,
                testTag = "drawer_item_faqs",
                onClick = { onSelectItem(DrawerMenuItemId.FAQS) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.Settings,
                label = "Settings",
                selected = activeItem == DrawerMenuItemId.SETTINGS,
                testTag = "drawer_item_settings",
                onClick = { onSelectItem(DrawerMenuItemId.SETTINGS) },
            )
            DrawerMenuRow(
                icon = Icons.AutoMirrored.Outlined.Chat,
                label = "Contact Us",
                selected = activeItem == DrawerMenuItemId.CONTACT_US,
                testTag = "drawer_item_contact_us",
                onClick = { onSelectItem(DrawerMenuItemId.CONTACT_US) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.Share,
                label = "Share with Friends",
                selected = false,
                testTag = "drawer_item_share_friends",
                onClick = { onSelectItem(DrawerMenuItemId.SHARE_FRIENDS) },
            )
            DrawerMenuRow(
                icon = Icons.Outlined.VerifiedUser,
                label = "Privacy Policy",
                selected = activeItem == DrawerMenuItemId.PRIVACY_POLICY,
                testTag = "drawer_item_privacy_policy",
                onClick = { onSelectItem(DrawerMenuItemId.PRIVACY_POLICY) },
            )
            DrawerMenuRow(
                icon = Icons.AutoMirrored.Outlined.Article,
                label = "Terms and Conditions",
                selected = activeItem == DrawerMenuItemId.TERMS_CONDITIONS,
                testTag = "drawer_item_terms_conditions",
                onClick = { onSelectItem(DrawerMenuItemId.TERMS_CONDITIONS) },
            )

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                thickness = 0.8.dp,
                color = marketColors.border.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(6.dp))

            DrawerMenuRow(
                icon = Icons.AutoMirrored.Outlined.ExitToApp,
                label = "Exit App",
                selected = false,
                testTag = "drawer_item_exit_app",
                onClick = { onSelectItem(DrawerMenuItemId.EXIT_APP) },
            )
        }
    }
}

@Composable
private fun DrawerBrandHeader(
    liveIpoCount: Int,
    lastSyncedLabel: String,
    onQuickSyncGmp: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val avatarRingBrush = Brush.linearGradient(
        colors = listOf(
            marketColors.ctaGold,
            MaterialTheme.colorScheme.primary,
        ),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Circular Brand Avatar Ring
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(avatarRingBrush)
                .padding(2.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = Color(0xFF080B12),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_ipo_guruji_logo),
                    contentDescription = "IPO Guruji Brand Emblem",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "IPO Guruji",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (liveIpoCount > 0) {
                    "$liveIpoCount Live IPOs • $lastSyncedLabel"
                } else {
                    "Live IPO & GMP Desk"
                },
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(6.dp))

            val ctaBg = MaterialTheme.colorScheme.onSurface
            val ctaFg = if (LocalGlassStyle.current.isDark) MaterialTheme.colorScheme.background else Color.White

            // High-Contrast Pill Action mirroring reference header CTA pill
            Surface(
                onClick = onQuickSyncGmp,
                shape = RoundedCornerShape(99.dp),
                color = ctaBg,
                contentColor = ctaFg,
                border = BorderStroke(0.6.dp, marketColors.border.copy(alpha = 0.4f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("drawer_sync_gmp_pill"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Sync Live GMP",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ctaFg,
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = marketColors.ctaGold,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerUserProfileCard(
    authSession: AdminAuthSession,
    resolvedDisplayName: String,
    investorCategory: InvestorCategory,
    bookmarkedCount: Int,
    activeOverrideCount: Int,
    isSigningIn: Boolean,
    isProfileSelected: Boolean,
    isAdminSelected: Boolean,
    onOpenUserProfile: () -> Unit,
    onOpenAdminDashboard: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val isSelected = isProfileSelected || (authSession.isAuthorizedAdmin && isAdminSelected)
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        authSession.isAuthorizedAdmin -> marketColors.bullish.copy(alpha = 0.5f)
        else -> marketColors.border.copy(alpha = 0.75f)
    }
    val effectiveName = resolvedDisplayName.ifBlank { authSession.displayName }.ifBlank { "Investor" }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isSelected) 1.2.dp else 0.8.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("drawer_user_profile_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (authSession.isAuthorizedAdmin) {
                                marketColors.bullishContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (authSession.isAuthorizedAdmin) {
                            Icons.Rounded.AdminPanelSettings
                        } else {
                            Icons.Rounded.Person
                        },
                        contentDescription = "Investor Profile",
                        tint = if (authSession.isAuthorizedAdmin) {
                            marketColors.bullish
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (authSession.isSignedIn) effectiveName else "Investor Profile",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (authSession.isAuthorizedAdmin) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = marketColors.bullishContainer,
                                modifier = Modifier.testTag("drawer_admin_verified_badge"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = marketColors.bullish,
                                        modifier = Modifier.size(10.dp),
                                    )
                                    Text(
                                        text = "ADMIN",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = marketColors.bullish,
                                    )
                                }
                            }
                        } else if (authSession.isSignedIn) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = marketColors.smeContainer,
                                modifier = Modifier.testTag("drawer_user_category_badge"),
                            ) {
                                Text(
                                    text = investorCategory.badgeLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = marketColors.smeBadge,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Text(
                        text = when {
                            authSession.isAuthorizedAdmin -> {
                                if (activeOverrideCount > 0) {
                                    "${authSession.email} • $activeOverrideCount overrides"
                                } else {
                                    authSession.email
                                }
                            }
                            authSession.isSignedIn -> {
                                if (bookmarkedCount > 0) {
                                    "${authSession.email} • $bookmarkedCount saved"
                                } else {
                                    authSession.email
                                }
                            }
                            else -> "Sign in to sync watchlist & alerts"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = marketColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (authSession.isSignedIn) {
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("drawer_user_sign_out_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = "Sign out",
                            tint = marketColors.mutedText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (authSession.isAuthorizedAdmin) {
                // Owner admin sees both personal Profile and exclusive Admin Dashboard
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenAdminDashboard,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("drawer_open_admin_dashboard_button"),
                    ) {
                        Text(
                            text = "Admin Dashboard",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenUserProfile,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("drawer_open_user_profile_button"),
                    ) {
                        Text(
                            text = "My Profile",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else if (authSession.isSignedIn) {
                // Regular signed-in user sees ONLY their Investor Profile — zero Admin controls
                Button(
                    onClick = onOpenUserProfile,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("drawer_open_user_profile_button"),
                ) {
                    Text(
                        text = "Open Profile",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                // Signed-out guest sees friendly Sign in + View Profile — zero Admin controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onGoogleSignIn,
                        enabled = !isSigningIn,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("drawer_google_sign_in_button"),
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                text = "Sign in",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenUserProfile,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("drawer_open_user_profile_button"),
                    ) {
                        Text(
                            text = "Profile",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    val marketColors = LocalMarketColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = marketColors.mutedText,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun DrawerMenuRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
) {
    val rowBg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }
    val iconTint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val textColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!badgeText.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}
