package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.BoardType
import com.example.data.model.GmpPoint
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.data.model.ValuationMetrics
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class CloudIpoBundleSnapshot(
    val ipos: List<IpoEntity>,
    val lastSuccessAtMs: Long,
    val sourcesOk: List<String>,
    val sourcesFailed: List<String>,
    val isFromCache: Boolean,
    val documentExists: Boolean = true,
)

/**
 * Reads the pre-scraped IPO catalog bundle from Firestore (`ipo_live_bundle/current`) using a
 * one-shot `get()` (never a realtime snapshot listener) against the named Firestore database.
 */
class LiveIpoCloudRepository(
    private val db: FirebaseFirestore?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val fetchOverride: (suspend () -> CloudIpoBundleSnapshot?)? = null,
) {
    // CRITICAL: Always resolve named database via R.string.firestore_database_id (never default)
    constructor(context: Context) : this(
        db = resolveNamedFirestore(context),
    )

    /**
     * Performs a single one-shot Firestore `get()` on `ipo_live_bundle/current`.
     * Returns null on network/Firestore error or when Firestore is unavailable.
     */
    suspend fun fetchLiveBundleOnce(): CloudIpoBundleSnapshot? {
        fetchOverride?.let { return it() }
        val firestore = db ?: return null
        val docPath = "$COLLECTION_IPO_LIVE_BUNDLE/$DOC_CURRENT"
        return try {
            val snapshot = firestore.collection(COLLECTION_IPO_LIVE_BUNDLE)
                .document(DOC_CURRENT)
                .get()
                .await()
            if (!snapshot.exists()) {
                return CloudIpoBundleSnapshot(
                    ipos = emptyList(),
                    lastSuccessAtMs = 0L,
                    sourcesOk = emptyList(),
                    sourcesFailed = emptyList(),
                    isFromCache = snapshot.metadata.isFromCache,
                    documentExists = false,
                )
            }
            mapDocumentData(
                data = snapshot.data,
                isFromCache = snapshot.metadata.isFromCache,
                nowMs = clockMs(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docPath)
            null
        }
    }

    companion object {
        private const val TAG = "LiveIpoCloudRepo"
        const val COLLECTION_IPO_LIVE_BUNDLE = "ipo_live_bundle"
        const val COLLECTION_IPO_LIVE_META = "ipo_live_meta"
        const val DOC_CURRENT = "current"
        const val DELAYED_DATA_THRESHOLD_MS = 3L * 60L * 60L * 1000L

        private const val DEFAULT_REGISTRAR_NAME = "Official Exchange & Registrar"
        private const val DEFAULT_REGISTRAR_URL =
            "https://www.chittorgarh.com/report/ipo-in-india-list-main-board-sme/82/"
        private const val DEFAULT_LOGO_URL =
            "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange_logo.svg"
        private const val DEFAULT_BANNER_URL =
            "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange.jpg?width=900"

        private val ISO_UTC_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss",
        )

        private fun resolveNamedFirestore(context: Context): FirebaseFirestore? {
            return runCatching {
                val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
                FirebaseFirestore.getInstance(databaseId)
            }.onFailure { error ->
                Log.w(TAG, "Firestore named database unavailable: ${error.message}")
            }.getOrNull()
        }

        /**
         * Manually maps `ipo_live_bundle/current` document data into typed `IpoEntity` items
         * without using Firestore `toObject()` reflection.
         */
        fun mapDocumentData(
            data: Map<String, Any?>?,
            isFromCache: Boolean,
            nowMs: Long = System.currentTimeMillis(),
        ): CloudIpoBundleSnapshot {
            if (data == null) {
                return CloudIpoBundleSnapshot(
                    ipos = emptyList(),
                    lastSuccessAtMs = 0L,
                    sourcesOk = emptyList(),
                    sourcesFailed = emptyList(),
                    isFromCache = isFromCache,
                    documentExists = false,
                )
            }

            val lastSuccessAtMs = parseLastSuccessAtMs(data["lastSuccessAt"])
                .takeIf { it > 0L }
                ?: parseLastSuccessAtMs(data["updatedAt"])

            val sourcesOk = asStringList(data["sourcesOk"])
            val sourcesFailed = asStringList(data["sourcesFailed"])
            val updatedAgoLabel = formatUpdatedAgoLabel(lastSuccessAtMs, nowMs)
            val todayShortLabel = SimpleDateFormat("dd MMM", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }.format(Date(if (lastSuccessAtMs > 0L) lastSuccessAtMs else nowMs))

            val rawIpos = data["ipos"] as? List<*> ?: emptyList<Any?>()
            val mapped = mutableListOf<IpoEntity>()
            val seenIds = HashSet<String>()

            rawIpos.forEachIndexed { index, rawItem ->
                val map = rawItem as? Map<*, *> ?: return@forEachIndexed
                val entity = mapSingleIpoItem(
                    item = map,
                    index = index,
                    updatedAgoLabel = updatedAgoLabel,
                    todayShortLabel = todayShortLabel,
                    fallbackSources = sourcesOk,
                ) ?: return@forEachIndexed
                if (seenIds.add(entity.id)) {
                    mapped.add(entity)
                }
            }

            return CloudIpoBundleSnapshot(
                ipos = mapped,
                lastSuccessAtMs = lastSuccessAtMs,
                sourcesOk = sourcesOk,
                sourcesFailed = sourcesFailed,
                isFromCache = isFromCache,
                documentExists = true,
            )
        }

        private fun mapSingleIpoItem(
            item: Map<*, *>,
            index: Int,
            updatedAgoLabel: String,
            todayShortLabel: String,
            fallbackSources: List<String>,
        ): IpoEntity? {
            val id = asCleanString(item["id"])
            val companyName = asCleanString(item["companyName"])
            if (id.isBlank() || companyName.isBlank()) return null

            val boardTypeRaw = asCleanString(item["boardType"]).uppercase(Locale.US)
            val boardType = if (boardTypeRaw == "SME") BoardType.SME else BoardType.MAINBOARD

            val status = parseIpoStatus(asCleanString(item["status"]))

            val openDate = asCleanString(item["openDate"]).ifBlank { "TBA" }
            val closeDate = asCleanString(item["closeDate"]).ifBlank { "TBA" }
            val listingDate = asCleanString(item["listingDate"]).ifBlank { "TBA" }
            val allotmentDate = asCleanString(item["allotmentDate"]).ifBlank {
                if (status == IpoStatus.CLOSED) "Completed" else "TBA"
            }

            val priceLow = asSafeInt(item["priceLow"]).coerceAtLeast(0)
            val rawPriceHigh = asSafeInt(item["priceHigh"]).coerceAtLeast(0)
            val priceHigh = if (rawPriceHigh >= priceLow) rawPriceHigh else priceLow
            val lotSize = asSafeInt(item["lotSize"]).coerceAtLeast(0)
            val issueSizeCr = asSafeDouble(item["issueSizeCr"]).coerceAtLeast(0.0)
            val gmpRupees = asSafeInt(item["gmpRupees"])

            val qibSubX = asSafeDouble(item["qibSubX"]).coerceAtLeast(0.0)
            val bNiiSubX = asSafeDouble(item["bNiiSubX"]).coerceAtLeast(0.0)
            val sNiiSubX = asSafeDouble(item["sNiiSubX"]).coerceAtLeast(0.0)
            val retailSubX = asSafeDouble(item["retailSubX"]).coerceAtLeast(0.0)
            val employeeSubX = asSafeDouble(item["employeeSubX"]).coerceAtLeast(0.0)
            val totalSubX = asSafeDouble(item["totalSubX"]).coerceAtLeast(0.0)
            val subUpdatedAtMs = asSafeLong(item["subUpdatedAtMs"]).coerceAtLeast(0L)

            val registrarName = asCleanString(item["registrarName"]).ifBlank { DEFAULT_REGISTRAR_NAME }
            val registrarUrl = asCleanString(item["registrarUrl"]).ifBlank { DEFAULT_REGISTRAR_URL }

            val itemSources = asStringList(item["sources"]).ifEmpty { fallbackSources }
            val formattedSources = itemSources
                .map { formatSourceDisplayName(it) }
                .distinct()
                .take(4)
                .joinToString(" • ")
                .ifBlank { "Cloud Sync" }

            val shortCode = companyName
                .replace(Regex("[^A-Za-z0-9]"), "")
                .uppercase(Locale.US)
                .take(10)
                .ifBlank { "IPO" }

            val hasSub = totalSubX > 0.0 || qibSubX > 0.0 || bNiiSubX > 0.0 ||
                sNiiSubX > 0.0 || retailSubX > 0.0 || employeeSubX > 0.0

            val overview = if (priceHigh > 0 && lotSize > 0) {
                "$companyName filing, lot size, issue size, GMP, and subscription tracked via $formattedSources."
            } else {
                "$companyName upcoming IPO tracked via $formattedSources. Official dates, price band, and lot size will update once announced."
            }

            return IpoEntity(
                id = id,
                companyName = companyName,
                shortCode = shortCode,
                sector = if (boardType == BoardType.SME) "SME IPO" else "Mainboard IPO",
                boardType = boardType,
                exchange = if (boardType == BoardType.SME) "NSE SME / BSE SME" else "NSE • BSE",
                status = status,
                openDate = openDate,
                closeDate = closeDate,
                allotmentDate = allotmentDate,
                listingDate = listingDate,
                priceLow = priceLow,
                priceHigh = priceHigh,
                faceValue = 10,
                lotSize = lotSize,
                issueSizeCr = issueSizeCr,
                freshIssueCr = issueSizeCr,
                ofsCr = 0.0,
                gmpRupees = gmpRupees,
                kostakRupees = 0,
                qibSubX = qibSubX,
                bNiiSubX = bNiiSubX,
                sNiiSubX = sNiiSubX,
                retailSubX = retailSubX,
                employeeSubX = employeeSubX,
                totalSubX = totalSubX,
                registrarName = registrarName,
                registrarUrl = registrarUrl,
                leadManagers = listOf("Refer DRHP / RHP Prospectus"),
                overview = overview,
                objectsOfIssue = emptyList(),
                strengths = emptyList(),
                risks = emptyList(),
                financialYears = emptyList(),
                gmpHistory = listOf(
                    GmpPoint("Prev", gmpRupees),
                    GmpPoint(todayShortLabel, gmpRupees),
                ),
                dailySubscriptions = emptyList(),
                valuation = ValuationMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                isWatchlisted = false,
                userLots = 1,
                updatedAgoLabel = updatedAgoLabel,
                gmpSourceLabel = "Verified • $formattedSources",
                sortOrder = index + 1,
                logoUrl = DEFAULT_LOGO_URL,
                bannerImageUrl = DEFAULT_BANNER_URL,
                liveApplicationCounts = emptyList(),
                subscriptionSourceLabel = if (hasSub) formattedSources else "",
                subscriptionUpdatedAtMs = subUpdatedAtMs,
            )
        }

        fun parseLastSuccessAtMs(raw: Any?): Long {
            return when (raw) {
                is Timestamp -> raw.toDate().time
                is Date -> raw.time
                is Number -> raw.toLong().coerceAtLeast(0L)
                is String -> parseIsoTimestampMs(raw)
                else -> 0L
            }
        }

        private fun parseIsoTimestampMs(raw: String): Long {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return 0L
            trimmed.toLongOrNull()?.let { numeric ->
                if (numeric > 1_000_000_000_000L) return numeric
            }
            // Normalize ISO-8601 colon timezone offset (+05:30 -> +0530) for minSdk 24 SimpleDateFormat
            val normalized = trimmed.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            for (pattern in ISO_UTC_PATTERNS) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                        isLenient = false
                    }.parse(normalized)?.time
                }.getOrNull()
                if (parsed != null && parsed > 0L) return parsed
            }
            return 0L
        }

        fun formatUpdatedAgoLabel(
            lastSuccessAtMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): String {
            if (lastSuccessAtMs <= 0L) return "Data updated recently"
            val diffMs = (nowMs - lastSuccessAtMs).coerceAtLeast(0L)
            val diffMinutes = diffMs / 60_000L
            return when {
                diffMinutes < 1L -> "Data updated just now"
                diffMinutes < 60L -> "Data updated $diffMinutes min ago"
                diffMinutes < 24L * 60L -> "Data updated ${diffMinutes / 60L} hr ago"
                else -> {
                    val days = diffMinutes / (24L * 60L)
                    if (days == 1L) "Data updated 1 day ago" else "Data updated $days days ago"
                }
            }
        }

        fun isDataDelayed(
            lastSuccessAtMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            if (lastSuccessAtMs <= 0L) return false
            return (nowMs - lastSuccessAtMs) > DELAYED_DATA_THRESHOLD_MS
        }

        private fun parseIpoStatus(raw: String): IpoStatus {
            return when (raw.trim().uppercase(Locale.US)) {
                "LIVE", "OPEN" -> IpoStatus.LIVE
                "PRE_APPLY", "PRE-APPLY", "PREAPPLY" -> IpoStatus.PRE_APPLY
                "UPCOMING" -> IpoStatus.UPCOMING
                "CLOSED", "LISTED" -> IpoStatus.CLOSED
                else -> IpoStatus.UPCOMING
            }
        }

        private fun formatSourceDisplayName(raw: String): String {
            return when (raw.trim().lowercase(Locale.US)) {
                "nse", "nse india" -> "NSE India"
                "chittorgarh" -> "Chittorgarh"
                "investorgain" -> "InvestorGain"
                "ipowatch" -> "IPOWatch"
                "ipocentral", "ipo central" -> "IPO Central"
                else -> raw.trim()
            }
        }

        private fun asCleanString(value: Any?): String = (value as? String)?.trim().orEmpty()

        private fun asStringList(value: Any?): List<String> {
            val list = value as? List<*> ?: return emptyList()
            return list.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
        }

        private fun asSafeInt(value: Any?): Int {
            return when (value) {
                is Int -> value
                is Long -> value.toInt()
                is Double -> if (value.isFinite()) value.roundToInt() else 0
                is Float -> if (value.isFinite()) value.roundToInt() else 0
                is Number -> value.toInt()
                is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt() ?: 0
                else -> 0
            }
        }

        private fun asSafeLong(value: Any?): Long {
            return when (value) {
                is Long -> value
                is Int -> value.toLong()
                is Double -> if (value.isFinite()) value.toLong() else 0L
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull() ?: 0L
                else -> 0L
            }
        }

        private fun asSafeDouble(value: Any?): Double {
            val parsed = when (value) {
                is Double -> value
                is Float -> value.toDouble()
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            return if (parsed.isFinite()) parsed else 0.0
        }
    }
}
