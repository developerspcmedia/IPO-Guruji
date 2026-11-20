package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoNewsArticle
import com.example.data.model.IpoStatus
import com.example.data.model.NewsCategory
import com.example.data.preferences.GmpWatchRule
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AllotmentCheckEvent
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

data class NotificationEvaluationResult(
    val newIposToAlert: List<IpoEntity>,
    val openedInterestedIposToAlert: List<IpoEntity>,
    val updatedKnownIds: Set<String>,
    val updatedOpenedIds: Set<String>,
)

data class NewsNotificationEvaluationResult(
    val newArticlesToAlert: List<IpoNewsArticle>,
    val updatedKnownNewsIds: Set<String>,
    val updatedUnreadNewsIds: Set<String>,
)

data class GmpThresholdAlert(
    val ipo: IpoEntity,
    val directionLabel: String,
    val thresholdPercent: Int,
    val signature: String,
)

data class SmartIpoAlertEvaluationResult(
    val day3RemindersToAlert: List<IpoEntity>,
    val gmpAlertsToFire: List<GmpThresholdAlert>,
    val updatedNotifiedDay3Ids: Set<String>,
    val updatedGmpWatchRules: Map<String, GmpWatchRule>,
)

object IpoNotificationManager {
    const val CHANNEL_NEW_IPO = "ipo_new_listings"
    const val CHANNEL_INTERESTED_OPEN = "ipo_subscription_open"
    const val CHANNEL_ALLOTMENT_ALERTS = "ipo_allotment_alerts"
    const val CHANNEL_NEWS_ALERTS = "ipo_news_alerts"
    const val CHANNEL_SMART_ALERTS = "ipo_smart_alerts"
    const val EXTRA_IPO_ID = "extra_ipo_id"
    const val EXTRA_OPEN_ALLOTMENT_ALERTS = "extra_open_allotment_alerts"
    const val EXTRA_OPEN_NEWS_TAB = "extra_open_news_tab"
    const val EXTRA_NEWS_ARTICLE_ID = "extra_news_article_id"
    const val SYNC_JOB_ID = 7041
    const val ALLOTMENT_JOB_ID = 7042

    private const val NEW_IPO_NOTIFICATION_BASE_ID = 10_000
    private const val OPEN_IPO_NOTIFICATION_BASE_ID = 20_000
    private const val ALLOTMENT_NOTIFICATION_BASE_ID = 30_000
    private const val NEWS_NOTIFICATION_BASE_ID = 40_000
    private const val SMART_ALERT_NOTIFICATION_BASE_ID = 50_000
    private const val PERIODIC_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val ALLOTMENT_CHECK_INTERVAL_MS = 30L * 60L * 1000L

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val newIpoChannel = NotificationChannel(
            CHANNEL_NEW_IPO,
            "New IPO Listings",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts when a new Mainboard or SME IPO is added to the tracker."
        }

        val openChannel = NotificationChannel(
            CHANNEL_INTERESTED_OPEN,
            "IPO Subscription Open Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "High-priority alerts when an upcoming IPO opens for subscription."
        }

        val allotmentChannel = NotificationChannel(
            CHANNEL_ALLOTMENT_ALERTS,
            "Instant IPO Allotment Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Private instant alerts when an IPO allotment result goes live or shares are allotted."
            enableVibration(true)
        }

        val newsChannel = NotificationChannel(
            CHANNEL_NEWS_ALERTS,
            "Breaking IPO News Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Instant alerts for breaking Mainboard & SME IPO news, GMP buzz, and SEBI filings."
            enableVibration(true)
        }

        val smartAlertsChannel = NotificationChannel(
            CHANNEL_SMART_ALERTS,
            "Day-3 Closing & GMP Threshold Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Smart reminders on IPO Day-3 closing at 2:00 PM IST and custom GMP % breakout/drop alerts."
            enableVibration(true)
        }

