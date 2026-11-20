package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.NewsCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class GmpWatchRule(
    val ipoId: String,
    val minGmpPercent: Int? = null,
    val maxGmpPercent: Int? = null,
    val lastAlertSignature: String? = null,
) {
    val isActive: Boolean
        get() = minGmpPercent != null || maxGmpPercent != null
}

data class SmartAlertPreferencesSnapshot(
    val day3ReminderIpoIds: Set<String> = emptySet(),
    val notifiedDay3ReminderIds: Set<String> = emptySet(),
    val gmpWatchRules: Map<String, GmpWatchRule> = emptyMap(),
)

enum class ThemePreference(
    val label: String,
    val subtitle: String,
) {
    SYSTEM("System", "Match device"),
    LIGHT("Light", "Clean daylight"),
    DARK("AMOLED", "True black OLED"),
    IOS_GLASS("iOS Glass", "Transparent frost"),
}

data class NotificationPreferencesSnapshot(
    val notifyNewIpoEnabled: Boolean,
    val notifyOpenIpoEnabled: Boolean,
    val notifyAllotmentEnabled: Boolean = true,
    val hasSeededBaseline: Boolean,
    val knownIpoIds: Set<String>,
    val notifiedOpenedIpoIds: Set<String>,
)

data class NewsNotificationPreferencesSnapshot(
    val notifyNewsAlertsEnabled: Boolean = true,
    val enabledCategories: Set<NewsCategory> = DEFAULT_NEWS_ALERT_CATEGORIES,
    val hasNewsBaseline: Boolean = false,
    val knownNewsIds: Set<String> = emptySet(),
    val unreadNewsIds: Set<String> = emptySet(),
) {
    companion object {
        val DEFAULT_NEWS_ALERT_CATEGORIES: Set<NewsCategory> = setOf(
            NewsCategory.UPCOMING,
            NewsCategory.GMP_LISTING,
            NewsCategory.SME_BUZZ,
            NewsCategory.SEBI_FILINGS,
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ipo_pulse_prefs")

class UserPreferencesRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AD_IMPRESSIONS = intPreferencesKey("ad_impressions")
        val DETAIL_OPENS = intPreferencesKey("detail_opens")
        val NOTIFY_NEW_IPO = booleanPreferencesKey("notify_new_ipo")
        val NOTIFY_OPEN_IPO = booleanPreferencesKey("notify_open_ipo")
        val NOTIFY_ALLOTMENT = booleanPreferencesKey("notify_allotment")
        val HAS_NOTIFICATION_BASELINE = booleanPreferencesKey("has_notification_baseline")
        val KNOWN_IPO_IDS = stringSetPreferencesKey("known_ipo_ids")
        val NOTIFIED_OPENED_IPO_IDS = stringSetPreferencesKey("notified_opened_ipo_ids")
        val NOTIFY_NEWS_ALERTS = booleanPreferencesKey("notify_news_alerts")
        val NEWS_ALERT_CATEGORIES = stringSetPreferencesKey("news_alert_categories")
        val HAS_NEWS_BASELINE = booleanPreferencesKey("has_news_baseline")
        val KNOWN_NEWS_IDS = stringSetPreferencesKey("known_news_ids")
        val UNREAD_NEWS_IDS = stringSetPreferencesKey("unread_news_ids")
        val DAY3_REMINDER_IPO_IDS = stringSetPreferencesKey("day3_reminder_ipo_ids")
        val NOTIFIED_DAY3_REMINDER_IDS = stringSetPreferencesKey("notified_day3_reminder_ids")
        val GMP_WATCH_RULES_JSON = stringPreferencesKey("gmp_watch_rules_json")
        val BOOKMARKED_IPO_IDS = stringSetPreferencesKey("bookmarked_ipo_ids")
        val COMPACT_CARDS_ENABLED = booleanPreferencesKey("compact_cards_enabled")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
    }

    val themePreference: Flow<ThemePreference> = context.dataStore.data.map { prefs ->
        val stored = prefs[Keys.THEME_MODE] ?: ThemePreference.SYSTEM.name
        runCatching { ThemePreference.valueOf(stored) }.getOrDefault(ThemePreference.SYSTEM)
    }

    val adImpressionCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AD_IMPRESSIONS] ?: 0
    }

    val detailOpenCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.DETAIL_OPENS] ?: 0
    }

    val notifyNewIpoEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIFY_NEW_IPO] ?: true
    }

    val notifyOpenIpoEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIFY_OPEN_IPO] ?: true
    }

    val notifyAllotmentEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIFY_ALLOTMENT] ?: true
    }

    val notifyNewsAlertsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIFY_NEWS_ALERTS] ?: true
    }

    val newsAlertCategories: Flow<Set<NewsCategory>> = context.dataStore.data.map { prefs ->
        parseNewsCategories(prefs[Keys.NEWS_ALERT_CATEGORIES])
    }

    val unreadNewsIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.UNREAD_NEWS_IDS] ?: emptySet()
    }

    val day3ReminderIpoIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.DAY3_REMINDER_IPO_IDS] ?: emptySet()
    }

    val gmpWatchRules: Flow<Map<String, GmpWatchRule>> = context.dataStore.data.map { prefs ->
        parseGmpWatchRules(prefs[Keys.GMP_WATCH_RULES_JSON])
    }

    val bookmarkedIpoIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.BOOKMARKED_IPO_IDS] ?: emptySet()
    }

    val compactCardsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.COMPACT_CARDS_ENABLED] ?: false
    }

    val biometricLockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false
    }

    suspend fun getNotificationSnapshot(): NotificationPreferencesSnapshot {
        val prefs = context.dataStore.data.first()
        return NotificationPreferencesSnapshot(
            notifyNewIpoEnabled = prefs[Keys.NOTIFY_NEW_IPO] ?: true,
            notifyOpenIpoEnabled = prefs[Keys.NOTIFY_OPEN_IPO] ?: true,
            notifyAllotmentEnabled = prefs[Keys.NOTIFY_ALLOTMENT] ?: true,
            hasSeededBaseline = prefs[Keys.HAS_NOTIFICATION_BASELINE] ?: false,
            knownIpoIds = prefs[Keys.KNOWN_IPO_IDS] ?: emptySet(),
            notifiedOpenedIpoIds = prefs[Keys.NOTIFIED_OPENED_IPO_IDS] ?: emptySet(),
        )
    }

    suspend fun getNewsNotificationSnapshot(): NewsNotificationPreferencesSnapshot {
        val prefs = context.dataStore.data.first()
        return NewsNotificationPreferencesSnapshot(
            notifyNewsAlertsEnabled = prefs[Keys.NOTIFY_NEWS_ALERTS] ?: true,
            enabledCategories = parseNewsCategories(prefs[Keys.NEWS_ALERT_CATEGORIES]),
            hasNewsBaseline = prefs[Keys.HAS_NEWS_BASELINE] ?: false,
            knownNewsIds = prefs[Keys.KNOWN_NEWS_IDS] ?: emptySet(),
            unreadNewsIds = prefs[Keys.UNREAD_NEWS_IDS] ?: emptySet(),
        )
    }

    suspend fun getSmartAlertSnapshot(): SmartAlertPreferencesSnapshot {
        val prefs = context.dataStore.data.first()
        return SmartAlertPreferencesSnapshot(
            day3ReminderIpoIds = prefs[Keys.DAY3_REMINDER_IPO_IDS] ?: emptySet(),
            notifiedDay3ReminderIds = prefs[Keys.NOTIFIED_DAY3_REMINDER_IDS] ?: emptySet(),
            gmpWatchRules = parseGmpWatchRules(prefs[Keys.GMP_WATCH_RULES_JSON]),
        )
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = preference.name
        }
    }

    suspend fun setNotifyNewIpoEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFY_NEW_IPO] = enabled
        }
    }

    suspend fun setNotifyOpenIpoEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFY_OPEN_IPO] = enabled
        }
    }

    suspend fun setNotifyAllotmentEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFY_ALLOTMENT] = enabled
        }
    }

    suspend fun setNotifyNewsAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFY_NEWS_ALERTS] = enabled
        }
    }

    suspend fun setNewsAlertCategories(categories: Set<NewsCategory>) {
        val cleaned = categories.filter { it != NewsCategory.ALL }.toSet()
        val toStore = cleaned.ifEmpty { NewsNotificationPreferencesSnapshot.DEFAULT_NEWS_ALERT_CATEGORIES }
        context.dataStore.edit { prefs ->
            prefs[Keys.NEWS_ALERT_CATEGORIES] = toStore.map { it.name }.toSet()
        }
    }

    suspend fun setDay3Reminder(ipoId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.DAY3_REMINDER_IPO_IDS] ?: emptySet()).toMutableSet()
            val notified = (prefs[Keys.NOTIFIED_DAY3_REMINDER_IDS] ?: emptySet()).toMutableSet()
            if (enabled) {
                current.add(ipoId)
            } else {
                current.remove(ipoId)
                notified.remove(ipoId)
            }
            prefs[Keys.DAY3_REMINDER_IPO_IDS] = current
            prefs[Keys.NOTIFIED_DAY3_REMINDER_IDS] = notified
        }
    }

    suspend fun setGmpWatchRule(
        ipoId: String,
        minGmpPercent: Int?,
        maxGmpPercent: Int?,
    ) {
        context.dataStore.edit { prefs ->
            val map = parseGmpWatchRules(prefs[Keys.GMP_WATCH_RULES_JSON]).toMutableMap()
            if (minGmpPercent == null && maxGmpPercent == null) {
                map.remove(ipoId)
            } else {
                map[ipoId] = GmpWatchRule(
                    ipoId = ipoId,
                    minGmpPercent = minGmpPercent,
                    maxGmpPercent = maxGmpPercent,
                    lastAlertSignature = null,
                )
            }
            prefs[Keys.GMP_WATCH_RULES_JSON] = json.encodeToString(map.values.toList())
        }
    }

    suspend fun updateSmartAlertTracking(
        notifiedDay3ReminderIds: Set<String>,
        updatedGmpWatchRules: Map<String, GmpWatchRule>,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFIED_DAY3_REMINDER_IDS] = notifiedDay3ReminderIds
            prefs[Keys.GMP_WATCH_RULES_JSON] = json.encodeToString(updatedGmpWatchRules.values.toList())
        }
    }

    suspend fun toggleBookmarkIpo(ipoId: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.BOOKMARKED_IPO_IDS] ?: emptySet()).toMutableSet()
            if (!current.add(ipoId)) {
                current.remove(ipoId)
            }
            prefs[Keys.BOOKMARKED_IPO_IDS] = current
        }
    }

    suspend fun setCompactCardsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.COMPACT_CARDS_ENABLED] = enabled
        }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BIOMETRIC_LOCK_ENABLED] = enabled
        }
    }

    suspend fun updateNotificationTracking(
        knownIpoIds: Set<String>,
        notifiedOpenedIpoIds: Set<String>,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_NOTIFICATION_BASELINE] = true
            prefs[Keys.KNOWN_IPO_IDS] = knownIpoIds
            prefs[Keys.NOTIFIED_OPENED_IPO_IDS] = notifiedOpenedIpoIds
        }
    }

    suspend fun updateNewsNotificationTracking(
        knownNewsIds: Set<String>,
        unreadNewsIds: Set<String>,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_NEWS_BASELINE] = true
            prefs[Keys.KNOWN_NEWS_IDS] = knownNewsIds.take(200).toSet()
            prefs[Keys.UNREAD_NEWS_IDS] = unreadNewsIds.take(50).toSet()
        }
    }

    suspend fun markAllNewsRead() {
        context.dataStore.edit { prefs ->
            prefs[Keys.UNREAD_NEWS_IDS] = emptySet()
        }
    }

    suspend fun recordAdImpression() {
        context.dataStore.edit { prefs ->
            prefs[Keys.AD_IMPRESSIONS] = (prefs[Keys.AD_IMPRESSIONS] ?: 0) + 1
        }
    }

    suspend fun incrementDetailOpen(): Int {
        var newCount = 0
        context.dataStore.edit { prefs ->
            newCount = (prefs[Keys.DETAIL_OPENS] ?: 0) + 1
            prefs[Keys.DETAIL_OPENS] = newCount
        }
        return newCount
    }

    private fun parseNewsCategories(raw: Set<String>?): Set<NewsCategory> {
        if (raw.isNullOrEmpty()) return NewsNotificationPreferencesSnapshot.DEFAULT_NEWS_ALERT_CATEGORIES
        val parsed = raw.mapNotNull { name ->
            runCatching { NewsCategory.valueOf(name) }.getOrNull()
        }.filter { it != NewsCategory.ALL }.toSet()
        return parsed.ifEmpty { NewsNotificationPreferencesSnapshot.DEFAULT_NEWS_ALERT_CATEGORIES }
    }

    private fun parseGmpWatchRules(raw: String?): Map<String, GmpWatchRule> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<List<GmpWatchRule>>(raw)
                .filter { it.isActive }
                .associateBy { it.ipoId }
        }.getOrDefault(emptyMap())
    }
}
