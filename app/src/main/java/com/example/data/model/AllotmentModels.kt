package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class AllotmentWatchStatus(val label: String) {
    PENDING("Pending"),
    RESULT_LIVE("Result Live"),
    ALLOTTED("Allotted"),
    NOT_ALLOTTED("Not Allotted"),
}

@Entity(tableName = "private_user_profiles")
data class PrivateUserProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val panNumber: String,
    val dematId: String = "",
    val upiHandle: String = "",
    val isDefault: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val maskedPan: String
        get() = maskPanNumber(panNumber)
}

@Entity(tableName = "allotment_watches")
data class AllotmentWatchEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val profileName: String,
    val maskedPan: String,
    val ipoId: String,
    val ipoName: String,
    val registrarName: String,
    val registrarUrl: String,
    val allotmentDate: String,
    val listingDate: String,
    val lotsApplied: Int = 1,
    val sharesPerLot: Int = 1,
    val applicationNumber: String = "",
    val status: AllotmentWatchStatus = AllotmentWatchStatus.PENDING,
    val sharesAllotted: Int = 0,
    val lastCheckedAtMs: Long = 0L,
    val lastCheckSummary: String = "Waiting for allotment result",
    val notifiedAllotmentLive: Boolean = false,
    val notifiedFinalResult: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val totalSharesApplied: Int
        get() = (lotsApplied.coerceAtLeast(1)) * (sharesPerLot.coerceAtLeast(1))
}

object PanPrivacyUtils {
    private val PAN_REGEX = Regex("^[A-Z]{5}[0-9]{4}[A-Z]$")

    fun normalizePan(raw: String): String =
        raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

    fun isValidPan(raw: String): Boolean = PAN_REGEX.matches(normalizePan(raw))

    fun maskPan(raw: String): String {
        val clean = normalizePan(raw)
        if (clean.length != 10) return "••••••••••"
        return "${clean.substring(0, 4)}••••${clean.substring(8)}"
    }
}

fun maskPanNumber(raw: String): String = PanPrivacyUtils.maskPan(raw)
