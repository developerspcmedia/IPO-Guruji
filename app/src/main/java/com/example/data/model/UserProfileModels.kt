package com.example.data.model

import androidx.annotation.Keep
import com.example.data.preferences.CloudSyncedPrefsSnapshot
import com.example.data.preferences.ThemePreference
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class InvestorCategory(
    val displayLabel: String,
    val badgeLabel: String,
    val description: String,
) {
    RETAIL("Retail", "RETAIL", "Up to ₹2 Lakh"),
    S_HNI("S-HNI", "S-HNI", "₹2L – ₹10L"),
    B_HNI("B-HNI", "B-HNI", "Above ₹10L"),
    SHAREHOLDER("Shareholder", "SHAREHOLDER", "Parent quota"),
    ;

    companion object {
        fun fromRaw(raw: String?): InvestorCategory {
            if (raw.isNullOrBlank()) return RETAIL
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: RETAIL
        }
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfileDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val investorCategory: String = InvestorCategory.RETAIL.name,
    val bio: String? = null,
    val bookmarkedIpoIds: List<String> = emptyList(),
    val preferences: CloudSyncedPrefsSnapshot = CloudSyncedPrefsSnapshot(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    val categoryEnum: InvestorCategory
        get() = InvestorCategory.fromRaw(investorCategory)

    fun toPreferencesMap(): Map<String, Any> = mapOf(
        "themePreference" to preferences.themePreference.name,
        "compactCards" to preferences.compactCards,
        "notifyNewIpo" to preferences.notifyNewIpo,
        "notifyOpenIpo" to preferences.notifyOpenIpo,
        "notifyAllotment" to preferences.notifyAllotment,
        "notifyNewsAlerts" to preferences.notifyNewsAlerts,
    )

    /**
     * Exact create payload mirroring `firestore.rules` `/user_profiles/{userId}` `allow create`.
     * Never writes `@DocumentId` `id`, never writes any `role` field, strips null optional keys.
     */
    fun toCreateMap(): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "userId" to userId,
            "displayName" to displayName.trim().take(80).ifBlank { "Investor" },
            "email" to email.trim().take(120),
            "investorCategory" to categoryEnum.name,
            "bookmarkedIpoIds" to bookmarkedIpoIds.distinct().take(200),
            "preferences" to toPreferencesMap(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val cleanPhoto = photoUrl?.trim()?.take(500)
        if (!cleanPhoto.isNullOrEmpty()) {
            payload["photoUrl"] = cleanPhoto
        }
        val cleanBio = bio?.trim()?.take(240)
        if (!cleanBio.isNullOrEmpty()) {
            payload["bio"] = cleanBio
        }
        return payload
    }

    /**
     * Exact partial update payload mirroring `firestore.rules` `/user_profiles/{userId}` `allow update`.
     * Preserves immutable `userId`, `email`, and `createdAt`.
     */
    fun toDetailsUpdateMap(): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "displayName" to displayName.trim().take(80).ifBlank { "Investor" },
            "investorCategory" to categoryEnum.name,
            "bio" to bio.orEmpty().trim().take(240),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val cleanPhoto = photoUrl?.trim()?.take(500)
        if (!cleanPhoto.isNullOrEmpty()) {
            payload["photoUrl"] = cleanPhoto
        }
        return payload
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromDocumentSnapshot(doc: DocumentSnapshot): UserProfileDocument? {
            val data = doc.data ?: return null
            val uid = (data["userId"] as? String).orEmpty().ifBlank { doc.id }
            val name = (data["displayName"] as? String).orEmpty()
            val email = (data["email"] as? String).orEmpty()
            if (uid.isBlank() || name.isBlank()) return null

            val rawBookmarks = (data["bookmarkedIpoIds"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.distinct()
                ?.take(200)
                ?: emptyList()

            val prefsMap = data["preferences"] as? Map<String, Any?> ?: emptyMap()
            val themeStr = prefsMap["themePreference"] as? String
            val resolvedTheme = themeStr
                ?.let { raw -> ThemePreference.entries.firstOrNull { it.name == raw } }
                ?: ThemePreference.SYSTEM

            val parsedPrefs = CloudSyncedPrefsSnapshot(
                themePreference = resolvedTheme,
                compactCards = prefsMap["compactCards"] as? Boolean ?: false,
                notifyNewIpo = prefsMap["notifyNewIpo"] as? Boolean ?: true,
                notifyOpenIpo = prefsMap["notifyOpenIpo"] as? Boolean ?: true,
                notifyAllotment = prefsMap["notifyAllotment"] as? Boolean ?: true,
                notifyNewsAlerts = prefsMap["notifyNewsAlerts"] as? Boolean ?: true,
            )

            return UserProfileDocument(
                id = doc.id,
                userId = uid,
                displayName = name,
                email = email,
                photoUrl = data["photoUrl"] as? String,
                investorCategory = InvestorCategory.fromRaw(data["investorCategory"] as? String).name,
                bio = data["bio"] as? String,
                bookmarkedIpoIds = rawBookmarks,
                preferences = parsedPrefs,
                createdAt = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                updatedAt = doc.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
            )
        }
    }
}
