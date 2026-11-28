package com.example.data.repository

import android.text.Html
import android.util.Log
import com.example.data.model.BoardType
import com.example.data.model.DaySubscription
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.data.model.LiveApplicationCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class LiveSubscriptionQuote(
    val companyName: String,
    val normalizedKey: String,
    val boardType: BoardType? = null,
    val qibSubX: Double = 0.0,
    val bNiiSubX: Double = 0.0,
    val sNiiSubX: Double = 0.0,
    val niiTotalSubX: Double = 0.0,
    val retailSubX: Double = 0.0,
    val employeeSubX: Double = 0.0,
    val totalSubX: Double = 0.0,
    val sourceName: String,
    val priorityRank: Int,
    val asOfLabel: String = "",
    val symbol: String = "",
    val detailUrl: String = "",
    val dailyHistory: List<DaySubscription> = emptyList(),
    val applicationCounts: List<LiveApplicationCount> = emptyList(),
    val fetchedAtMs: Long = 0L,
) {
    val hasCategoryBreakdown: Boolean
        get() = qibSubX > 0.0 || bNiiSubX > 0.0 || sNiiSubX > 0.0 || niiTotalSubX > 0.0 || retailSubX > 0.0

    val hasAnySubscription: Boolean
        get() = totalSubX > 0.0 || hasCategoryBreakdown || dailyHistory.isNotEmpty() || applicationCounts.isNotEmpty()
}

data class SubscriptionSourceStatus(
    val sourceName: String,
    val ok: Boolean,
    val reason: String = "",
)

data class LiveSubscriptionSnapshot(
    val quotesByKey: Map<String, LiveSubscriptionQuote>,
    val allQuotes: List<LiveSubscriptionQuote>,
    val activeSources: List<String>,
    val syncedAtTime: String,
    val syncedAtMs: Long = System.currentTimeMillis(),
    val sourceStatuses: List<SubscriptionSourceStatus> = emptyList(),
)

/**
 * Dedicated multi-source live IPO category-wise subscription fetcher.
 * Aggregates QIB, bNII (>₹10L), sNII (<₹10L), NII Total, Retail (RII), Employee, Total,
 * Day 1/2/3 progression, and live exchange Application-wise Breakup counts with strict
 * source priority: Official NSE/BSE first, then Chittorgarh, InvestorGain, and IPOWatch.
 */
