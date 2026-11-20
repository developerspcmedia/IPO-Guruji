package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalMarketColors

private data class FaqItem(
    val question: String,
    val answer: String,
)

private data class LegalSection(
    val heading: String,
    val body: String,
)

@Composable
fun IpoFaqsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val faqs = remember {
        listOf(
            FaqItem(
                question = "What is Grey Market Premium (GMP)?",
                answer = "GMP is the unofficial premium at which IPO shares trade in the unregulated grey market before listing on NSE or BSE. It reflects informal market sentiment, not a guaranteed listing price.",
            ),
            FaqItem(
                question = "What is the difference between Retail, sHNI, and bHNI categories?",
                answer = "Retail Individual Investors (RII) apply up to ₹2,00,000. Small HNIs (sHNI) bid between ₹2,00,000 and ₹10,00,000, while Big HNIs (bHNI) bid above ₹10,00,000.",
            ),
            FaqItem(
                question = "How do Mainboard IPOs differ from SME IPOs?",
                answer = "Mainboard IPOs are larger companies listed on NSE/BSE with retail lot sizes around ₹14,000–₹15,000. SME IPOs list on NSE Emerge or BSE SME with higher minimum lot investments (often ₹1,00,000+).",
            ),
            FaqItem(
                question = "Should retail investors select the Cut-Off Price option?",
                answer = "Yes. Selecting the Cut-Off Price allows retail investors to accept whatever final issue price is discovered through book building, preventing bid rejection if the issue prices at the upper band.",
            ),
            FaqItem(
                question = "How and when can I check my IPO allotment status?",
                answer = "Allotment is generally finalized on T+1 working day after the IPO closes. You can check using your PAN or Application Number on the official registrar portal (MUFG Intime, KFinTech, Bigshare, etc.) via the Allotment Hub menu.",
            ),
            FaqItem(
                question = "When do blocked ASBA / UPI funds get unblocked?",
                answer = "If shares are not allotted, your bank usually revokes the UPI mandate or ASBA block on T+2 working days after issue closure, before the stock lists on T+3.",
            ),
        )
    }

    var expandedIndex by remember { mutableStateOf<Int?>(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("faqs_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            DrawerSubScreenTopBar(
                title = "IPO FAQs",
                subtitle = "Common questions about GMP, Allotment & Bidding",
                onBack = onBack,
                backButtonTag = "faqs_back_button",
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(faqs) { index, faq ->
                    val expanded = expandedIndex == index
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { expandedIndex = if (expanded) null else index }
                            .testTag("faq_item_$index"),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, marketColors.border),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = faq.question,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            AnimatedVisibility(visible = expanded) {
                                Column {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(
                                        thickness = 0.7.dp,
                                        color = marketColors.border.copy(alpha = 0.7f),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = faq.answer,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember {
        listOf(
            LegalSection(
                heading = "1. On-Device Storage & Preferences",
                body = "IPO Guruji stores your theme preference, watchlist bookmarks, local alert toggles, and cached IPO market snapshots strictly on your device using Android Room and DataStore. No account sign-in or registration is required.",
            ),
            LegalSection(
                heading = "2. Private PAN Profiles & Allotment Checks",
                body = "If you save PAN profiles inside Allotment Alerts, they are stored strictly on your device and excluded from Android cloud backup—never uploaded to IPO Guruji servers. When you initiate an allotment status check, your PAN is transmitted over encrypted HTTPS directly to official SEBI-registered registrars (such as MUFG Intime, KFinTech, Bigshare, or Cameo) solely to fetch your allotment status. You can mask, edit, or delete saved PAN profiles inside the app at any time.",
            ),
            LegalSection(
                heading = "3. Biometric & Device Vault Lock",
                body = "The optional Vault Lock (USE_BIOMETRIC) uses Android's built-in system biometric or device PIN prompt locally on your phone to protect your Allotment Alerts tab. IPO Guruji never accesses, reads, or stores biometric templates or device passcodes.",
            ),
            LegalSection(
                heading = "4. Market Data & Independent Status",
                body = "IPO Guruji is an independent informational utility and is not affiliated with SEBI, NSE, BSE, or any IPO registrar. The app fetches publicly available Mainboard and SME IPO schedules, live subscription feeds, news, and unofficial grey market estimates over HTTPS. Opening external registrar, exchange, or news links takes you to third-party websites governed by their own privacy policies.",
            ),
            LegalSection(
                heading = "5. Advertising (Google AdMob)",
                body = "IPO Guruji displays advertisements via Google AdMob (Banner, Native, Interstitial, and App Open ads) to keep the tracker free to use. Google Mobile Ads may collect device signals, approximate location, app interactions, and Advertising ID in accordance with Google's Privacy Policy. You can reset or opt out of personalized ads anytime in Android Settings → Google → Ads.",
            ),
            LegalSection(
                heading = "6. Notifications & Permissions",
                body = "Notification permission (POST_NOTIFICATIONS on Android 13+) is completely optional and used solely for on-device alerts covering new IPOs, subscription openings, private allotment updates, and breaking IPO news.",
            ),
            LegalSection(
                heading = "7. Data Deletion & Support",
                body = "Because all user preferences and PAN profiles remain strictly on your device, you can delete individual PAN profiles anytime inside Allotment Alerts or remove all app data by clearing storage or uninstalling the app. For privacy questions, IPO data corrections, or technical support, contact developers.pcmedia@gmail.com.",
            ),
        )
    }

    LegalDocumentScreen(
        title = "Privacy Policy",
        subtitle = "IPO Guruji • Effective 2026",
        screenTag = "privacy_policy_screen",
        backTag = "privacy_policy_back_button",
        sections = sections,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun TermsConditionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember {
        listOf(
            LegalSection(
                heading = "1. Educational & Informational Purpose Only",
                body = "IPO Guruji is an independent IPO tracking utility. Nothing published inside this application constitutes investment advice, brokerage services, solicitation, or a recommendation to buy, sell, or subscribe to any securities.",
            ),
            LegalSection(
                heading = "2. Not SEBI-Registered & Independent App",
                body = "IPO Guruji is not registered with SEBI as an investment advisor or research analyst, and is not affiliated with or endorsed by SEBI, NSE, BSE, or any IPO registrar. Always read the official Red Herring Prospectus (RHP) and consult a qualified SEBI-registered financial advisor before investing.",
            ),
            LegalSection(
                heading = "3. Grey Market Premium (GMP) Disclaimer",
                body = "GMP figures displayed in the app are compiled from unofficial public market trackers and can change rapidly without notice. Actual NSE/BSE listing prices may differ significantly from pre-listing estimates.",
            ),
            LegalSection(
                heading = "4. No Liability for Market Losses",
                body = "While we strive to keep schedules, subscription figures, and financials accurate and timely, IPO Guruji makes no warranty regarding completeness and is not liable for trading or investment decisions made using this information.",
            ),
        )
    }

    LegalDocumentScreen(
        title = "Terms and Conditions",
        subtitle = "Usage Terms & SEBI Disclaimer",
        screenTag = "terms_conditions_screen",
        backTag = "terms_conditions_back_button",
        sections = sections,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun LegalDocumentScreen(
    title: String,
    subtitle: String,
    screenTag: String,
    backTag: String,
    sections: List<LegalSection>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(screenTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            DrawerSubScreenTopBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                backButtonTag = backTag,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(sections) { _, section ->
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, marketColors.border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = section.heading,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = section.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactUsDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    val supportEmail = "developers.pcmedia@gmail.com"

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("contact_us_dialog"),
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                text = "Contact IPO Guruji",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Have a question, IPO data correction, or feature suggestion? Reach out to our team directly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ),
                    border = BorderStroke(1.dp, marketColors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Email,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text(
                                text = "Support Email",
                                style = MaterialTheme.typography.labelSmall,
                                color = marketColors.mutedText,
                            )
                            Text(
                                text = supportEmail,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$supportEmail")
                        putExtra(Intent.EXTRA_SUBJECT, "IPO Guruji App Support & Feedback")
                    }
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                },
                modifier = Modifier.testTag("contact_us_email_button"),
            ) {
                Text("Email Support")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
