package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.AllotmentWatchStatus
import com.example.data.model.IpoEntity
import com.example.data.model.PanPrivacyUtils
import com.example.data.model.PrivateUserProfileEntity
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle

@Composable
fun AddEditProfileDialog(
    existingProfile: PrivateUserProfileEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, pan: String, dematId: String, upiHandle: String) -> Unit,
) {
    val marketColors = LocalMarketColors.current
    var name by remember(existingProfile) { mutableStateOf(existingProfile?.displayName.orEmpty()) }
    var pan by remember(existingProfile) { mutableStateOf(existingProfile?.panNumber.orEmpty()) }
    var dematId by remember(existingProfile) { mutableStateOf(existingProfile?.dematId.orEmpty()) }
    var upiHandle by remember(existingProfile) { mutableStateOf(existingProfile?.upiHandle.orEmpty()) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("add_edit_profile_dialog"),
        shape = RoundedCornerShape(22.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (existingProfile == null) "Add Private User Profile" else "Edit Private Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = marketColors.bullish,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Stored only on this device • Never shared",
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.bullish,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        localError = null
                    },
                    label = { Text("Profile Name (e.g., Self, Spouse, Dad)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_name_input"),
                )
                OutlinedTextField(
                    value = pan,
                    onValueChange = { raw ->
                        pan = PanPrivacyUtils.normalizePan(raw).take(10)
                        localError = null
                    },
                    label = { Text("PAN Number (10 chars)") },
                    placeholder = { Text("ABCDE1234F") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    supportingText = {
                        val valid = PanPrivacyUtils.isValidPan(pan)
                        Text(
                            text = if (pan.isEmpty()) {
                                "Format: 5 letters + 4 digits + 1 letter"
                            } else if (valid) {
                                "Valid PAN • Masked as ${PanPrivacyUtils.maskPan(pan)}"
                            } else {
                                "Enter all 10 characters (e.g., ABCDE1234F)"
                            },
                            color = if (valid) marketColors.bullish else marketColors.mutedText,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_pan_input"),
                )
                OutlinedTextField(
                    value = dematId,
                    onValueChange = { dematId = it.take(20) },
                    label = { Text("Demat BO / DP-Client ID (Optional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_demat_input"),
                )
                OutlinedTextField(
                    value = upiHandle,
                    onValueChange = { upiHandle = it.take(40) },
                    label = { Text("UPI Handle (Optional)") },
                    placeholder = { Text("name@upi") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_upi_input"),
                )
                if (!localError.isNullOrBlank()) {
                    Text(
                        text = localError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    val cleanPan = PanPrivacyUtils.normalizePan(pan)
                    when {
                        cleanName.isBlank() -> localError = "Please enter a profile name."
                        !PanPrivacyUtils.isValidPan(cleanPan) -> localError = "Please enter a valid 10-character PAN (e.g., ABCDE1234F)."
                        else -> onSave(cleanName, cleanPan, dematId.trim(), upiHandle.trim())
                    }
                },
                modifier = Modifier.testTag("save_profile_button"),
            ) {
                Text(if (existingProfile == null) "Save Privately" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun TrackIpoAllotmentDialog(
    ipos: List<IpoEntity>,
    profiles: List<PrivateUserProfileEntity>,
    initialIpoId: String?,
    onDismiss: () -> Unit,
    onConfirmTrack: (ipo: IpoEntity, profileIds: List<String>, lots: Int, appNumber: String) -> Unit,
) {
    val marketColors = LocalMarketColors.current
    var searchIpo by remember { mutableStateOf("") }
    var selectedIpoId by remember(initialIpoId, ipos) {
        mutableStateOf(
            initialIpoId?.takeIf { id -> ipos.any { it.id == id } }
                ?: ipos.firstOrNull()?.id,
        )
    }
    var selectedProfileIds by remember(profiles) {
        mutableStateOf(profiles.firstOrNull()?.let { setOf(it.id) } ?: emptySet())
    }
    var lotsApplied by remember { mutableIntStateOf(1) }
    var appNumber by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val visibleIpos = remember(ipos, searchIpo) {
        val q = searchIpo.trim().lowercase()
        val list = if (q.isBlank()) ipos else ipos.filter {
            it.companyName.lowercase().contains(q) || it.shortCode.lowercase().contains(q)
        }
        list.take(12)
    }
    val selectedIpo = remember(ipos, selectedIpoId) { ipos.firstOrNull { it.id == selectedIpoId } }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("track_ipo_allotment_dialog"),
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = "Track IPO Allotment Alert",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "1. Select IPO",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = searchIpo,
                    onValueChange = { searchIpo = it },
                    placeholder = { Text("Search IPO name...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleIpos.forEach { ipo ->
                        val isSelected = ipo.id == selectedIpoId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.2.dp else 0.8.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else marketColors.border,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIpoId = ipo.id
                                    localError = null
                                }
                                .testTag("pick_ipo_option_${ipo.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ipo.companyName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "Allotment: ${ipo.allotmentDate} • ${ipo.registrarName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = marketColors.mutedText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "2. Select Private Profile(s)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    profiles.forEach { profile ->
                        val checked = profile.id in selectedProfileIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProfileIds = if (checked) {
                                        selectedProfileIds - profile.id
                                    } else {
                                        selectedProfileIds + profile.id
                                    }
                                    localError = null
                                }
                                .testTag("pick_profile_checkbox_${profile.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedProfileIds = if (isChecked) {
                                        selectedProfileIds + profile.id
                                    } else {
                                        selectedProfileIds - profile.id
                                    }
                                    localError = null
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "PAN ${profile.maskedPan}",
                                    style = MonoNumericStyle.copy(
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                    ),
                                    color = marketColors.mutedText,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "3. Lots Applied & Application No. (Optional)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Lots Applied",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (lotsApplied > 1) lotsApplied-- },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Decrease lots")
                        }
                        Text(
                            text = lotsApplied.toString(),
                            style = MonoNumericStyle,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(
                            onClick = { if (lotsApplied < 100) lotsApplied++ },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Increase lots")
                        }
                    }
                }

                OutlinedTextField(
                    value = appNumber,
                    onValueChange = { appNumber = it.take(30) },
                    label = { Text("Application Number (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!localError.isNullOrBlank()) {
                    Text(
                        text = localError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetIpo = selectedIpo
                    when {
                        targetIpo == null -> localError = "Please select an IPO."
                        selectedProfileIds.isEmpty() -> localError = "Select at least one private profile."
                        else -> onConfirmTrack(
                            targetIpo,
                            selectedProfileIds.toList(),
                            lotsApplied,
                            appNumber.trim(),
                        )
                    }
                },
                modifier = Modifier.testTag("confirm_track_allotment_button"),
            ) {
                Text("Enable Alert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun ConfirmAllotmentResultDialog(
    watch: AllotmentWatchEntity,
    onDismiss: () -> Unit,
    onConfirm: (status: AllotmentWatchStatus, sharesAllotted: Int) -> Unit,
) {
    var selectedStatus by remember(watch) {
        mutableStateOf(
            if (watch.status == AllotmentWatchStatus.NOT_ALLOTTED) {
                AllotmentWatchStatus.NOT_ALLOTTED
            } else {
                AllotmentWatchStatus.ALLOTTED
            },
        )
    }
    var sharesText by remember(watch) {
        mutableStateOf(
            (if (watch.sharesAllotted > 0) watch.sharesAllotted else watch.sharesPerLot).toString(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("confirm_allotment_result_dialog"),
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = "Update Allotment Status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${watch.ipoName} • ${watch.profileName} (${watch.maskedPan})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedStatus == AllotmentWatchStatus.ALLOTTED,
                        onClick = { selectedStatus = AllotmentWatchStatus.ALLOTTED },
                        label = { Text("Allotted") },
                        modifier = Modifier.testTag("status_option_allotted"),
                    )
                    FilterChip(
                        selected = selectedStatus == AllotmentWatchStatus.NOT_ALLOTTED,
                        onClick = { selectedStatus = AllotmentWatchStatus.NOT_ALLOTTED },
                        label = { Text("Not Allotted") },
                        modifier = Modifier.testTag("status_option_not_allotted"),
                    )
                }
                if (selectedStatus == AllotmentWatchStatus.ALLOTTED) {
                    OutlinedTextField(
                        value = sharesText,
                        onValueChange = { raw -> sharesText = raw.filter { it.isDigit() }.take(7) },
                        label = { Text("Shares Allotted") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shares_allotted_input"),
                    )
                }
                Text(
                    text = "Confirming will immediately update your local record and send a private status notification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMarketColors.current.mutedText,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val shares = sharesText.toIntOrNull() ?: watch.sharesPerLot
                    onConfirm(selectedStatus, shares)
                },
                modifier = Modifier.testTag("save_allotment_result_button"),
            ) {
                Text("Save & Notify")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun WipePrivateDataDialog(
    onDismiss: () -> Unit,
    onConfirmWipe: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("wipe_private_data_dialog"),
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                text = "Delete All Private Data?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = "This permanently removes all saved private PAN profiles and tracked IPO allotment alerts from this device and stops background monitoring.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmWipe,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag("confirm_wipe_private_data_button"),
            ) {
                Text("Delete Everything")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