class LiveSubscriptionRepository(
    private val enableNetworkFetch: Boolean = false,
) {
    private val sessionCookieJar = InMemorySessionCookieJar()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(sessionCookieJar)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var lastSnapshot: LiveSubscriptionSnapshot? = null

    @Volatile
    private var lastNseWarmupMs: Long = 0L

    fun lastSyncedAtMs(): Long = lastSnapshot?.syncedAtMs ?: 0L

    fun isSnapshotStale(
        maxAgeMs: Long = DEFAULT_STALE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val last = lastSnapshot?.syncedAtMs ?: return true
        return (nowMs - last) >= maxAgeMs
    }

    suspend fun fetchLatestSubscriptions(
        forceRefresh: Boolean = true,
        targetSymbol: String? = null,
        targetCompanyName: String? = null,
    ): LiveSubscriptionSnapshot = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val syncTime = formatTimeLabel(nowMs)
        val cached = lastSnapshot
        if (!forceRefresh && cached != null && (nowMs - cached.syncedAtMs) < DEFAULT_STALE_MS) {
            return@withContext cached
        }
        if (!enableNetworkFetch) {
            return@withContext LiveSubscriptionSnapshot(
                quotesByKey = emptyMap(),
                allQuotes = emptyList(),
                activeSources = emptyList(),
                syncedAtTime = syncTime,
                syncedAtMs = nowMs,
            )
        }

        // Warm NSE session cookies before calling NSE JSON endpoints (rate-limited every 10 min)
        withTimeoutOrNull(4_500L) {
            ensureNseSessionWarm(nowMs)
        }

        val now = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = nowMs }
        val month = now.get(Calendar.MONTH) + 1
        val year = now.get(Calendar.YEAR)
        val fyStart = if (month >= 4) year else year - 1
        val fyParam = "$fyStart-${(fyStart + 1) % 100}"
        val monthFormatted = String.format(Locale.US, "%02d", month)

        val prevCal = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val prevMonth = prevCal.get(Calendar.MONTH) + 1
        val prevYear = prevCal.get(Calendar.YEAR)
        val prevFyStart = if (prevMonth >= 4) prevYear else prevYear - 1
        val prevFyParam = "$prevFyStart-${(prevFyStart + 1) % 100}"
        val prevMonthFormatted = String.format(Locale.US, "%02d", prevMonth)

        val investorGainSubUrl =
            "https://webnodejs.investorgain.com/cloud/report/data-read/333/1/$monthFormatted/$year/$fyParam/0/all?search=&v=1&_ts=$nowMs"
        val investorGainPrevSubUrl =
            "https://webnodejs.investorgain.com/cloud/report/data-read/333/1/$prevMonthFormatted/$prevYear/$prevFyParam/0/all?search=&v=1&_ts=$nowMs"

        // Strict source priority: NSE Live Bid (-1) > NSE India (0) > Chittorgarh Detail (1)
        // > Chittorgarh (2) / SME (2) > InvestorGain Detail (3) > InvestorGain (4) > InvestorGain Prev (5) > IPOWatch (6)
        val tasks = mutableListOf(
            SubSourceTask(
                name = "NSE India",
                priorityRank = PRIORITY_NSE_CURRENT,
                url = "https://www.nseindia.com/api/ipo-current-issue",
                kind = SubSourceKind.NSE_CURRENT_JSON,
                referer = "https://www.nseindia.com/market-data/all-upcoming-issues-ipo",
            ),
            SubSourceTask(
                name = "Chittorgarh",
                priorityRank = PRIORITY_CHITTORGARH_SUMMARY,
                url = "https://www.chittorgarh.com/report/ipo-subscription-status-live-bidding-data-bse-nse/21/",
                kind = SubSourceKind.HTML_TABLE,
                defaultBoardType = BoardType.MAINBOARD,
                baseUrl = "https://www.chittorgarh.com",
            ),
            SubSourceTask(
                name = "Chittorgarh SME",
                priorityRank = PRIORITY_CHITTORGARH_SUMMARY,
                url = "https://www.chittorgarh.com/report/sme-ipo-subscription-status-live-bidding-bse-nse/22/",
                kind = SubSourceKind.HTML_TABLE,
                defaultBoardType = BoardType.SME,
                baseUrl = "https://www.chittorgarh.com",
            ),
            SubSourceTask(
                name = "InvestorGain",
                priorityRank = PRIORITY_INVESTORGAIN_SUMMARY,
                url = investorGainSubUrl,
                kind = SubSourceKind.INVESTORGAIN_JSON,
                referer = "https://www.investorgain.com/report/ipo-subscription-live/333/",
            ),
            SubSourceTask(
                name = "InvestorGain Prev",
                priorityRank = PRIORITY_INVESTORGAIN_PREV,
                url = investorGainPrevSubUrl,
                kind = SubSourceKind.INVESTORGAIN_JSON,
                referer = "https://www.investorgain.com/report/ipo-subscription-live/333/",
            ),
            SubSourceTask(
                name = "IPOWatch",
                priorityRank = PRIORITY_IPOWATCH,
                url = "https://ipowatch.in/ipo-subscription-status-today/",
                kind = SubSourceKind.HTML_TABLE,
                baseUrl = "https://ipowatch.in",
            ),
        )

        val cleanedSymbol = targetSymbol?.trim()?.uppercase(Locale.US)
            ?.takeIf { it.length in 2..20 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '&' } }
        if (cleanedSymbol != null && !targetCompanyName.isNullOrBlank()) {
            val encodedSymbol = runCatching { URLEncoder.encode(cleanedSymbol, "UTF-8") }.getOrDefault(cleanedSymbol)
            tasks.add(
                0,
                SubSourceTask(
                    name = "NSE Live Bid",
                    priorityRank = PRIORITY_NSE_LIVE_BID,
                    url = "https://www.nseindia.com/api/ipo-bid-details?symbol=$encodedSymbol",
                    kind = SubSourceKind.NSE_BID_DETAIL_JSON,
                    referer = "https://www.nseindia.com/market-data/issue-information?symbol=$encodedSymbol&series=EQ&type=Active",
                    targetCompanyName = targetCompanyName,
                    targetSymbol = cleanedSymbol,
                ),
            )
        }

        val activeSources = mutableListOf<String>()
        val sourceStatuses = mutableListOf<SubscriptionSourceStatus>()
        val rawQuotes = mutableListOf<LiveSubscriptionQuote>()

        coroutineScope {
            val results = tasks.map { task ->
                async {
                    val fetchResult = withTimeoutOrNull(8_500L) {
                        fetchText(sourceName = task.name, url = task.url, referer = task.referer)
                    }
                    if (fetchResult == null) {
                        Log.w(TAG, "[${task.name}] Timed out fetching ${task.url}")
                        return@async SourceTaskOutcome(
                            status = SubscriptionSourceStatus(task.name, ok = false, reason = "Timeout"),
                            quotes = emptyList(),
                        )
                    }
                    val body = fetchResult.body
                    if (body.isNullOrBlank()) {
                        return@async SourceTaskOutcome(
                            status = SubscriptionSourceStatus(
                                sourceName = task.name,
                                ok = false,
                                reason = fetchResult.errorReason ?: "Empty response",
                            ),
                            quotes = emptyList(),
                        )
                    }

                    val parsed = try {
                        when (task.kind) {
                            SubSourceKind.NSE_CURRENT_JSON -> parseNseSubscriptionJson(
                                rawJson = body,
                                sourceName = task.name,
                                priorityRank = task.priorityRank,
                                asOfLabel = syncTime,
                            )
                            SubSourceKind.NSE_BID_DETAIL_JSON -> parseNseBidDetailsJson(
                                rawJson = body,
                                companyName = task.targetCompanyName.orEmpty(),
                                symbol = task.targetSymbol.orEmpty(),
                                sourceName = task.name,
                                priorityRank = task.priorityRank,
                                asOfLabel = syncTime,
                            )
                            SubSourceKind.INVESTORGAIN_JSON -> parseInvestorGainSubscriptionJson(
                                rawJson = body,
                                sourceName = task.name,
                                priorityRank = task.priorityRank,
                                asOfLabel = syncTime,
                            )
                            SubSourceKind.HTML_TABLE -> parseHtmlSubscriptionTables(
                                html = body,
                                sourceName = task.name,
                                priorityRank = task.priorityRank,
                                asOfLabel = syncTime,
                                defaultBoardType = task.defaultBoardType,
                                baseUrl = task.baseUrl.orEmpty(),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "[${task.name}] Parse error: ${e.javaClass.simpleName}: ${e.message}", e)
                        emptyList()
                    }
                    val status = if (parsed.isNotEmpty()) {
                        SubscriptionSourceStatus(task.name, ok = true)
                    } else {
                        SubscriptionSourceStatus(task.name, ok = false, reason = "No rows parsed")
                    }
                    SourceTaskOutcome(status = status, quotes = parsed)
                }
            }.awaitAll()

            results.forEach { outcome ->
                sourceStatuses.add(outcome.status)
                if (outcome.quotes.isNotEmpty()) {
                    activeSources.add(outcome.status.sourceName)
                    rawQuotes.addAll(outcome.quotes)
                }
            }

            // Stage 2: When inspecting a specific IPO, fetch its per-IPO detail page(s) to extract
            // Day 1 / Day 2 / Day 3 progression and live exchange Application-wise Breakup counts.
            if (!targetCompanyName.isNullOrBlank()) {
                val targetMatches = rawQuotes.filter { quote ->
                    quote.detailUrl.isNotBlank() &&
                        areSameSubscriptionCompany(targetCompanyName, quote.companyName, null, quote.boardType)
                }
                val detailTargets = targetMatches
                    .sortedBy { it.priorityRank }
                    .distinctBy { it.detailUrl }
                    .take(2)

                if (detailTargets.isNotEmpty()) {
                    val detailResults = detailTargets.map { summaryQuote ->
                        async {
                            val isChittorgarh = summaryQuote.detailUrl.contains("chittorgarh.com", ignoreCase = true)
                            val detailSourceName = when {
                                isChittorgarh -> "Chittorgarh Detail"
                                summaryQuote.detailUrl.contains("investorgain.com", ignoreCase = true) -> "InvestorGain Detail"
                                else -> "${summaryQuote.sourceName} Detail"
                            }
                            val fetchResult = withTimeoutOrNull(7_500L) {
                                fetchText(sourceName = detailSourceName, url = summaryQuote.detailUrl)
                            }
                            if (fetchResult == null) {
                                Log.w(TAG, "[$detailSourceName] Timed out fetching ${summaryQuote.detailUrl}")
                                return@async null
                            }
                            val detailHtml = fetchResult.body ?: return@async null
                            val detailPriority = when {
                                isChittorgarh -> PRIORITY_CHITTORGARH_DETAIL
                                summaryQuote.detailUrl.contains("investorgain.com", ignoreCase = true) -> PRIORITY_INVESTORGAIN_DETAIL
                                else -> summaryQuote.priorityRank
                            }
                            try {
                                parseHtmlIpoDetailSubscriptionPage(
                                    html = detailHtml,
                                    companyName = targetCompanyName,
                                    boardType = summaryQuote.boardType,
                                    sourceName = detailSourceName,
                                    priorityRank = detailPriority,
                                    asOfLabel = syncTime,
                                    detailUrl = summaryQuote.detailUrl,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "[$detailSourceName] Detail parse error: ${e.javaClass.simpleName}: ${e.message}", e)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()

                    detailResults.forEach { detailQuote ->
                        if (detailQuote.hasAnySubscription) {
                            activeSources.add(detailQuote.sourceName)
                            rawQuotes.add(detailQuote)
                        }
                    }
                }
            }
        }

        val merged = mergeSubscriptionQuotes(rawQuotes)
        val snapshot = LiveSubscriptionSnapshot(
            quotesByKey = merged.associateBy { it.normalizedKey },
            allQuotes = merged,
            activeSources = activeSources.distinct(),
            syncedAtTime = syncTime,
            syncedAtMs = nowMs,
            sourceStatuses = sourceStatuses,
        )
        if (merged.isNotEmpty()) {
            lastSnapshot = snapshot
        }
        snapshot
    }

    private fun ensureNseSessionWarm(nowMs: Long) {
        if ((nowMs - lastNseWarmupMs) < NSE_WARMUP_INTERVAL_MS && sessionCookieJar.hasCookiesFor("www.nseindia.com")) {
            return
        }
        val homeReq = Request.Builder()
            .url("https://www.nseindia.com")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .build()
        try {
            httpClient.newCall(homeReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "[NSE Warmup] Home returned HTTP ${resp.code}")
                }
                resp.body?.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[NSE Warmup] Home request failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        val ipoPageReq = Request.Builder()
            .url("https://www.nseindia.com/market-data/all-upcoming-issues-ipo")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Referer", "https://www.nseindia.com/")
            .header("Cache-Control", "no-cache")
            .build()
        try {
            httpClient.newCall(ipoPageReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "[NSE Warmup] IPO page returned HTTP ${resp.code}")
                }
                resp.body?.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[NSE Warmup] IPO page request failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        lastNseWarmupMs = nowMs
    }

    internal fun parseInvestorGainSubscriptionJson(
        rawJson: String,
        sourceName: String,
        priorityRank: Int,
        asOfLabel: String = "",
    ): List<LiveSubscriptionQuote> {
        val result = mutableListOf<LiveSubscriptionQuote>()
        runCatching {
            val root = JSONObject(rawJson)
            val rows = root.optJSONArray("reportTableData") ?: return@runCatching
            for (i in 0 until rows.length()) {
                val obj = rows.optJSONObject(i) ?: continue
                val rawName = firstNonBlank(
                    obj.optString("Company Name"),
                    obj.optString("Name"),
                    obj.optString("IPO"),
                    obj.optString("Company"),
                )
                val cleanName = cleanCompanyName(rawName)
                if (cleanName.length < 3) continue

                val categoryHint = firstNonBlank(
                    obj.optString("~IPO_Category"),
                    obj.optString("Category"),
                    rawName,
                )
                val boardType = when {
                    categoryHint.contains("SME", ignoreCase = true) -> BoardType.SME
                    categoryHint.contains("Main", ignoreCase = true) -> BoardType.MAINBOARD
                    else -> null
                }

                val qib = parseSubscriptionMultiplier(
                    firstNonBlank(obj.optString("QIB"), obj.optString("QIB (x)"), obj.optString("QIBs")),
                )
                val bNii = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("bNII"),
                        obj.optString("bNII (x)"),
                        obj.optString("BNII"),
                        obj.optString("BHNI"),
                        obj.optString("bHNI"),
                        obj.optString("Big NII"),
                        obj.optString("NII (>10L)"),
                    ),
                )
                val sNii = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("sNII"),
                        obj.optString("sNII (x)"),
                        obj.optString("SNII"),
                        obj.optString("SHNI"),
                        obj.optString("sHNI"),
                        obj.optString("Small NII"),
                        obj.optString("NII (<10L)"),
                    ),
                )
                val nii = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("NII"),
                        obj.optString("NII (x)"),
                        obj.optString("HNI"),
                        obj.optString("SHNI/BHNI"),
                    ),
                )
                val retail = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("RII"),
                        obj.optString("RII (x)"),
                        obj.optString("Retail"),
                        obj.optString("Retail (x)"),
                    ),
                )
                val emp = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("EMP"),
                        obj.optString("Employee"),
                        obj.optString("Employees"),
                    ),
                )
                val total = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("Total"),
                        obj.optString("Total (x)"),
                        obj.optString("Subscription"),
                        obj.optString("Sub"),
                    ),
                )

                val totalApps = parseApplicationCount(
                    firstNonBlank(
                        obj.optString("Total Applications"),
                        obj.optString("Applications"),
                        obj.optString("No of Applications"),
                        obj.optString("No. of Applications"),
                    ),
                )
                val appCounts = if (totalApps > 0L) {
                    listOf(
                        LiveApplicationCount(
                            categoryId = "total",
                            categoryLabel = "Total",
                            appliedApplications = totalApps,
                            subscriptionX = roundTwoDecimals(total),
                        ),
                    )
                } else {
                    emptyList()
                }

                val folderSlug = firstNonBlank(
                    obj.optString("~urlrewrite_folder_name"),
                    obj.optString("urlrewrite_folder_name"),
                )
                val igId = firstNonBlank(obj.optString("~id"), obj.optString("id"))
                val detailUrl = extractHrefFromHtml(rawName, "https://www.investorgain.com").ifBlank {
                    when {
                        folderSlug.startsWith("http", ignoreCase = true) -> folderSlug
                        folderSlug.startsWith("/") -> "https://www.investorgain.com$folderSlug"
                        folderSlug.isNotBlank() && igId.isNotBlank() ->
                            "https://www.investorgain.com/subscription/$folderSlug/$igId/"
                        else -> ""
                    }
                }

                val quote = buildNormalizedQuote(
                    companyName = cleanName,
                    boardType = boardType,
                    qib = qib,
                    bNii = bNii,
                    sNii = sNii,
                    niiTotal = nii,
                    retail = retail,
                    employee = emp,
                    total = total,
                    sourceName = sourceName,
                    priorityRank = priorityRank,
                    asOfLabel = asOfLabel,
                    detailUrl = detailUrl,
                    applicationCounts = appCounts,
                )
                if (quote.hasAnySubscription) {
                    result.add(quote)
                }
            }
        }
        return result
    }

    internal fun parseNseSubscriptionJson(
        rawJson: String,
        sourceName: String,
        priorityRank: Int,
        asOfLabel: String = "",
    ): List<LiveSubscriptionQuote> {
        val result = mutableListOf<LiveSubscriptionQuote>()
        runCatching {
            val trimmed = rawJson.trim()
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("data") ?: obj.optJSONArray("active") ?: JSONArray()
                }
                else -> JSONArray()
            }
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val rawName = firstNonBlank(
                    obj.optString("companyName"),
                    obj.optString("company_name"),
                    obj.optString("name"),
                )
                val cleanName = cleanCompanyName(rawName)
                if (cleanName.length < 3) continue

                val series = obj.optString("series").uppercase(Locale.US)
                val boardType = if (series == "SME" || rawName.contains("SME", ignoreCase = true)) {
                    BoardType.SME
                } else {
                    BoardType.MAINBOARD
                }
                val symbol = obj.optString("symbol").trim().uppercase(Locale.US)
                val qib = parseSubscriptionMultiplier(firstNonBlank(obj.optString("qib"), obj.optString("qibNoOfTime")))
                val bNii = parseSubscriptionMultiplier(firstNonBlank(obj.optString("bnii"), obj.optString("bNii")))
                val sNii = parseSubscriptionMultiplier(firstNonBlank(obj.optString("snii"), obj.optString("sNii")))
                val nii = parseSubscriptionMultiplier(firstNonBlank(obj.optString("nii"), obj.optString("hni")))
                val retail = parseSubscriptionMultiplier(firstNonBlank(obj.optString("retail"), obj.optString("rii")))
                val emp = parseSubscriptionMultiplier(firstNonBlank(obj.optString("emp"), obj.optString("employee")))
                val total = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("noOfTime"),
                        obj.optString("totalSubscription"),
                        obj.optString("subscription"),
                    ),
                )
                val totalApps = parseApplicationCount(
                    firstNonBlank(
                        obj.optString("noOfapplication"),
                        obj.optString("noOfApplications"),
                        obj.optString("applications"),
                    ),
                )
                val appCounts = if (totalApps > 0L) {
                    listOf(
                        LiveApplicationCount(
                            categoryId = "total",
                            categoryLabel = "Total",
                            appliedApplications = totalApps,
                            subscriptionX = roundTwoDecimals(total),
                        ),
                    )
                } else {
                    emptyList()
                }

                val quote = buildNormalizedQuote(
                    companyName = cleanName,
                    boardType = boardType,
                    qib = qib,
                    bNii = bNii,
                    sNii = sNii,
                    niiTotal = nii,
                    retail = retail,
                    employee = emp,
                    total = total,
                    sourceName = sourceName,
                    priorityRank = priorityRank,
                    asOfLabel = asOfLabel,
                    symbol = symbol,
                    applicationCounts = appCounts,
                )
                if (quote.hasAnySubscription) {
                    result.add(quote)
                }
            }
        }
        return result
    }

    /**
     * Parses NSE's per-symbol `/api/ipo-bid-details?symbol=...` JSON payload containing category rows
     * (Qualified Institutional Buyers, Non Institutional Investors, Retail Individual Investors, Employees, Total)
     * including live exchange application counts (`noOfapplication` / `noOfApplications`).
     */
    internal fun parseNseBidDetailsJson(
        rawJson: String,
        companyName: String,
        symbol: String,
        sourceName: String,
        priorityRank: Int,
        asOfLabel: String = "",
    ): List<LiveSubscriptionQuote> {
        val cleanName = cleanCompanyName(companyName)
        if (cleanName.length < 3) return emptyList()
        var qib = 0.0
        var bNii = 0.0
        var sNii = 0.0
        var nii = 0.0
        var retail = 0.0
        var emp = 0.0
        var total = 0.0
        val appCountsById = LinkedHashMap<String, LiveApplicationCount>()

        runCatching {
            val root = JSONObject(rawJson)
            val rows = root.optJSONArray("dataList")
                ?: root.optJSONObject("data")?.optJSONArray("dataList")
                ?: root.optJSONArray("data")
                ?: return@runCatching

            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val category = firstNonBlank(
                    row.optString("category"),
                    row.optString("investorCategory"),
                    row.optString("name"),
                ).lowercase(Locale.US)
                val times = parseSubscriptionMultiplier(
                    firstNonBlank(
                        row.optString("noOfTime"),
                        row.optString("times"),
                        row.optString("subscription"),
                    ),
                )
                val appliedApps = parseApplicationCount(
                    firstNonBlank(
                        row.optString("noOfapplication"),
                        row.optString("noOfApplications"),
                        row.optString("noOfApplication"),
                        row.optString("applications"),
                        row.optString("noOfBids"),
                    ),
                )
                val reservedApps = parseApplicationCount(
                    firstNonBlank(
                        row.optString("reservedApplications"),
                        row.optString("noOfReservedApplications"),
                    ),
                )
                if (times <= 0.0 && appliedApps <= 0L) continue

                when {
                    category.contains("total") -> {
                        if (times > 0.0) total = times
                        if (appliedApps > 0L) {
                            appCountsById["total"] = LiveApplicationCount(
                                categoryId = "total",
                                categoryLabel = "Total",
                                reservedApplications = reservedApps,
                                appliedApplications = appliedApps,
                                subscriptionX = roundTwoDecimals(times),
                            )
                        }
                    }
                    category.contains("qualified") || category.contains("qib") -> {
                        if (times > 0.0) qib = times
                    }
                    isBigNiiCategory(category) -> {
                        if (times > 0.0) bNii = times
                        if (appliedApps > 0L) {
                            appCountsById["bhni"] = LiveApplicationCount(
                                categoryId = "bhni",
                                categoryLabel = "Big HNI",
                                reservedApplications = reservedApps,
                                appliedApplications = appliedApps,
                                subscriptionX = roundTwoDecimals(times),
                            )
                        }
                    }
                    isSmallNiiCategory(category) -> {
                        if (times > 0.0) sNii = times
                        if (appliedApps > 0L) {
                            appCountsById["shni"] = LiveApplicationCount(
                                categoryId = "shni",
                                categoryLabel = "S-HNI",
                                reservedApplications = reservedApps,
                                appliedApplications = appliedApps,
                                subscriptionX = roundTwoDecimals(times),
                            )
                        }
                    }
                    category.contains("non institutional") || category.contains("nii") || category.contains("hni") -> {
                        if (times > 0.0) nii = times
                    }
                    category.contains("retail") || category.contains("rii") -> {
                        if (times > 0.0) retail = times
                        if (appliedApps > 0L) {
                            appCountsById["retail"] = LiveApplicationCount(
                                categoryId = "retail",
                                categoryLabel = "Retail",
                                reservedApplications = reservedApps,
                                appliedApplications = appliedApps,
                                subscriptionX = roundTwoDecimals(times),
                            )
                        }
                    }
                    category.contains("employee") || category.contains("emp") -> {
                        if (times > 0.0) emp = times
                        if (appliedApps > 0L) {
                            appCountsById["employee"] = LiveApplicationCount(
                                categoryId = "employee",
                                categoryLabel = "Employee",
                                reservedApplications = reservedApps,
                                appliedApplications = appliedApps,
                                subscriptionX = roundTwoDecimals(times),
                            )
                        }
                    }
                    category.contains("shareholder") -> {
                        if (appliedApps > 0L) {
                            appCountsById["shareholder"] = LiveApplicationCount(
                                categoryId = "shareholder",
                                categoryLabel = "Shareholder",
                                reservedApplications = reservedApps,
                                appliedApplications = appliedApps,
                                subscriptionX = roundTwoDecimals(times),
                            )
                        }
                    }
                }
            }
        }

        val quote = buildNormalizedQuote(
            companyName = cleanName,
            boardType = null,
            qib = qib,
            bNii = bNii,
            sNii = sNii,
            niiTotal = nii,
            retail = retail,
            employee = emp,
            total = total,
            sourceName = sourceName,
            priorityRank = priorityRank,
            asOfLabel = asOfLabel,
            symbol = symbol,
            applicationCounts = appCountsById.values.toList(),
        )
        return if (quote.hasAnySubscription) listOf(quote) else emptyList()
    }

    internal fun parseHtmlSubscriptionTables(
        html: String,
        sourceName: String,
        priorityRank: Int,
        asOfLabel: String = "",
        defaultBoardType: BoardType? = null,
        baseUrl: String = "",
    ): List<LiveSubscriptionQuote> {
        val result = mutableListOf<LiveSubscriptionQuote>()
        val tableRegex = Regex("<table[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        tableRegex.findAll(html).forEach { tableMatch ->
            val rawRows = rowRegex.findAll(tableMatch.groupValues[1]).map { rowMatch ->
                cellRegex.findAll(rowMatch.groupValues[1])
                    .map { cellMatch ->
                        val rawCell = cellMatch.groupValues[1]
                        rawCell to stripHtml(rawCell)
                    }
                    .toList()
            }.filter { it.size >= 4 }.toList()
            if (rawRows.size < 2) return@forEach

            var nameIdx = -1
            var qibIdx = -1
            var bNiiIdx = -1
            var sNiiIdx = -1
            var niiIdx = -1
            var retailIdx = -1
            var empIdx = -1
            var totalIdx = -1
            var appsIdx = -1
            var headerRowIndex = -1

            rawRows.take(3).forEachIndexed { rIdx, row ->
                val lower = row.map { it.second.lowercase(Locale.US) }
                val foundName = lower.indexOfFirst { it.contains("company") || it.contains("ipo") || it.contains("name") }
                val foundQib = lower.indexOfFirst { it.contains("qib") && !isApplicationCountHeader(it) }
                val foundRetail = lower.indexOfFirst {
                    (it.contains("retail") || it == "rii" || it.contains("rii")) && !isApplicationCountHeader(it)
                }
                val foundTotal = lower.indexOfFirst { isTotalMultiplierHeader(it) }
                if (foundName >= 0 && (foundQib >= 0 || foundRetail >= 0 || foundTotal >= 0)) {
                    nameIdx = foundName
                    qibIdx = foundQib
                    bNiiIdx = lower.indexOfFirst { isBigNiiHeader(it) && !isApplicationCountHeader(it) }
                    sNiiIdx = lower.indexOfFirst { isSmallNiiHeader(it) && !isApplicationCountHeader(it) }
                    niiIdx = lower.indexOfFirst {
                        (it == "nii" || it.startsWith("nii") || it.contains("hni")) &&
                            !isBigNiiHeader(it) &&
                            !isSmallNiiHeader(it) &&
                            !isApplicationCountHeader(it)
                    }
                    retailIdx = foundRetail
                    empIdx = lower.indexOfFirst { it.contains("emp") && !isApplicationCountHeader(it) }
                    totalIdx = foundTotal
                    appsIdx = lower.indexOfFirst { isApplicationCountHeader(it) }
                    headerRowIndex = rIdx
                }
            }
            if (headerRowIndex < 0 || nameIdx < 0) return@forEach

            rawRows.drop(headerRowIndex + 1).forEach { cells ->
                val rawNameCellHtml = cells.getOrNull(nameIdx)?.first.orEmpty()
                val rawName = cells.getOrNull(nameIdx)?.second.orEmpty()
                val cleanName = cleanCompanyName(rawName)
                if (cleanName.length < 3 || cleanName.equals("Company Name", ignoreCase = true)) return@forEach

                val boardType = when {
                    rawName.contains("SME", ignoreCase = true) -> BoardType.SME
                    defaultBoardType != null -> defaultBoardType
                    else -> null
                }

                val qib = qibIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)?.second) } ?: 0.0
                val bNii = bNiiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)?.second) } ?: 0.0
                val sNii = sNiiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)?.second) } ?: 0.0
                val nii = niiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)?.second) } ?: 0.0
                val retail = retailIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)?.second) } ?: 0.0
                val emp = empIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)?.second) } ?: 0.0
                val total = totalIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)?.second) } ?: 0.0
                val totalApps = appsIdx.takeIf { it >= 0 }?.let { parseApplicationCount(cells.getOrNull(it)?.second) } ?: 0L

                val detailUrl = extractHrefFromHtml(rawNameCellHtml, baseUrl)
                val appCounts = if (totalApps > 0L) {
                    listOf(
                        LiveApplicationCount(
                            categoryId = "total",
                            categoryLabel = "Total",
                            appliedApplications = totalApps,
                            subscriptionX = roundTwoDecimals(total),
                        ),
                    )
                } else {
                    emptyList()
                }

                val quote = buildNormalizedQuote(
                    companyName = cleanName,
                    boardType = boardType,
                    qib = qib,
                    bNii = bNii,
                    sNii = sNii,
                    niiTotal = nii,
                    retail = retail,
                    employee = emp,
                    total = total,
                    sourceName = sourceName,
                    priorityRank = priorityRank,
                    asOfLabel = asOfLabel,
                    detailUrl = detailUrl,
                    applicationCounts = appCounts,
                )
                if (quote.hasAnySubscription) {
                    result.add(quote)
                }
            }
        }
        return result
    }

    /**
     * Parses a per-IPO subscription detail HTML page (e.g. Chittorgarh / InvestorGain IPO subscription page)
     * to extract:
     * 1. Day-by-day subscription rows (Day 1, Day 2, Day 3 with date, QIB, bNII, sNII, NII, Retail, Total).
     * 2. Live Application-wise Breakup counts (Retail, S-HNI, Big HNI, Employee, Shareholder, Total).
     * 3. Category-wise live subscription summary when present.
     */
    internal fun parseHtmlIpoDetailSubscriptionPage(
        html: String,
        companyName: String,
        boardType: BoardType? = null,
        sourceName: String,
        priorityRank: Int,
        asOfLabel: String = "",
        detailUrl: String = "",
    ): LiveSubscriptionQuote? {
        val cleanName = cleanCompanyName(companyName)
        if (cleanName.length < 3) return null

        val tableRegex = Regex("<table[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        var parsedDailyRows = emptyList<DaySubscription>()
        val appCountsById = LinkedHashMap<String, LiveApplicationCount>()
        var summaryQib = 0.0
        var summaryBNii = 0.0
        var summarySNii = 0.0
        var summaryNii = 0.0
        var summaryRetail = 0.0
        var summaryEmp = 0.0
        var summaryTotal = 0.0

        tableRegex.findAll(html).forEach { tableMatch ->
            val rows = rowRegex.findAll(tableMatch.groupValues[1]).map { rowMatch ->
                cellRegex.findAll(rowMatch.groupValues[1])
                    .map { stripHtml(it.groupValues[1]) }
                    .toList()
            }.filter { it.size >= 3 }.toList()
            if (rows.size < 2) return@forEach

            // 1. Check for Day-by-Day Subscription Table
            var dayHeaderIdx = -1
            var dayColIdx = -1
            var dateColIdx = -1
            var qibIdx = -1
            var bNiiIdx = -1
            var sNiiIdx = -1
            var niiIdx = -1
            var retailIdx = -1
            var empIdx = -1
            var totalIdx = -1

            rows.take(3).forEachIndexed { rIdx, row ->
                val lower = row.map { it.lowercase(Locale.US) }
                val foundDay = lower.indexOfFirst { it == "day" || it.contains("day") || it.contains("as on") || it.contains("date") }
                val foundDate = lower.indexOfFirst { it.contains("date") && !it.contains("update") }
                val foundQib = lower.indexOfFirst { it.contains("qib") && !isApplicationCountHeader(it) }
                val foundRetail = lower.indexOfFirst {
                    (it.contains("retail") || it == "rii" || it.contains("rii")) && !isApplicationCountHeader(it)
                }
                val foundBNii = lower.indexOfFirst { isBigNiiHeader(it) && !isApplicationCountHeader(it) }
                val foundSNii = lower.indexOfFirst { isSmallNiiHeader(it) && !isApplicationCountHeader(it) }
                val foundNii = lower.indexOfFirst {
                    (it == "nii" || it.startsWith("nii") || it.contains("hni")) &&
                        !isBigNiiHeader(it) && !isSmallNiiHeader(it) && !isApplicationCountHeader(it)
                }
                val foundTotal = lower.indexOfFirst { isTotalMultiplierHeader(it) }
                val metricCount = listOf(foundQib, foundRetail, foundNii, foundBNii, foundTotal).count { it >= 0 }
                if (foundDay >= 0 && metricCount >= 2 && lower.none { it.contains("company") }) {
                    dayHeaderIdx = rIdx
                    dayColIdx = foundDay
                    dateColIdx = if (foundDate >= 0 && foundDate != foundDay) foundDate else -1
                    qibIdx = foundQib
                    bNiiIdx = foundBNii
                    sNiiIdx = foundSNii
                    niiIdx = foundNii
                    retailIdx = foundRetail
                    empIdx = lower.indexOfFirst { it.contains("emp") && !isApplicationCountHeader(it) }
                    totalIdx = foundTotal
                }
            }

            if (dayHeaderIdx >= 0) {
                val collectedDays = mutableListOf<DaySubscription>()
                rows.drop(dayHeaderIdx + 1).forEach { cells ->
                    val rawDayCell = cells.getOrNull(dayColIdx).orEmpty().trim()
                    val lowerDay = rawDayCell.lowercase(Locale.US)
                    if (rawDayCell.isBlank() ||
                        lowerDay.contains("shares") ||
                        lowerDay.contains("offered") ||
                        lowerDay.contains("application") ||
                        lowerDay.contains("total")
                    ) {
                        return@forEach
                    }

                    val qib = qibIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                    val bNii = bNiiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                    val sNii = sNiiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                    val niiRaw = niiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                    val retail = retailIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                    val total = totalIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                    if (qib <= 0.0 && bNii <= 0.0 && sNii <= 0.0 && niiRaw <= 0.0 && retail <= 0.0 && total <= 0.0) {
                        return@forEach
                    }

                    val (dayLabel, extractedDate) = extractDayAndDateLabel(
                        rawDayCell = rawDayCell,
                        explicitDateCell = dateColIdx.takeIf { it >= 0 }?.let { cells.getOrNull(it) }.orEmpty(),
                        fallbackDayNumber = collectedDays.size + 1,
                    )
                    val resolvedNii = when {
                        niiRaw > 0.0 -> niiRaw
                        bNii > 0.0 || sNii > 0.0 -> roundTwoDecimals((bNii * 2.0 + sNii) / 3.0)
                        else -> 0.0
                    }
                    val resolvedBNii = if (bNii > 0.0) bNii else resolvedNii
                    val resolvedSNii = if (sNii > 0.0) sNii else resolvedNii

                    collectedDays.add(
                        DaySubscription(
                            dayLabel = dayLabel,
                            qibX = roundTwoDecimals(qib),
                            niiX = roundTwoDecimals(resolvedNii),
                            retailX = roundTwoDecimals(retail),
                            totalX = roundTwoDecimals(total),
                            dateLabel = extractedDate,
                            bNiiX = roundTwoDecimals(resolvedBNii),
                            sNiiX = roundTwoDecimals(resolvedSNii),
                        ),
                    )
                }
                if (collectedDays.size > parsedDailyRows.size) {
                    parsedDailyRows = collectedDays
                }
                return@forEach
            }

            // 2. Check for Application-Wise Breakup OR Category Live Subscription Table
            var catHeaderIdx = -1
            var catColIdx = -1
            var reservedColIdx = -1
            var appliedColIdx = -1
            var subColIdx = -1

            rows.take(3).forEachIndexed { rIdx, row ->
                val lower = row.map { it.lowercase(Locale.US) }
                val foundCat = lower.indexOfFirst {
                    it.contains("category") || it.contains("investor") || it.contains("quota") || it == "type"
                }
                val foundReserved = lower.indexOfFirst {
                    (it.contains("reserved") || it.contains("offered")) && !it.contains("share")
                }
                val foundApplied = lower.withIndex().firstOrNull { (idx, header) ->
                    idx != foundReserved &&
                        !header.contains("reserved") &&
                        !header.contains("offered") &&
                        (header.contains("applied") || isApplicationCountHeader(header) || header.contains("received"))
                }?.index ?: -1
                val foundSub = lower.indexOfFirst {
                    (it.contains("subscription") || it.contains("times") || it.contains("(x)")) &&
                        !isApplicationCountHeader(it)
                }
                if (foundCat >= 0 && (foundApplied >= 0 || foundSub >= 0)) {
                    catHeaderIdx = rIdx
                    catColIdx = foundCat
                    reservedColIdx = foundReserved
                    appliedColIdx = foundApplied
                    subColIdx = foundSub
                }
            }

            if (catHeaderIdx >= 0) {
                rows.drop(catHeaderIdx + 1).forEach { cells ->
                    val rawCategory = cells.getOrNull(catColIdx).orEmpty().trim()
                    val cat = rawCategory.lowercase(Locale.US)
                    if (cat.isBlank()) return@forEach

                    val subX = subColIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                    val reservedApps = reservedColIdx.takeIf { it >= 0 }?.let { parseApplicationCount(cells.getOrNull(it)) } ?: 0L
                    val appliedApps = appliedColIdx.takeIf { it >= 0 }?.let { parseApplicationCount(cells.getOrNull(it)) } ?: 0L

                    if (subX <= 0.0 && appliedApps <= 0L) return@forEach

                    when {
                        cat.contains("total") -> {
                            if (subX > 0.0) summaryTotal = subX
                            if (appliedApps > 0L) {
                                appCountsById.putIfAbsent(
                                    "total",
                                    LiveApplicationCount(
                                        categoryId = "total",
                                        categoryLabel = "Total",
                                        reservedApplications = reservedApps,
                                        appliedApplications = appliedApps,
                                        subscriptionX = roundTwoDecimals(subX),
                                    ),
                                )
                            }
                        }
                        cat.contains("qualified") || cat.contains("qib") -> {
                            if (subX > 0.0) summaryQib = subX
                        }
                        isBigNiiCategory(cat) -> {
                            if (subX > 0.0) summaryBNii = subX
                            if (appliedApps > 0L) {
                                appCountsById.putIfAbsent(
                                    "bhni",
                                    LiveApplicationCount(
                                        categoryId = "bhni",
                                        categoryLabel = "Big HNI",
                                        reservedApplications = reservedApps,
                                        appliedApplications = appliedApps,
                                        subscriptionX = roundTwoDecimals(subX),
                                    ),
                                )
                            }
                        }
                        isSmallNiiCategory(cat) -> {
                            if (subX > 0.0) summarySNii = subX
                            if (appliedApps > 0L) {
                                appCountsById.putIfAbsent(
                                    "shni",
                                    LiveApplicationCount(
                                        categoryId = "shni",
                                        categoryLabel = "S-HNI",
                                        reservedApplications = reservedApps,
                                        appliedApplications = appliedApps,
                                        subscriptionX = roundTwoDecimals(subX),
                                    ),
                                )
                            }
                        }
                        cat.contains("non institutional") || cat.contains("nii") || cat.contains("hni") -> {
                            if (subX > 0.0) summaryNii = subX
                        }
                        cat.contains("retail") || cat.contains("rii") -> {
                            if (subX > 0.0) summaryRetail = subX
                            if (appliedApps > 0L) {
                                appCountsById.putIfAbsent(
                                    "retail",
                                    LiveApplicationCount(
                                        categoryId = "retail",
                                        categoryLabel = "Retail",
                                        reservedApplications = reservedApps,
                                        appliedApplications = appliedApps,
                                        subscriptionX = roundTwoDecimals(subX),
                                    ),
                                )
                            }
                        }
                        cat.contains("employee") || cat.contains("emp") -> {
                            if (subX > 0.0) summaryEmp = subX
                            if (appliedApps > 0L) {
                                appCountsById.putIfAbsent(
                                    "employee",
                                    LiveApplicationCount(
                                        categoryId = "employee",
                                        categoryLabel = "Employee",
                                        reservedApplications = reservedApps,
                                        appliedApplications = appliedApps,
                                        subscriptionX = roundTwoDecimals(subX),
                                    ),
                                )
                            }
                        }
                        cat.contains("shareholder") -> {
                            if (appliedApps > 0L) {
                                appCountsById.putIfAbsent(
                                    "shareholder",
                                    LiveApplicationCount(
                                        categoryId = "shareholder",
                                        categoryLabel = "Shareholder",
                                        reservedApplications = reservedApps,
                                        appliedApplications = appliedApps,
                                        subscriptionX = roundTwoDecimals(subX),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        val lastDay = parsedDailyRows.lastOrNull()
        val resolvedQib = if (summaryQib > 0.0) summaryQib else (lastDay?.qibX ?: 0.0)
        val resolvedBNii = if (summaryBNii > 0.0) summaryBNii else (lastDay?.bNiiX ?: 0.0)
        val resolvedSNii = if (summarySNii > 0.0) summarySNii else (lastDay?.sNiiX ?: 0.0)
        val resolvedNii = if (summaryNii > 0.0) summaryNii else (lastDay?.niiX ?: 0.0)
        val resolvedRetail = if (summaryRetail > 0.0) summaryRetail else (lastDay?.retailX ?: 0.0)
        val resolvedTotal = if (summaryTotal > 0.0) summaryTotal else (lastDay?.totalX ?: 0.0)

        val quote = buildNormalizedQuote(
            companyName = cleanName,
            boardType = boardType,
            qib = resolvedQib,
            bNii = resolvedBNii,
            sNii = resolvedSNii,
            niiTotal = resolvedNii,
            retail = resolvedRetail,
            employee = summaryEmp,
            total = resolvedTotal,
            sourceName = sourceName,
            priorityRank = priorityRank,
            asOfLabel = asOfLabel,
            detailUrl = detailUrl,
            dailyHistory = parsedDailyRows,
            applicationCounts = appCountsById.values.toList(),
        )
        return quote.takeIf { it.hasAnySubscription }
    }

    internal fun isValidQuoteNumbers(quote: LiveSubscriptionQuote): Boolean {
        val metrics = listOf(
            quote.qibSubX,
            quote.bNiiSubX,
            quote.sNiiSubX,
            quote.niiTotalSubX,
            quote.retailSubX,
            quote.employeeSubX,
            quote.totalSubX,
        )
        if (metrics.any { !it.isFinite() || it < 0.0 }) return false
        for (day in quote.dailyHistory) {
            val dayMetrics = listOf(day.qibX, day.niiX, day.retailX, day.totalX, day.bNiiX, day.sNiiX)
            if (dayMetrics.any { !it.isFinite() || it < 0.0 }) return false
        }
        for (count in quote.applicationCounts) {
            if (!count.subscriptionX.isFinite() || count.subscriptionX < 0.0) return false
            if (count.reservedApplications < 0L || count.appliedApplications < 0L) return false
        }
        return true
    }

    /**
     * Picks ONE winning quote per IPO by freshness (`fetchedAtMs DESC`) and then strict source priority
     * (`priorityRank ASC`). Category and total subscription fields are NEVER mixed across sources.
     * Secondary/lower-priority quotes may ONLY enrich `dailyHistory`, `applicationCounts`, and metadata.
     */
    internal fun mergeSubscriptionQuotes(quotes: List<LiveSubscriptionQuote>): List<LiveSubscriptionQuote> {
        if (quotes.isEmpty()) return emptyList()
        val validQuotes = quotes.filter { isValidQuoteNumbers(it) }
        if (validQuotes.isEmpty()) return emptyList()

        val sorted = validQuotes.sortedWith(
            compareByDescending<LiveSubscriptionQuote> { it.fetchedAtMs }
                .thenBy { it.priorityRank }
                .thenByDescending { it.hasCategoryBreakdown }
                .thenByDescending { it.dailyHistory.size }
                .thenByDescending { it.applicationCounts.size },
        )
        val merged = mutableListOf<LiveSubscriptionQuote>()
        for (candidate in sorted) {
            val existingIndex = merged.indexOfFirst { existing ->
                areSameSubscriptionCompany(existing.companyName, candidate.companyName, existing.boardType, candidate.boardType)
            }
            if (existingIndex < 0) {
                val normalizedNiiTotal = when {
                    candidate.niiTotalSubX > 0.0 -> candidate.niiTotalSubX
                    candidate.bNiiSubX > 0.0 || candidate.sNiiSubX > 0.0 ->
                        roundTwoDecimals((candidate.bNiiSubX * 2.0 + candidate.sNiiSubX) / 3.0)
                    else -> 0.0
                }
                merged.add(
                    candidate.copy(
                        qibSubX = roundTwoDecimals(candidate.qibSubX),
                        bNiiSubX = roundTwoDecimals(candidate.bNiiSubX),
                        sNiiSubX = roundTwoDecimals(candidate.sNiiSubX),
                        niiTotalSubX = roundTwoDecimals(normalizedNiiTotal),
                        retailSubX = roundTwoDecimals(candidate.retailSubX),
                        employeeSubX = roundTwoDecimals(candidate.employeeSubX),
                        totalSubX = roundTwoDecimals(candidate.totalSubX),
                    ),
                )
            } else {
                val base = merged[existingIndex]
                val mergedDaily = when {
                    base.dailyHistory.size >= candidate.dailyHistory.size && base.dailyHistory.isNotEmpty() ->
                        base.dailyHistory
                    candidate.dailyHistory.isNotEmpty() -> candidate.dailyHistory
                    else -> base.dailyHistory
                }
                val mergedAppCounts = mergeApplicationCounts(base.applicationCounts, candidate.applicationCounts)

                // Keep ALL category/total subscription values strictly from the winning quote (base).
                merged[existingIndex] = base.copy(
                    boardType = base.boardType ?: candidate.boardType,
                    symbol = base.symbol.ifBlank { candidate.symbol },
                    asOfLabel = base.asOfLabel.ifBlank { candidate.asOfLabel },
                    detailUrl = base.detailUrl.ifBlank { candidate.detailUrl },
                    dailyHistory = mergedDaily,
                    applicationCounts = mergedAppCounts,
                )
            }
        }
        return merged
    }

    /**
     * Authoritatively applies a validated winning live quote to the IPO entity.
     * Rejects negative/NaN quotes and rejects regressive lower `totalSubX` within the same bidding day.
     */
    internal fun applySubscriptionToEntity(
        entity: IpoEntity,
        quote: LiveSubscriptionQuote,
        syncTime: String,
        nowMs: Long = System.currentTimeMillis(),
    ): IpoEntity {
        if (!isValidQuoteNumbers(quote)) {
            Log.w(TAG, "[${entity.companyName}] Rejecting invalid/negative/NaN quote from ${quote.sourceName}")
            return entity
        }
        val effectiveUpdatedAtMs = quote.fetchedAtMs.takeIf { it > 0L } ?: nowMs
        val sameBiddingDay = entity.subscriptionUpdatedAtMs > 0L &&
            IpoDateUtils.startOfDayMs(entity.subscriptionUpdatedAtMs) == IpoDateUtils.startOfDayMs(effectiveUpdatedAtMs)
        if (sameBiddingDay && entity.totalSubX > 0.0 && quote.totalSubX < entity.totalSubX &&
            (quote.totalSubX > 0.0 || quote.hasCategoryBreakdown)
        ) {
            Log.w(
                TAG,
                "[${entity.companyName}] Rejecting same-day regression ${quote.totalSubX}x < ${entity.totalSubX}x from ${quote.sourceName}",
            )
            return entity
        }

        val hasBreakdown = quote.hasCategoryBreakdown
        val nextQib = when {
            quote.qibSubX > 0.0 -> quote.qibSubX
            hasBreakdown -> 0.0
            else -> entity.qibSubX
        }
        val nextBNii = when {
            quote.bNiiSubX > 0.0 -> quote.bNiiSubX
            quote.niiTotalSubX > 0.0 -> quote.niiTotalSubX
            hasBreakdown -> 0.0
            else -> entity.bNiiSubX
        }
        val nextSNii = when {
            quote.sNiiSubX > 0.0 -> quote.sNiiSubX
            quote.niiTotalSubX > 0.0 -> quote.niiTotalSubX
            hasBreakdown -> 0.0
            else -> entity.sNiiSubX
        }
        val nextRetail = when {
            quote.retailSubX > 0.0 -> quote.retailSubX
            hasBreakdown -> 0.0
            else -> entity.retailSubX
        }
        val nextEmp = if (quote.employeeSubX > 0.0) quote.employeeSubX else entity.employeeSubX
        val nextTotal = if (quote.totalSubX > 0.0) quote.totalSubX else entity.totalSubX

        val niiForRow = when {
            quote.niiTotalSubX > 0.0 -> quote.niiTotalSubX
            nextBNii > 0.0 || nextSNii > 0.0 -> roundTwoDecimals((nextBNii * 2.0 + nextSNii) / 3.0)
            else -> entity.niiCombinedSubX
        }

        val updatedDaily = resolveDailySubscriptions(
            entity = entity,
            quote = quote,
            nextQib = nextQib,
            nextBNii = nextBNii,
            nextSNii = nextSNii,
            nextNii = niiForRow,
            nextRetail = nextRetail,
            nextTotal = nextTotal,
            nowMs = effectiveUpdatedAtMs,
        )

        val updatedAppCounts = if (quote.applicationCounts.isNotEmpty()) {
            quote.applicationCounts
        } else {
            entity.liveApplicationCounts
        }

        val cleanSources = quote.sourceName.ifBlank { "Live Exchange" }
        val sourceStamp = "$cleanSources • $syncTime"
        return entity.copy(
            qibSubX = roundTwoDecimals(nextQib),
            bNiiSubX = roundTwoDecimals(nextBNii),
            sNiiSubX = roundTwoDecimals(nextSNii),
            retailSubX = roundTwoDecimals(nextRetail),
            employeeSubX = roundTwoDecimals(nextEmp),
            totalSubX = roundTwoDecimals(nextTotal),
            dailySubscriptions = updatedDaily,
            liveApplicationCounts = updatedAppCounts,
            subscriptionSourceLabel = sourceStamp,
            subscriptionUpdatedAtMs = effectiveUpdatedAtMs,
            updatedAgoLabel = sourceStamp,
        )
    }

    private fun resolveDailySubscriptions(
        entity: IpoEntity,
        quote: LiveSubscriptionQuote,
        nextQib: Double,
        nextBNii: Double,
        nextSNii: Double,
        nextNii: Double,
        nextRetail: Double,
        nextTotal: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): List<DaySubscription> {
        if (quote.dailyHistory.isNotEmpty()) {
            val normalized = quote.dailyHistory.mapIndexed { idx, row ->
                val resolvedDate = row.dateLabel.ifBlank { computeTradingDayDateLabel(entity.openDate, idx) }
                val resolvedBNii = if (row.bNiiX > 0.0) row.bNiiX else row.niiX
                val resolvedSNii = if (row.sNiiX > 0.0) row.sNiiX else row.niiX
                row.copy(
                    dayLabel = row.dayLabel.ifBlank { "Day ${idx + 1}" },
                    dateLabel = resolvedDate,
                    bNiiX = roundTwoDecimals(resolvedBNii),
                    sNiiX = roundTwoDecimals(resolvedSNii),
                )
            }
            // If an official exchange quote (NSE) also returned fresher totals for the live session,
            // align the final row so the Day-wise table never contradicts the Live Subscription card.
            if (quote.priorityRank <= PRIORITY_NSE_CURRENT && nextTotal > 0.0 && normalized.isNotEmpty()) {
                val last = normalized.last()
                val alignedLast = last.copy(
                    qibX = if (nextQib > 0.0) roundTwoDecimals(nextQib) else last.qibX,
                    bNiiX = if (nextBNii > 0.0) roundTwoDecimals(nextBNii) else last.bNiiX,
                    sNiiX = if (nextSNii > 0.0) roundTwoDecimals(nextSNii) else last.sNiiX,
                    niiX = if (nextNii > 0.0) roundTwoDecimals(nextNii) else last.niiX,
                    retailX = if (nextRetail > 0.0) roundTwoDecimals(nextRetail) else last.retailX,
                    totalX = roundTwoDecimals(nextTotal),
                )
                return normalized.dropLast(1) + alignedLast
            }
            return normalized
        }

        if (nextTotal <= 0.0 && nextQib <= 0.0 && nextRetail <= 0.0 && nextNii <= 0.0) {
            return entity.dailySubscriptions
        }

        val (currentDayLabel, currentDateLabel) = resolveCurrentBiddingDayLabel(entity, nowMs)
        val liveRow = DaySubscription(
            dayLabel = currentDayLabel,
            qibX = roundTwoDecimals(nextQib),
            niiX = roundTwoDecimals(nextNii),
            retailX = roundTwoDecimals(nextRetail),
            totalX = roundTwoDecimals(nextTotal),
            dateLabel = currentDateLabel,
            bNiiX = roundTwoDecimals(nextBNii),
            sNiiX = roundTwoDecimals(nextSNii),
        )
        val cleanedPrior = entity.dailySubscriptions.filterNot {
            it.dayLabel.equals(currentDayLabel, ignoreCase = true) ||
                it.dayLabel.equals("Live", ignoreCase = true) ||
                it.dayLabel.equals("Final", ignoreCase = true)
        }
        return cleanedPrior + liveRow
    }

    private fun resolveCurrentBiddingDayLabel(
        entity: IpoEntity,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<String, String> {
        val openMs = IpoDateUtils.parseFlexibleDateMs(entity.openDate, nowMs)
        val closeMs = IpoDateUtils.parseFlexibleDateMs(entity.closeDate, nowMs)
        val todayMs = IpoDateUtils.startOfDayMs(nowMs)
        val dateFmt = SimpleDateFormat("dd MMM", Locale.US).apply {
            timeZone = IST_TIME_ZONE
        }

        if (entity.status == IpoStatus.CLOSED || (closeMs != null && closeMs < todayMs)) {
            val totalDays = if (openMs != null && closeMs != null && closeMs >= openMs) {
                countWeekdaysInclusive(openMs, closeMs).coerceIn(1, 5)
            } else {
                entity.dailySubscriptions.size.coerceIn(1, 3)
            }
            val dateText = closeMs?.let { dateFmt.format(Date(it)) } ?: entity.closeDate.removeSuffix(" 2026").trim()
            return "Day $totalDays" to dateText
        }

        if (openMs != null && todayMs >= openMs) {
            val cappedToday = if (closeMs != null && todayMs > closeMs) closeMs else todayMs
            val dayNum = countWeekdaysInclusive(openMs, cappedToday).coerceIn(1, 5)
            return "Day $dayNum" to dateFmt.format(Date(cappedToday))
        }

        val fallbackLabel = entity.dailySubscriptions.lastOrNull()?.dayLabel?.ifBlank { "Day 1" } ?: "Day 1"
        val fallbackDate = openMs?.let { dateFmt.format(Date(it)) } ?: dateFmt.format(Date(nowMs))
        return fallbackLabel to fallbackDate
    }

    private fun computeTradingDayDateLabel(openDate: String, zeroBasedDayIndex: Int): String {
        val openMs = IpoDateUtils.parseFlexibleDateMs(openDate) ?: return ""
        val cal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = openMs }
        var added = 0
        while (added < zeroBasedDayIndex) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                added++
            }
        }
        return SimpleDateFormat("dd MMM", Locale.US).apply {
            timeZone = IST_TIME_ZONE
        }.format(cal.time)
    }

    private fun countWeekdaysInclusive(startMs: Long, endMs: Long): Int {
        if (endMs < startMs) return 1
        val cal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = startMs }
        var count = 0
        while (cal.timeInMillis <= endMs && count < 10) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                count++
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return count.coerceAtLeast(1)
    }

    private fun mergeApplicationCounts(
        primary: List<LiveApplicationCount>,
        secondary: List<LiveApplicationCount>,
    ): List<LiveApplicationCount> {
        if (primary.isEmpty()) return secondary
        if (secondary.isEmpty()) return primary
        val merged = LinkedHashMap<String, LiveApplicationCount>()
        primary.forEach { count ->
            if (count.appliedApplications > 0L || count.reservedApplications > 0L) {
                merged[count.categoryId] = count
            }
        }
        secondary.forEach { count ->
            val existing = merged[count.categoryId]
            if (existing == null) {
                if (count.appliedApplications > 0L || count.reservedApplications > 0L) {
                    merged[count.categoryId] = count
                }
            } else {
                merged[count.categoryId] = existing.copy(
                    reservedApplications = existing.reservedApplications.takeIf { it > 0L } ?: count.reservedApplications,
                    appliedApplications = existing.appliedApplications.takeIf { it > 0L } ?: count.appliedApplications,
                    subscriptionX = existing.subscriptionX.takeIf { it > 0.0 } ?: count.subscriptionX,
                )
            }
        }
        return merged.values.toList()
    }

    internal fun findMatchingQuote(
        entity: IpoEntity,
        quotes: List<LiveSubscriptionQuote>,
    ): LiveSubscriptionQuote? {
        val entityKey = normalizeCompanyKey(entity.companyName)
        val shortKey = entity.shortCode.lowercase(Locale.US)
        return quotes.firstOrNull { it.normalizedKey == entityKey }
            ?: quotes.firstOrNull { quote ->
                areSameSubscriptionCompany(entity.companyName, quote.companyName, entity.boardType, quote.boardType) ||
                    (shortKey.length >= 4 && quote.symbol.equals(entity.shortCode, ignoreCase = true))
            }
    }

    internal fun normalizeCompanyKey(raw: String): String {
        val cleaned = cleanCompanyName(raw).lowercase(Locale.US)
        return cleaned
            .replace(Regex("\\b(limited|ltd|india|indian|industries|industry|technologies|technology|tech|enterprises|enterprise|solutions|solution|services|service|systems|system|projects|project|holdings|holding|corporation|corp|private|pvt|ipo|sme|mainboard|nse|bse)\\b"), " ")
            .replace(Regex("[^a-z0-9]"), "")
    }

    internal fun areSameSubscriptionCompany(
        nameA: String,
        nameB: String,
        boardA: BoardType?,
        boardB: BoardType?,
    ): Boolean {
        if (boardA != null && boardB != null && boardA != boardB) return false
        val keyA = normalizeCompanyKey(nameA)
        val keyB = normalizeCompanyKey(nameB)
        if (keyA.isBlank() || keyB.isBlank()) return false
        if (keyA == keyB) return true
        val shorter = minOf(keyA.length, keyB.length)
        return shorter >= 6 && (keyA.contains(keyB) || keyB.contains(keyA))
    }

    private fun buildNormalizedQuote(
        companyName: String,
        boardType: BoardType?,
        qib: Double,
        bNii: Double,
        sNii: Double,
        niiTotal: Double,
        retail: Double,
        employee: Double,
        total: Double,
        sourceName: String,
        priorityRank: Int,
        asOfLabel: String,
        symbol: String = "",
        detailUrl: String = "",
        dailyHistory: List<DaySubscription> = emptyList(),
        applicationCounts: List<LiveApplicationCount> = emptyList(),
    ): LiveSubscriptionQuote {
        val resolvedNiiTotal = when {
            niiTotal > 0.0 -> niiTotal
            bNii > 0.0 || sNii > 0.0 -> roundTwoDecimals((bNii * 2.0 + sNii) / 3.0)
            else -> 0.0
        }
        return LiveSubscriptionQuote(
            companyName = companyName,
            normalizedKey = normalizeCompanyKey(companyName),
            boardType = boardType,
            qibSubX = roundTwoDecimals(qib),
            bNiiSubX = roundTwoDecimals(bNii),
            sNiiSubX = roundTwoDecimals(sNii),
            niiTotalSubX = roundTwoDecimals(resolvedNiiTotal),
            retailSubX = roundTwoDecimals(retail),
            employeeSubX = roundTwoDecimals(employee),
            totalSubX = roundTwoDecimals(total),
            sourceName = sourceName,
            priorityRank = priorityRank,
            asOfLabel = asOfLabel,
            symbol = symbol,
            detailUrl = detailUrl,
            dailyHistory = dailyHistory,
            applicationCounts = applicationCounts,
        )
    }

    private fun isApplicationCountHeader(headerLower: String): Boolean {
        return headerLower.contains("application") ||
            headerLower.contains("no. of app") ||
            headerLower.contains("no of app") ||
            headerLower.contains("total app") ||
            headerLower == "apps" ||
            headerLower.endsWith(" apps")
    }

    private fun isTotalMultiplierHeader(headerLower: String): Boolean {
        if (isApplicationCountHeader(headerLower)) return false
        if (headerLower.contains("share") || headerLower.contains("size") || headerLower.contains("amount") || headerLower.contains("crore")) {
            return false
        }
        return headerLower == "total" ||
            headerLower.startsWith("total ") ||
            headerLower.startsWith("total(") ||
            headerLower.contains("total (x)") ||
            headerLower.contains("total sub") ||
            headerLower == "subscription" ||
            headerLower == "sub" ||
            headerLower == "sub (x)"
    }

    private fun isBigNiiHeader(headerLower: String): Boolean {
        return headerLower.contains("bnii") ||
            headerLower.contains("b-nii") ||
            headerLower.contains("bhni") ||
            headerLower.contains("b-hni") ||
            headerLower.contains(">10") ||
            headerLower.contains("> 10") ||
            headerLower.contains("above 10") ||
            headerLower.contains("big nii") ||
            headerLower.contains("big hni")
    }

    private fun isSmallNiiHeader(headerLower: String): Boolean {
        return headerLower.contains("snii") ||
            headerLower.contains("s-nii") ||
            headerLower.contains("shni") ||
            headerLower.contains("s-hni") ||
            headerLower.contains("<10") ||
            headerLower.contains("< 10") ||
            headerLower.contains("below 10") ||
            headerLower.contains("small nii") ||
            headerLower.contains("small hni")
    }

    private fun isBigNiiCategory(categoryLower: String): Boolean {
        return isBigNiiHeader(categoryLower) ||
            (categoryLower.contains("above") && (categoryLower.contains("10") || categoryLower.contains("nii") || categoryLower.contains("hni")))
    }

    private fun isSmallNiiCategory(categoryLower: String): Boolean {
        return isSmallNiiHeader(categoryLower) ||
            ((categoryLower.contains("below") || categoryLower.contains("upto") || categoryLower.contains("2 lakh") || categoryLower.contains("2l")) &&
                (categoryLower.contains("10") || categoryLower.contains("nii") || categoryLower.contains("hni")))
    }

    private fun extractDayAndDateLabel(
        rawDayCell: String,
        explicitDateCell: String,
        fallbackDayNumber: Int,
    ): Pair<String, String> {
        val dayMatch = Regex("Day\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawDayCell)
        val dayLabel = if (dayMatch != null) {
            "Day ${dayMatch.groupValues[1]}"
        } else {
            "Day $fallbackDayNumber"
        }

        val dateRegex = Regex("(\\d{1,2}[-\\s/][A-Za-z]{3,9}(?:[,\\s-]+\\d{2,4})?|[A-Za-z]{3,9}\\s+\\d{1,2}(?:,\\s*\\d{2,4})?)")
        val rawDateCandidate = explicitDateCell.ifBlank {
            dateRegex.find(rawDayCell)?.value.orEmpty()
        }
        val cleanDate = rawDateCandidate
            .replace(Regex("\\s+"), " ")
            .trim(' ', '(', ')', '-', ':', ',')
        return dayLabel to cleanDate
    }

    private fun extractHrefFromHtml(rawCellHtml: String, baseUrl: String): String {
        val match = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(rawCellHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return ""
        if (match.isBlank() || match.startsWith("#") || match.startsWith("javascript:", ignoreCase = true)) {
            return ""
        }
        if (match.startsWith("http://", ignoreCase = true) || match.startsWith("https://", ignoreCase = true)) {
            return match
        }
        if (baseUrl.isBlank()) return ""
        return runCatching {
            URI(baseUrl).resolve(match).toString()
        }.getOrDefault("")
    }

    private fun parseSubscriptionMultiplier(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0
        val clean = stripHtml(raw)
            .replace(",", "")
            .replace("times", "", ignoreCase = true)
            .replace("x", "", ignoreCase = true)
            .trim()
        if (clean.isBlank() || clean == "-" || clean == "--" || clean.equals("NA", ignoreCase = true) || clean.equals("N/A", ignoreCase = true)) {
            return 0.0
        }
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(clean)?.groupValues?.getOrNull(1) ?: return 0.0
        val value = match.toDoubleOrNull() ?: return 0.0
        return if (value in 0.0..50_000.0) value else 0.0
    }

    private fun parseApplicationCount(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = stripHtml(raw)
            .replace(",", "")
            .trim()
        if (clean.isBlank() || clean == "-" || clean == "--" || clean.equals("NA", ignoreCase = true) || clean.equals("N/A", ignoreCase = true)) {
            return 0L
        }
        val lower = clean.lowercase(Locale.US)
        val numMatch = Regex("(\\d+(?:\\.\\d+)?)").find(lower)?.groupValues?.getOrNull(1) ?: return 0L
        val num = numMatch.toDoubleOrNull() ?: return 0L
        val scaled = when {
            lower.contains("cr") -> num * 10_000_000.0
            lower.contains("lakh") || lower.endsWith("l") || lower.contains(" lac") -> num * 100_000.0
            lower.endsWith("k") -> num * 1_000.0
            else -> num
        }
        val asLong = scaled.toLong()
        return if (asLong in 1L..500_000_000L) asLong else 0L
    }

    private fun cleanCompanyName(raw: String): String {
        return stripHtml(raw)
            .replace(Regex("\\b(NSE\\s*SME|BSE\\s*SME|SME\\s*IPO|Mainboard\\s*IPO|IPO\\s*Subscription|IPO)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', ':', '|', ',')
    }

    private fun stripHtml(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]*>"), " ")
        return runCatching {
            Html.fromHtml(noTags, Html.FROM_HTML_MODE_COMPACT).toString()
        }.getOrElse {
            noTags
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&#8377;", "₹")
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun firstNonBlank(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() && it.trim() != "null" }?.trim().orEmpty()

    private data class FetchTextResult(
        val body: String? = null,
        val httpCode: Int? = null,
        val errorReason: String? = null,
    )

    private data class SourceTaskOutcome(
        val status: SubscriptionSourceStatus,
        val quotes: List<LiveSubscriptionQuote>,
    )

    private fun fetchText(sourceName: String, url: String, referer: String? = null): FetchTextResult {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json, text/html, */*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
            if (!referer.isNullOrBlank()) {
                builder.header("Referer", referer)
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    FetchTextResult(body = resp.body?.string(), httpCode = resp.code)
                } else {
                    Log.w(TAG, "[$sourceName] HTTP ${resp.code} fetching $url")
                    FetchTextResult(httpCode = resp.code, errorReason = "HTTP ${resp.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[$sourceName] Request failed for $url: ${e.javaClass.simpleName}: ${e.message}", e)
            FetchTextResult(errorReason = e.javaClass.simpleName)
        }
    }

    private fun roundTwoDecimals(value: Double): Double =
        (value * 100.0).roundToInt() / 100.0

    private class InMemorySessionCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        fun hasCookiesFor(host: String): Boolean {
            val now = System.currentTimeMillis()
            return store[host]?.any { it.expiresAt > now } == true
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host
            val now = System.currentTimeMillis()
            val list = store.computeIfAbsent(host) { mutableListOf() }
            synchronized(list) {
                list.removeAll { it.expiresAt <= now }
                for (cookie in cookies) {
                    list.removeAll { it.name == cookie.name && it.path == cookie.path }
                    if (cookie.expiresAt > now) {
                        list.add(cookie)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val matching = mutableListOf<Cookie>()
            store.values.forEach { list ->
                synchronized(list) {
                    list.removeAll { it.expiresAt <= now }
                    list.forEach { cookie ->
                        if (cookie.matches(url)) {
                            matching.add(cookie)
                        }
                    }
                }
            }
            return matching
        }
    }

    private enum class SubSourceKind {
        NSE_CURRENT_JSON,
        NSE_BID_DETAIL_JSON,
        INVESTORGAIN_JSON,
        HTML_TABLE,
    }

    private data class SubSourceTask(
        val name: String,
        val priorityRank: Int,
        val url: String,
        val kind: SubSourceKind,
        val referer: String? = null,
        val defaultBoardType: BoardType? = null,
        val baseUrl: String? = null,
        val targetCompanyName: String? = null,
        val targetSymbol: String? = null,
    )

    companion object {
        private const val TAG = "LiveSubscriptionRepo"
        private val IST_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

        const val DEFAULT_STALE_MS: Long = 2L * 60L * 1_000L // 2 minutes
        const val MARKET_POLL_INTERVAL_MS: Long = 75_000L // 75 seconds during IST bidding hours
        const val CLOSE_DAY_EVENING_POLL_INTERVAL_MS: Long = 10L * 60L * 1_000L // 10 minutes until 9 PM IST on close day
        const val BOARD_POLL_INTERVAL_MS: Long = 3L * 60L * 1_000L // 3 minutes while board visible on weekdays
        private const val NSE_WARMUP_INTERVAL_MS: Long = 10L * 60L * 1_000L

        const val PRIORITY_NSE_LIVE_BID = -1
        const val PRIORITY_NSE_CURRENT = 0
        const val PRIORITY_CHITTORGARH_DETAIL = 1
        const val PRIORITY_CHITTORGARH_SUMMARY = 2
        const val PRIORITY_INVESTORGAIN_DETAIL = 3
        const val PRIORITY_INVESTORGAIN_SUMMARY = 4
        const val PRIORITY_INVESTORGAIN_PREV = 5
        const val PRIORITY_IPOWATCH = 6

        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        fun formatTimeLabel(nowMs: Long = System.currentTimeMillis()): String {
            return SimpleDateFormat("dd MMM, hh:mm a", Locale.US).apply {
                timeZone = IST_TIME_ZONE
            }.format(Date(nowMs))
        }

        fun formatSourceStatuses(statuses: List<SubscriptionSourceStatus>): String? {
            if (statuses.isEmpty()) return null
            val grouped = LinkedHashMap<String, Boolean>()
            for (status in statuses) {
                val shortName = when {
                    status.sourceName.startsWith("NSE", ignoreCase = true) -> "NSE"
                    status.sourceName.startsWith("Chittorgarh", ignoreCase = true) -> "Chittorgarh"
                    status.sourceName.startsWith("InvestorGain", ignoreCase = true) -> "InvestorGain"
                    status.sourceName.startsWith("IPOWatch", ignoreCase = true) -> "IPOWatch"
                    else -> status.sourceName
                }
                val previous = grouped[shortName] ?: false
                grouped[shortName] = previous || status.ok
            }
            if (grouped.isEmpty()) return null
            val summary = grouped.entries.joinToString(", ") { (name, ok) ->
                if (ok) "$name ✓" else "$name ✗"
            }
            return "Sources: $summary"
        }

        fun isWeekdayIst(nowMs: Long = System.currentTimeMillis()): Boolean {
            val cal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = nowMs }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY
        }

        /**
         * Returns true during Indian IPO bidding hours: Monday–Friday, 10:00 AM to 5:15 PM IST.
         */
        fun isMarketHoursIst(nowMs: Long = System.currentTimeMillis()): Boolean {
            val cal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = nowMs }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
            val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return minutesOfDay in (10 * 60)..(17 * 60 + 15)
        }

        /**
         * Post-close finalization window on close day: Monday–Friday, 5:16 PM to 9:00 PM IST.
         */
        fun isPostCloseEveningWindowIst(nowMs: Long = System.currentTimeMillis()): Boolean {
            val cal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = nowMs }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
            val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return minutesOfDay in (17 * 60 + 16)..(21 * 60)
        }

        /**
         * Next-morning catch-up window (~9:30 AM IST): Monday–Friday, 9:15 AM to 9:59 AM IST.
         */
        fun isNextMorningCatchupWindowIst(nowMs: Long = System.currentTimeMillis()): Boolean {
            val cal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = nowMs }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
            val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return minutesOfDay in (9 * 60 + 15)..(9 * 60 + 59)
        }

        fun isClosingTodayIst(
            closeDate: String,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            val closeMs = IpoDateUtils.parseFlexibleDateMs(closeDate, nowMs) ?: return false
            return IpoDateUtils.startOfDayMs(closeMs) == IpoDateUtils.startOfDayMs(nowMs)
        }

        fun closedOnPreviousTradingDayIst(
            closeDate: String,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            val closeMs = IpoDateUtils.parseFlexibleDateMs(closeDate, nowMs) ?: return false
            val closeDayMs = IpoDateUtils.startOfDayMs(closeMs)
            val prevTradingCal = Calendar.getInstance(IST_TIME_ZONE).apply {
                timeInMillis = IpoDateUtils.startOfDayMs(nowMs)
                do {
                    add(Calendar.DAY_OF_MONTH, -1)
                } while (get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
            return closeDayMs == prevTradingCal.timeInMillis
        }

        /**
         * Determines the active polling interval for an IPO detail screen based on calendar dates in IST:
         * - Weekends: null (never poll).
         * - Bidding open today + market hours (10:00 AM–5:15 PM IST): 75 seconds.
         * - Close date is today + evening window (5:16 PM–9:00 PM IST): 10 minutes.
         * - Otherwise: null.
         */
        fun resolveDetailPollIntervalMs(
            openDate: String,
            closeDate: String,
            listingDate: String,
            status: IpoStatus,
            nowMs: Long = System.currentTimeMillis(),
        ): Long? {
            if (!isWeekdayIst(nowMs)) return null
            if (IpoDateUtils.isBiddingOpenToday(openDate, closeDate, listingDate, status, nowMs) &&
                isMarketHoursIst(nowMs)
            ) {
                return MARKET_POLL_INTERVAL_MS
            }
            if (isClosingTodayIst(closeDate, nowMs) && isPostCloseEveningWindowIst(nowMs)) {
                return CLOSE_DAY_EVENING_POLL_INTERVAL_MS
            }
            return null
        }

        /**
         * Returns true when an IPO closed on the previous trading day and needs a single next-morning
         * (~9:30 AM IST) refresh to capture finalized closing subscription figures.
         */
        fun shouldRunNextMorningCatchup(
            closeDate: String,
            subscriptionUpdatedAtMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            if (!isNextMorningCatchupWindowIst(nowMs)) return false
            if (!closedOnPreviousTradingDayIst(closeDate, nowMs)) return false
            val morningWindowStartMs = Calendar.getInstance(IST_TIME_ZONE).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 15)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return subscriptionUpdatedAtMs < morningWindowStartMs
        }

        /**
         * Board screen polls every ~3 minutes ONLY while visible on weekdays during active bidding /
         * close-day evening hours when at least one IPO is open today (by calendar dates).
         */
        fun shouldPollBoard(
            ipos: List<IpoEntity>,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            if (!isWeekdayIst(nowMs)) return false
            if (!isMarketHoursIst(nowMs) && !isPostCloseEveningWindowIst(nowMs)) return false
            return ipos.any { ipo ->
                IpoDateUtils.isBiddingOpenToday(
                    openDate = ipo.openDate,
                    closeDate = ipo.closeDate,
                    listingDate = ipo.listingDate,
                    status = ipo.status,
                    referenceTimeMs = nowMs,
                )
            }
        }
    }
}