        manager.createNotificationChannel(newIpoChannel)
        manager.createNotificationChannel(openChannel)
        manager.createNotificationChannel(allotmentChannel)
        manager.createNotificationChannel(newsChannel)
        manager.createNotificationChannel(smartAlertsChannel)
    }

    fun evaluateNotificationEvents(
        previousIpos: List<IpoEntity>,
        updatedIpos: List<IpoEntity>,
        knownIpoIds: Set<String>,
        notifiedOpenedIds: Set<String>,
        hasSeededBaseline: Boolean,
        notifyNewIpoEnabled: Boolean,
        notifyOpenIpoEnabled: Boolean,
    ): NotificationEvaluationResult {
        val currentIds = updatedIpos.map { it.id }.toSet()
        val previousById = previousIpos.associateBy { it.id }

        val openedInterested = if (hasSeededBaseline && notifyOpenIpoEnabled) {
            updatedIpos.filter { ipo ->
                val prevStatus = previousById[ipo.id]?.status
                val transitionedToOpen = prevStatus != null && prevStatus != IpoStatus.LIVE
                ipo.status == IpoStatus.LIVE &&
                    ipo.id !in notifiedOpenedIds &&
                    transitionedToOpen
            }
        } else {
            emptyList()
        }

        val openedIds = openedInterested.map { it.id }.toSet()

        val newIpos = if (hasSeededBaseline && notifyNewIpoEnabled) {
            updatedIpos.filter { ipo ->
                ipo.id !in knownIpoIds && ipo.id !in openedIds
            }
        } else {
            emptyList()
        }

        val updatedKnown = if (hasSeededBaseline) knownIpoIds + currentIds else currentIds
        val liveNowIds = updatedIpos
            .filter { it.status == IpoStatus.LIVE }
            .map { it.id }
            .toSet()
        val updatedOpened = (notifiedOpenedIds intersect currentIds) + openedIds + liveNowIds

        return NotificationEvaluationResult(
            newIposToAlert = newIpos,
            openedInterestedIposToAlert = openedInterested,
            updatedKnownIds = updatedKnown,
            updatedOpenedIds = updatedOpened,
        )
    }

    suspend fun processSyncNotifications(
        context: Context,
        preferencesRepository: UserPreferencesRepository,
        previousIpos: List<IpoEntity>,
        updatedIpos: List<IpoEntity>,
    ): NotificationEvaluationResult {
        val snapshot = preferencesRepository.getNotificationSnapshot()
        val result = evaluateNotificationEvents(
            previousIpos = previousIpos,
            updatedIpos = updatedIpos,
            knownIpoIds = snapshot.knownIpoIds,
            notifiedOpenedIds = snapshot.notifiedOpenedIpoIds,
            hasSeededBaseline = snapshot.hasSeededBaseline,
            notifyNewIpoEnabled = snapshot.notifyNewIpoEnabled,
            notifyOpenIpoEnabled = snapshot.notifyOpenIpoEnabled,
        )

        preferencesRepository.updateNotificationTracking(
            knownIpoIds = result.updatedKnownIds,
            notifiedOpenedIpoIds = result.updatedOpenedIds,
        )

        if (hasNotificationPermission(context)) {
            ensureChannels(context)
            result.openedInterestedIposToAlert.take(3).forEach { ipo ->
                postSubscriptionOpenNotification(context, ipo)
            }
            result.newIposToAlert.take(3).forEach { ipo ->
                postNewIpoNotification(context, ipo)
            }
        }

        return result
    }

    fun evaluateNewsNotificationEvents(
        articles: List<IpoNewsArticle>,
        knownNewsIds: Set<String>,
        unreadNewsIds: Set<String>,
        hasNewsBaseline: Boolean,
        notifyNewsAlertsEnabled: Boolean,
        enabledCategories: Set<NewsCategory>,
    ): NewsNotificationEvaluationResult {
        val currentIds = articles.map { it.id }.toSet()
        val effectiveCategories = enabledCategories.filter { it != NewsCategory.ALL }.toSet()

        val newMatchingArticles = if (hasNewsBaseline && notifyNewsAlertsEnabled) {
            articles.filter { article ->
                val isNew = article.id !in knownNewsIds
                val matchesCategory = effectiveCategories.isEmpty() || article.category in effectiveCategories
                isNew && matchesCategory
            }
        } else {
            emptyList()
        }

        val updatedKnown = if (hasNewsBaseline) {
            (knownNewsIds + currentIds)
        } else {
            currentIds
        }

        val updatedUnread = if (hasNewsBaseline) {
            (unreadNewsIds intersect currentIds) + newMatchingArticles.map { it.id }
        } else {
            emptySet()
        }

        return NewsNotificationEvaluationResult(
            newArticlesToAlert = newMatchingArticles,
            updatedKnownNewsIds = updatedKnown,
            updatedUnreadNewsIds = updatedUnread,
        )
    }

    suspend fun processNewsSyncNotifications(
        context: Context,
        preferencesRepository: UserPreferencesRepository,
        articles: List<IpoNewsArticle>,
    ): NewsNotificationEvaluationResult {
        val snapshot = preferencesRepository.getNewsNotificationSnapshot()
        val result = evaluateNewsNotificationEvents(
            articles = articles,
            knownNewsIds = snapshot.knownNewsIds,
            unreadNewsIds = snapshot.unreadNewsIds,
            hasNewsBaseline = snapshot.hasNewsBaseline,
            notifyNewsAlertsEnabled = snapshot.notifyNewsAlertsEnabled,
            enabledCategories = snapshot.enabledCategories,
        )

        preferencesRepository.updateNewsNotificationTracking(
            knownNewsIds = result.updatedKnownNewsIds,
            unreadNewsIds = result.updatedUnreadNewsIds,
        )

        if (hasNotificationPermission(context) && result.newArticlesToAlert.isNotEmpty()) {
            ensureChannels(context)
            result.newArticlesToAlert.take(3).forEach { article ->
                postNewsAlertNotification(context, article)
            }
        }

        return result
    }

    fun evaluateSmartIpoAlerts(
        ipos: List<IpoEntity>,
        day3ReminderIpoIds: Set<String>,
        notifiedDay3Ids: Set<String>,
        gmpWatchRules: Map<String, GmpWatchRule>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SmartIpoAlertEvaluationResult {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(ist).apply { timeInMillis = nowEpochMs }
        val nowYear = nowCal.get(Calendar.YEAR)
        val nowDayOfYear = nowCal.get(Calendar.DAY_OF_YEAR)
        val nowHour24 = nowCal.get(Calendar.HOUR_OF_DAY)

        val day3ToFire = mutableListOf<IpoEntity>()
        val updatedNotifiedDay3 = notifiedDay3Ids.toMutableSet()

        ipos.forEach { ipo ->
            if (ipo.id in day3ReminderIpoIds && ipo.id !in notifiedDay3Ids && ipo.status == IpoStatus.LIVE) {
                val closeMs = IpoDateUtils.parseFlexibleDateMs(ipo.closeDate)
                if (closeMs != null) {
                    val closeCal = Calendar.getInstance(ist).apply { timeInMillis = closeMs }
                    val isClosingToday = closeCal.get(Calendar.YEAR) == nowYear &&
                        closeCal.get(Calendar.DAY_OF_YEAR) == nowDayOfYear
                    if (isClosingToday && nowHour24 >= 14) {
                        day3ToFire.add(ipo)
                        updatedNotifiedDay3.add(ipo.id)
                    }
                }
            }
        }

        val gmpAlerts = mutableListOf<GmpThresholdAlert>()
        val updatedRules = gmpWatchRules.toMutableMap()

        ipos.forEach { ipo ->
            val rule = gmpWatchRules[ipo.id] ?: return@forEach
            if (!rule.isActive) return@forEach
            val gmpPctRounded = ipo.gmpPercent.roundToInt()

            val highTarget = rule.maxGmpPercent
            val lowTarget = rule.minGmpPercent

            val matchedAlert = when {
                highTarget != null && gmpPctRounded >= highTarget -> {
                    val sig = "HIGH:$highTarget"
                    if (rule.lastAlertSignature != sig) {
                        GmpThresholdAlert(
                            ipo = ipo,
                            directionLabel = "crossed above +$highTarget%",
                            thresholdPercent = highTarget,
                            signature = sig,
                        )
                    } else {
                        null
                    }
                }

                lowTarget != null && gmpPctRounded <= lowTarget -> {
                    val sig = "LOW:$lowTarget"
                    if (rule.lastAlertSignature != sig) {
                        GmpThresholdAlert(
                            ipo = ipo,
                            directionLabel = "dropped to ${if (lowTarget > 0) "+$lowTarget%" else "$lowTarget%"} or below",
                            thresholdPercent = lowTarget,
                            signature = sig,
                        )
                    } else {
                        null
                    }
                }

                else -> null
            }

            if (matchedAlert != null) {
                gmpAlerts.add(matchedAlert)
                updatedRules[ipo.id] = rule.copy(lastAlertSignature = matchedAlert.signature)
            }
        }

        return SmartIpoAlertEvaluationResult(
            day3RemindersToAlert = day3ToFire,
            gmpAlertsToFire = gmpAlerts,
            updatedNotifiedDay3Ids = updatedNotifiedDay3,
            updatedGmpWatchRules = updatedRules,
        )
    }

    suspend fun processSmartIpoAlerts(
        context: Context,
        preferencesRepository: UserPreferencesRepository,
        ipos: List<IpoEntity>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SmartIpoAlertEvaluationResult {
        val snapshot = preferencesRepository.getSmartAlertSnapshot()
        if (snapshot.day3ReminderIpoIds.isEmpty() && snapshot.gmpWatchRules.isEmpty()) {
            return SmartIpoAlertEvaluationResult(
                day3RemindersToAlert = emptyList(),
                gmpAlertsToFire = emptyList(),
                updatedNotifiedDay3Ids = snapshot.notifiedDay3ReminderIds,
                updatedGmpWatchRules = snapshot.gmpWatchRules,
            )
        }
        val result = evaluateSmartIpoAlerts(
            ipos = ipos,
            day3ReminderIpoIds = snapshot.day3ReminderIpoIds,
            notifiedDay3Ids = snapshot.notifiedDay3ReminderIds,
            gmpWatchRules = snapshot.gmpWatchRules,
            nowEpochMs = nowEpochMs,
        )

        if (result.day3RemindersToAlert.isNotEmpty() || result.gmpAlertsToFire.isNotEmpty()) {
            preferencesRepository.updateSmartAlertTracking(
                notifiedDay3ReminderIds = result.updatedNotifiedDay3Ids,
                updatedGmpWatchRules = result.updatedGmpWatchRules,
            )
            if (hasNotificationPermission(context)) {
                ensureChannels(context)
                result.day3RemindersToAlert.take(3).forEach { ipo ->
                    postDay3ClosingReminderNotification(context, ipo)
                }
                result.gmpAlertsToFire.take(3).forEach { alert ->
                    postGmpThresholdNotification(context, alert)
                }
            }
        }
        return result
    }

    fun postDay3ClosingReminderNotification(context: Context, ipo: IpoEntity) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = SMART_ALERT_NOTIFICATION_BASE_ID + abs(("day3_${ipo.id}").hashCode() % 4_000)
        val qibStr = String.format(Locale.US, "%.2fx", ipo.qibSubX)
        val totalStr = String.format(Locale.US, "%.2fx", ipo.totalSubX)
        val body = "Closes today (${ipo.closeDate}) • QIB: $qibStr • Total: $totalStr • ${formatGmpBadge(ipo)}. Check Day-3 QIB demand before 4:30 PM UPI cutoff."

        val builder = NotificationCompat.Builder(context, CHANNEL_SMART_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Closing Today (2 PM Check): ${ipo.companyName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createDetailPendingIntent(context, ipo.id, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postGmpThresholdNotification(context: Context, alert: GmpThresholdAlert) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val ipo = alert.ipo
        val notificationId = SMART_ALERT_NOTIFICATION_BASE_ID + 4_500 + abs(("gmp_${ipo.id}").hashCode() % 4_000)
        val sign = if (ipo.gmpPercent >= 0) "+" else ""
        val pctLabel = "$sign${String.format(Locale.US, "%.1f%%", ipo.gmpPercent)}"
        val body = "Live GMP is ${formatGmpBadge(ipo)} ($pctLabel) — ${alert.directionLabel}. Est. Listing: ₹${ipo.estimatedListingPrice}."

        val builder = NotificationCompat.Builder(context, CHANNEL_SMART_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GMP Alert: ${ipo.companyName} ($pctLabel)")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createDetailPendingIntent(context, ipo.id, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postNewIpoNotification(context: Context, ipo: IpoEntity) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = NEW_IPO_NOTIFICATION_BASE_ID + abs(ipo.id.hashCode() % 9_000)
        val gmpText = formatGmpBadge(ipo)
        val priceText = if (ipo.priceHigh > 0) "Band ₹${ipo.priceLow}–₹${ipo.priceHigh}" else "Price TBA"
        val body = "${ipo.boardType.displayLabel} IPO • $priceText • $gmpText • Opens ${ipo.openDate}"

        val builder = NotificationCompat.Builder(context, CHANNEL_NEW_IPO)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("New IPO Added: ${ipo.companyName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(createDetailPendingIntent(context, ipo.id, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postSubscriptionOpenNotification(context: Context, ipo: IpoEntity) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = OPEN_IPO_NOTIFICATION_BASE_ID + abs(ipo.id.hashCode() % 9_000)
        val gmpText = formatGmpBadge(ipo)
        val priceText = if (ipo.priceHigh > 0) "₹${ipo.priceLow}–₹${ipo.priceHigh}" else "Price TBA"
        val body = "Subscription open (${ipo.openDate} – ${ipo.closeDate}) • $priceText • $gmpText"

        val builder = NotificationCompat.Builder(context, CHANNEL_INTERESTED_OPEN)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("IPO Open Now: ${ipo.companyName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createDetailPendingIntent(context, ipo.id, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postAllotmentEventNotification(
        context: Context,
        event: AllotmentCheckEvent,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val watch = event.watch
        when {
            event.shouldNotifyAllotted -> postAllottedNotification(context, watch)
            event.shouldNotifyNotAllotted -> postNotAllottedNotification(context, watch)
            event.shouldNotifyLive -> postAllotmentLiveNotification(context, watch)
        }
    }

    fun postAllottedNotification(context: Context, watch: AllotmentWatchEntity) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = ALLOTMENT_NOTIFICATION_BASE_ID + abs(watch.id.hashCode() % 9_000)
        val sharesText = if (watch.sharesAllotted > 0) "${watch.sharesAllotted} shares allotted" else "Shares allotted"
        val body = "${watch.profileName} (${watch.maskedPan}) • $sharesText • Listing ${watch.listingDate}."

        val builder = NotificationCompat.Builder(context, CHANNEL_ALLOTMENT_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("IPO Allotted: ${watch.ipoName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(createAllotmentPendingIntent(context, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postAllotmentLiveNotification(context: Context, watch: AllotmentWatchEntity) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = ALLOTMENT_NOTIFICATION_BASE_ID + abs(watch.id.hashCode() % 9_000)
        val body = "${watch.profileName} (${watch.maskedPan}) • ${watch.registrarName} allotment is now live. Tap to check immediately."

        val builder = NotificationCompat.Builder(context, CHANNEL_ALLOTMENT_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Allotment Live: ${watch.ipoName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(createAllotmentPendingIntent(context, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postNotAllottedNotification(context: Context, watch: AllotmentWatchEntity) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = ALLOTMENT_NOTIFICATION_BASE_ID + abs(watch.id.hashCode() % 9_000)
        val body = "${watch.profileName} (${watch.maskedPan}) • Not allotted. Blocked UPI/ASBA funds unblock by T+2."

        val builder = NotificationCompat.Builder(context, CHANNEL_ALLOTMENT_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Allotment Update: ${watch.ipoName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(createAllotmentPendingIntent(context, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postSampleAllotmentNotification(
        context: Context,
        profileName: String,
        maskedPan: String,
        ipoName: String,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = ALLOTMENT_NOTIFICATION_BASE_ID + 9_999
        val body = "$profileName ($maskedPan) • Private instant allotment alerts are active on this device."

        val builder = NotificationCompat.Builder(context, CHANNEL_ALLOTMENT_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Allotment Alert Ready: $ipoName")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(createAllotmentPendingIntent(context, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postNewsAlertNotification(context: Context, article: IpoNewsArticle) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = NEWS_NOTIFICATION_BASE_ID + abs(article.id.hashCode() % 9_000)
        val title = "${article.category.label}: ${article.title}"
        val body = "${article.source} • ${article.summary}"

        val builder = NotificationCompat.Builder(context, CHANNEL_NEWS_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createNewsPendingIntent(context, article.id, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun postSampleNewsAlertNotification(context: Context, article: IpoNewsArticle) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val notificationId = NEWS_NOTIFICATION_BASE_ID + 9_999
        val title = "IPO News Alert: ${article.title}"
        val body = "${article.source} • ${article.category.label} — Tap to open the News tab."

        val builder = NotificationCompat.Builder(context, CHANNEL_NEWS_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createNewsPendingIntent(context, article.id, notificationId))

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun schedulePeriodicSync(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (scheduler.getPendingJob(SYNC_JOB_ID) != null) return

        val component = ComponentName(context, IpoSyncJobService::class.java)
        val jobInfo = JobInfo.Builder(SYNC_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODIC_INTERVAL_MS)
            .setRequiresBatteryNotLow(true)
            .build()

        runCatching { scheduler.schedule(jobInfo) }
    }

    fun scheduleAllotmentMonitor(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (!enabled) {
            runCatching { scheduler.cancel(ALLOTMENT_JOB_ID) }
            return
        }
        if (scheduler.getPendingJob(ALLOTMENT_JOB_ID) != null) return

        val component = ComponentName(context, AllotmentMonitorJobService::class.java)
        val jobInfo = JobInfo.Builder(ALLOTMENT_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(ALLOTMENT_CHECK_INTERVAL_MS)
            .build()

        runCatching { scheduler.schedule(jobInfo) }
    }

    private fun createDetailPendingIntent(
        context: Context,
        ipoId: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_IPO_ID, ipoId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createAllotmentPendingIntent(
        context: Context,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ALLOTMENT_ALERTS, true)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNewsPendingIntent(
        context: Context,
        articleId: String?,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_NEWS_TAB, true)
            if (!articleId.isNullOrBlank()) {
                putExtra(EXTRA_NEWS_ARTICLE_ID, articleId)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatGmpBadge(ipo: IpoEntity): String {
        val sign = if (ipo.gmpRupees >= 0) "+" else "-"
        return "GMP ${sign}₹${abs(ipo.gmpRupees)}"
    }
}
