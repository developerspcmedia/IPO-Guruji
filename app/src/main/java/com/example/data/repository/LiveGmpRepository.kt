package com.example.data.repository

import android.text.Html
import android.util.Xml
import com.example.data.model.BoardType
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

data class LiveGmpQuote(
    val companyName: String,
    val normalizedKey: String,
    val gmpRupees: Int,
    val priceLow: Int = 0,
    val priceHigh: Int = 0,
    val lotSize: Int = 0,
    val issueSizeCr: Double = 0.0,
    val openDate: String = "",
    val closeDate: String = "",
    val listingDate: String = "",
    val boardType: BoardType = BoardType.MAINBOARD,
    val status: IpoStatus? = null,
    val totalSubX: Double = 0.0,
    val sourceName: String,
    val priorityRank: Int,
    val hasExplicitGmp: Boolean = true,
    val verifiedProviders: Set<String> = IpoFilingVerifier.extractVerifiedProvidersFromLabel(sourceName),
    val isUnconfirmedOrRumored: Boolean = false,
    val datePriorityRank: Int = LiveGmpRepository.datePriorityForSource(sourceName),
    val listingDatePriorityRank: Int = datePriorityRank,
) {
    val distinctProviderCount: Int
        get() = verifiedProviders.size
}

data class LiveGmpSnapshot(
    val quotesByKey: Map<String, LiveGmpQuote>,
    val allQuotes: List<LiveGmpQuote>,
    val activeSources: List<String>,
    val syncedAtTime: String,
    val verifiedProviders: List<String> = activeSources
        .mapNotNull { IpoFilingVerifier.providerFamily(it).takeIf { f -> f.isNotBlank() } }
        .distinct(),
)

class LiveGmpRepository(
    internal val enableNetworkFetch: Boolean = true,
) {
    private val cookieJar = InMemorySessionCookieJar()
    private val lastNseWarmupMs = AtomicLong(0L)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun executeCancellable(request: Request): Pair<Boolean, String> =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            val ok = res.isSuccessful
                            val body = if (ok) res.body?.string().orEmpty() else ""
                            if (continuation.isActive) {
                                continuation.resume(ok to body)
                            }
                        }
                    }
                },
            )
        }

    private suspend fun ensureNseSessionWarm(nowMs: Long): Boolean {
        val previous = lastNseWarmupMs.get()
        if (nowMs - previous < NSE_WARMUP_INTERVAL_MS) return true

        val warmupUrls = listOf(
            "https://www.nseindia.com",
            "https://www.nseindia.com/market-data/all-upcoming-issues-ipo",
        )
        var anySucceeded = false
        for (url in warmupUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-IN,en;q=0.9")
                    .header("Cache-Control", "no-cache")
                    .build()
                val (ok, _) = executeCancellable(request)
                if (ok) anySucceeded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Continue trying next warmup URL; overall status determined by anySucceeded.
            }
        }
        if (anySucceeded) {
            lastNseWarmupMs.set(nowMs)
        }
        return anySucceeded
    }

    suspend fun fetchLatestGmps(): LiveGmpSnapshot = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val istZone = TimeZone.getTimeZone("Asia/Kolkata")
        val syncTime = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).apply {
            timeZone = istZone
        }.format(Date(nowMs))
        if (!enableNetworkFetch) {
            return@withContext LiveGmpSnapshot(
                quotesByKey = emptyMap(),
                allQuotes = emptyList(),
                activeSources = emptyList(),
                syncedAtTime = syncTime,
            )
        }

        val now = Calendar.getInstance(istZone).apply { timeInMillis = nowMs }
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

        val investorGainJsonUrl =
            "https://webnodejs.investorgain.com/cloud/report/data-read/331/1/$monthFormatted/$year/$fyParam/0/all?search=&v=1&_ts=$nowMs"
        val investorGainPrevMonthUrl =
            "https://webnodejs.investorgain.com/cloud/report/data-read/331/1/$prevMonthFormatted/$prevYear/$prevFyParam/0/all?search=&v=1&_ts=$nowMs"
        val investorGainListedJsonUrl =
            "https://webnodejs.investorgain.com/cloud/report/data-read/377/1/$monthFormatted/$year/$fyParam/0/all?search=&v=1&_ts=$nowMs"

        val sourceTasks = listOf(
            SourceTask(
                name = "NSE India",
                priorityRank = 0,
                url = "https://www.nseindia.com/api/ipo-current-issue",
                kind = SourceKind.NSE_JSON,
                referer = "https://www.nseindia.com/market-data/all-upcoming-issues-ipo",
            ),
            SourceTask(
                name = "NSE India Upcoming",
                priorityRank = 0,
                url = "https://www.nseindia.com/api/ipo-upcoming",
                kind = SourceKind.NSE_JSON,
                referer = "https://www.nseindia.com/market-data/all-upcoming-issues-ipo",
                defaultStatus = IpoStatus.UPCOMING,
            ),
            SourceTask(
                name = "InvestorGain",
                priorityRank = 1,
                url = investorGainJsonUrl,
                kind = SourceKind.INVESTORGAIN_JSON,
                referer = "https://www.investorgain.com/",
            ),
            SourceTask(
                name = "IPOWatch",
                priorityRank = 2,
                url = "https://ipowatch.in/ipo-grey-market-premium-latest-ipo-gmp/",
                kind = SourceKind.HTML_TABLE,
            ),
            SourceTask(
                name = "IPO Central",
                priorityRank = 3,
                url = "https://ipocentral.in/ipo-gmp-today-grey-market-premium/",
                kind = SourceKind.HTML_TABLE,
            ),
            SourceTask(
                name = "Chittorgarh",
                priorityRank = 4,
                url = "https://www.chittorgarh.com/report/ipo-in-india-list-main-board-sme/82/",
                kind = SourceKind.HTML_TABLE,
            ),
            SourceTask(
                name = "Chittorgarh Upcoming",
                priorityRank = 4,
                url = "https://www.chittorgarh.com/report/ipo-in-india-list-main-board-sme/82/?a=upcoming",
                kind = SourceKind.HTML_TABLE,
                defaultStatus = IpoStatus.UPCOMING,
            ),
            SourceTask(
                name = "IPOWatch Upcoming",
                priorityRank = 5,
                url = "https://ipowatch.in/upcoming-ipo-list-2026-mainboard-sme-ipo/",
                kind = SourceKind.HTML_TABLE,
                defaultStatus = IpoStatus.UPCOMING,
            ),
            SourceTask(
                name = "IPOWatch Upcoming List",
                priorityRank = 5,
                url = "https://ipowatch.in/upcoming-ipo-list/",
                kind = SourceKind.HTML_TABLE,
                defaultStatus = IpoStatus.UPCOMING,
            ),
            SourceTask(
                name = "IPO Central Upcoming",
                priorityRank = 6,
                url = "https://ipocentral.in/upcoming-ipo-list/",
                kind = SourceKind.HTML_TABLE,
                defaultStatus = IpoStatus.UPCOMING,
            ),
            SourceTask(
                name = "IPOJi",
                priorityRank = 6,
                url = "https://www.ipoji.com/ipo",
                kind = SourceKind.HTML_TABLE,
            ),
            SourceTask(
                name = "InvestorGain Web",
                priorityRank = 7,
                url = "https://www.investorgain.com/report/live-ipo-gmp/331/",
                kind = SourceKind.HTML_TABLE,
            ),
            SourceTask(
                name = "InvestorGain Listed",
                priorityRank = 8,
                url = investorGainListedJsonUrl,
                kind = SourceKind.INVESTORGAIN_JSON,
                referer = "https://www.investorgain.com/",
                defaultStatus = IpoStatus.CLOSED,
            ),
            SourceTask(
                name = "InvestorGain Archive",
                priorityRank = 9,
                url = investorGainPrevMonthUrl,
                kind = SourceKind.INVESTORGAIN_JSON,
                referer = "https://www.investorgain.com/",
            ),
            SourceTask(
                name = "Chittorgarh Listed",
                priorityRank = 10,
                url = "https://www.chittorgarh.com/report/ipo-listing-performance/84/",
                kind = SourceKind.HTML_TABLE,
                defaultStatus = IpoStatus.CLOSED,
            ),
            SourceTask(
                name = "Chittorgarh SME Listed",
                priorityRank = 11,
                url = "https://www.chittorgarh.com/report/sme-ipo-listing-performance/85/",
                kind = SourceKind.HTML_TABLE,
                defaultStatus = IpoStatus.CLOSED,
                defaultBoardType = BoardType.SME,
            ),
            SourceTask(
                name = "IPOWatch Listed",
                priorityRank = 12,
                url = "https://ipowatch.in/ipo-performance-tracker/",
                kind = SourceKind.HTML_TABLE,
                defaultStatus = IpoStatus.CLOSED,
            ),
            SourceTask(
                name = "Google News GMP",
                priorityRank = 15,
                url = "https://news.google.com/rss/search?q=%22IPO+GMP%22+India&hl=en-IN&gl=IN&ceid=IN:en",
                kind = SourceKind.RSS_FEED,
            ),
        )

        fetchSourcesInParallel(
            sourceTasks = sourceTasks,
            nowMs = nowMs,
            syncTime = syncTime,
        )
    }

    internal suspend fun fetchSourcesInParallel(
        sourceTasks: List<SourceTask>,
        nowMs: Long,
        syncTime: String,
        perSourceTimeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        nseWarmupTimeoutMs: Long = NSE_WARMUP_TIMEOUT_MS,
        nseWarmupRunner: suspend (Long) -> Boolean = ::ensureNseSessionWarm,
        sourceFetcher: suspend (SourceTask) -> List<LiveGmpQuote> = ::fetchFromSource,
    ): LiveGmpSnapshot {
        val perSourceResults = coroutineScope {
            val nseWarmupDeferred = async {
                withTimeoutOrNull(nseWarmupTimeoutMs) {
                    try {
                        nseWarmupRunner(nowMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                } ?: false
            }

            sourceTasks.map { task ->
                async {
                    val quotes = withTimeoutOrNull(perSourceTimeoutMs) {
                        try {
                            if (task.kind == SourceKind.NSE_JSON) {
                                val nseReady = nseWarmupDeferred.await()
                                if (!nseReady) return@withTimeoutOrNull emptyList()
                            }
                            sourceFetcher(task)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }.orEmpty()
                    task.name to quotes
                }
            }.awaitAll()
        }

        val activeSources = mutableListOf<String>()
        val collectedQuotes = mutableListOf<LiveGmpQuote>()

        perSourceResults
            .sortedBy { (name, _) -> sourceTasks.firstOrNull { it.name == name }?.priorityRank ?: 99 }
            .forEach { (sourceName, quotes) ->
                if (quotes.isNotEmpty()) {
                    activeSources.add(sourceName)
                    collectedQuotes.addAll(quotes)
                }
            }

        val mergedList = mergeQuotesWithoutDuplicates(collectedQuotes)
        val mergedByKey = LinkedHashMap<String, LiveGmpQuote>()
        mergedList.forEach { quote ->
            mergedByKey[quote.normalizedKey] = quote
        }

        return LiveGmpSnapshot(
            quotesByKey = mergedByKey,
            allQuotes = mergedList,
            activeSources = activeSources.distinct(),
            syncedAtTime = syncTime,
        )
    }

    private suspend fun fetchFromSource(task: SourceTask): List<LiveGmpQuote> {
        val builder = Request.Builder()
            .url(task.url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")

        if (task.kind == SourceKind.NSE_JSON) {
            builder.header("Accept", "application/json, text/plain, */*")
            builder.header("X-Requested-With", "XMLHttpRequest")
        }

        if (task.referer != null) {
            builder.header("Referer", task.referer)
            builder.header("Origin", task.referer.trimEnd('/'))
        }

        val (ok, body) = executeCancellable(builder.build())
        if (!ok || body.isBlank()) return emptyList()

        return when (task.kind) {
            SourceKind.NSE_JSON -> parseNseIpoJson(
                rawJson = body,
                sourceName = task.name,
                priorityRank = task.priorityRank,
                defaultStatus = task.defaultStatus,
            )
            SourceKind.INVESTORGAIN_JSON -> parseInvestorGainJson(
                rawJson = body,
                sourceName = task.name,
                priorityRank = task.priorityRank,
                defaultStatus = task.defaultStatus,
                defaultBoardType = task.defaultBoardType,
            )
            SourceKind.HTML_TABLE -> parseHtmlGmpTables(
                html = body,
                sourceName = task.name,
                priorityRank = task.priorityRank,
                defaultStatus = task.defaultStatus,
                defaultBoardType = task.defaultBoardType,
            )
            SourceKind.RSS_FEED -> parseRssGmpUpdates(body, task.name, task.priorityRank)
        }
    }

    internal fun mergeQuotesWithoutDuplicates(quotes: List<LiveGmpQuote>): List<LiveGmpQuote> {
        val ordered = quotes.sortedBy { it.priorityRank }
        val deduplicated = mutableListOf<LiveGmpQuote>()
        for (candidate in ordered) {
            if (candidate.normalizedKey.length < 3) continue
            val matchIndex = deduplicated.indexOfFirst { existing -> areSameCompany(existing, candidate) }
            if (matchIndex >= 0) {
                deduplicated[matchIndex] = mergeQuotePair(deduplicated[matchIndex], candidate)
            } else {
                deduplicated.add(candidate)
            }
        }
        return deduplicated
    }

    internal fun areSameCompany(a: LiveGmpQuote, b: LiveGmpQuote): Boolean {
        if (a.priceHigh > 0 && b.priceHigh > 0) {
            val diff = abs(a.priceHigh - b.priceHigh)
            val maxPrice = maxOf(a.priceHigh, b.priceHigh)
            if (diff > 15 && diff.toDouble() / maxPrice.toDouble() > 0.25) {
                return false
            }
        }

        if (a.normalizedKey == b.normalizedKey) {
            return a.boardType == b.boardType || a.priceHigh == 0 || b.priceHigh == 0
        }
        val shorter = if (a.normalizedKey.length <= b.normalizedKey.length) a.normalizedKey else b.normalizedKey
        val longer = if (a.normalizedKey.length > b.normalizedKey.length) a.normalizedKey else b.normalizedKey
        if (shorter.length >= 7 && longer.startsWith(shorter)) {
            return a.boardType == b.boardType || a.priceHigh == 0 || b.priceHigh == 0
        }

        val tokensA = tokenizeCompanyName(a.companyName)
        val tokensB = tokenizeCompanyName(b.companyName)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false

        val firstA = tokensA.first()
        val firstB = tokensB.first()
        val sharedTokens = tokensA.intersect(tokensB.toSet())

        if (firstA == firstB && firstA.length >= 5) {
            if (sharedTokens.size >= 2) return true
            val similarPrice = a.priceHigh > 0 && b.priceHigh > 0 && abs(a.priceHigh - b.priceHigh) <= 5
            val sameOpenDate = a.openDate.isNotBlank() && a.openDate.equals(b.openDate, ignoreCase = true)
            if ((similarPrice || sameOpenDate) && (a.boardType == b.boardType || a.priceHigh == 0 || b.priceHigh == 0)) {
                return true
            }
        }

        if (sharedTokens.size >= 2 && (sharedTokens.size == tokensA.size || sharedTokens.size == tokensB.size)) {
            return a.boardType == b.boardType || a.priceHigh == 0 || b.priceHigh == 0
        }
        return false
    }

    internal fun areSameCompanyNames(nameA: String, nameB: String, boardA: BoardType = BoardType.MAINBOARD, boardB: BoardType = BoardType.MAINBOARD): Boolean {
        val qa = LiveGmpQuote(
            companyName = sanitizeCompanyName(nameA),
            normalizedKey = normalizeCompanyKey(sanitizeCompanyName(nameA)),
            gmpRupees = 0,
            boardType = boardA,
            sourceName = "check",
            priorityRank = 1,
        )
        val qb = LiveGmpQuote(
            companyName = sanitizeCompanyName(nameB),
            normalizedKey = normalizeCompanyKey(sanitizeCompanyName(nameB)),
            gmpRupees = 0,
            boardType = boardB,
            sourceName = "check",
            priorityRank = 1,
        )
        return areSameCompany(qa, qb)
    }

    private fun mergeQuotePair(primary: LiveGmpQuote, incoming: LiveGmpQuote): LiveGmpQuote {
        val preferred = if (incoming.priorityRank < primary.priorityRank) incoming else primary
        val fallback = if (preferred === primary) incoming else primary

        val resolvedGmp: Int
        val resolvedHasGmp: Boolean
        when {
            preferred.hasExplicitGmp -> {
                resolvedGmp = preferred.gmpRupees
                resolvedHasGmp = true
            }
            fallback.hasExplicitGmp -> {
                resolvedGmp = fallback.gmpRupees
                resolvedHasGmp = true
            }
            else -> {
                resolvedGmp = preferred.gmpRupees
                resolvedHasGmp = false
            }
        }

        val combinedProviders = LinkedHashSet<String>().apply {
            addAll(primary.verifiedProviders.mapNotNull { IpoFilingVerifier.providerFamily(it).takeIf { f -> f.isNotBlank() } })
            addAll(incoming.verifiedProviders.mapNotNull { IpoFilingVerifier.providerFamily(it).takeIf { f -> f.isNotBlank() } })
        }

        val combinedSource = if (combinedProviders.isNotEmpty()) {
            IpoFilingVerifier.formatVerifiedSourcesLabel(combinedProviders)
        } else if (preferred.sourceName != fallback.sourceName && !preferred.sourceName.contains(fallback.sourceName)) {
            "${preferred.sourceName} • ${fallback.sourceName}"
        } else {
            preferred.sourceName
        }

        val bestLow = preferred.priceLow.takeIf { it > 0 } ?: fallback.priceLow
        val bestHigh = preferred.priceHigh.takeIf { it > 0 } ?: fallback.priceHigh
        val bestLot = preferred.lotSize.takeIf { it > 0 } ?: fallback.lotSize
        val bestIssueSize = preferred.issueSizeCr.takeIf { it > 0.0 } ?: fallback.issueSizeCr

        // Dedicated official-first date authority (NSE/BSE > Chittorgarh > InvestorGain > IPOWatch > others)
        // independent of GMP priority so official exchange/filing calendars never get overwritten by GMP trackers.
        val datePreferred = if (incoming.datePriorityRank < primary.datePriorityRank) incoming else primary
        val dateFallback = if (datePreferred === primary) incoming else primary

        val (prefOpen, prefClose, prefListing) = validateScheduleTriple(
            datePreferred.openDate,
            datePreferred.closeDate,
            datePreferred.listingDate,
        )
        val (fbOpen, fbClose, fbListing) = validateScheduleTriple(
            dateFallback.openDate,
            dateFallback.closeDate,
            dateFallback.listingDate,
        )

        val candidateOpen: String
        val candidateClose: String
        val resolvedDatePriorityRank: Int
        when {
            prefOpen.isNotBlank() && prefClose.isNotBlank() -> {
                candidateOpen = prefOpen
                candidateClose = prefClose
                resolvedDatePriorityRank = datePreferred.datePriorityRank
            }
            prefOpen.isNotBlank() || prefClose.isNotBlank() -> {
                val filledOpen = prefOpen.ifBlank { fbOpen }
                val filledClose = prefClose.ifBlank { fbClose }
                val filledOpenMs = IpoDateUtils.parseFlexibleDateMs(filledOpen)
                val filledCloseMs = IpoDateUtils.parseFlexibleDateMs(filledClose)
                if (filledOpenMs != null && filledCloseMs != null && IpoDateUtils.isValidBiddingWindow(filledOpenMs, filledCloseMs)) {
                    candidateOpen = filledOpen
                    candidateClose = filledClose
                    resolvedDatePriorityRank = datePreferred.datePriorityRank
                } else if (fbOpen.isNotBlank() && fbClose.isNotBlank()) {
                    candidateOpen = fbOpen
                    candidateClose = fbClose
                    resolvedDatePriorityRank = dateFallback.datePriorityRank
                } else {
                    candidateOpen = prefOpen
                    candidateClose = prefClose
                    resolvedDatePriorityRank = datePreferred.datePriorityRank
                }
            }
            fbOpen.isNotBlank() || fbClose.isNotBlank() -> {
                candidateOpen = fbOpen
                candidateClose = fbClose
                resolvedDatePriorityRank = dateFallback.datePriorityRank
            }
            else -> {
                candidateOpen = ""
                candidateClose = ""
                resolvedDatePriorityRank = minOf(primary.datePriorityRank, incoming.datePriorityRank)
            }
        }

        val listingPreferred = if (incoming.listingDatePriorityRank < primary.listingDatePriorityRank) incoming else primary
        val listingFallback = if (listingPreferred === primary) incoming else primary
        val prefListValid = IpoFilingVerifier.sanitizeConfirmedDate(listingPreferred.listingDate)
        val fbListValid = IpoFilingVerifier.sanitizeConfirmedDate(listingFallback.listingDate)

        val candidateListing: String
        val resolvedListingPriorityRank: Int
        when {
            prefListValid.isNotBlank() && isListingConsistentWithWindow(prefListValid, candidateOpen, candidateClose) -> {
                candidateListing = prefListValid
                resolvedListingPriorityRank = listingPreferred.listingDatePriorityRank
            }
            fbListValid.isNotBlank() && isListingConsistentWithWindow(fbListValid, candidateOpen, candidateClose) -> {
                candidateListing = fbListValid
                resolvedListingPriorityRank = listingFallback.listingDatePriorityRank
            }
            prefListValid.isNotBlank() -> {
                candidateListing = prefListValid
                resolvedListingPriorityRank = listingPreferred.listingDatePriorityRank
            }
            fbListValid.isNotBlank() -> {
                candidateListing = fbListValid
                resolvedListingPriorityRank = listingFallback.listingDatePriorityRank
            }
            else -> {
                candidateListing = ""
                resolvedListingPriorityRank = minOf(primary.listingDatePriorityRank, incoming.listingDatePriorityRank)
            }
        }

        val (bestOpen, bestClose, bestListing) = validateScheduleTriple(
            candidateOpen,
            candidateClose,
            candidateListing,
        )

        val bestSub = preferred.totalSubX.takeIf { it > 0.0 } ?: fallback.totalSubX
        val effectiveHigh = maxOf(bestLow, bestHigh)
        val bestStatus = if (bestOpen.isNotBlank() || bestClose.isNotBlank() || bestListing.isNotBlank()) {
            val closedHint = if (
                (primary.status == IpoStatus.CLOSED || incoming.status == IpoStatus.CLOSED) &&
                bestOpen.isBlank() && bestClose.isBlank()
            ) {
                "listed"
            } else {
                ""
            }
            inferStatus(
                statusHint = closedHint,
                openDate = bestOpen,
                closeDate = bestClose,
                priceHigh = effectiveHigh,
                listingDate = bestListing,
            )
        } else {
            val rawStatus = preferred.status ?: fallback.status
            if (rawStatus == IpoStatus.LIVE) IpoStatus.UPCOMING else rawStatus
        }

        val bestBoard = if (preferred.boardType == BoardType.SME || fallback.boardType == BoardType.SME) {
            BoardType.SME
        } else {
            preferred.boardType
        }

        val bestName = if (preferred.companyName.length >= fallback.companyName.length && !preferred.companyName.all { it.isUpperCase() || !it.isLetter() }) {
            preferred.companyName
        } else if (!fallback.companyName.all { it.isUpperCase() || !it.isLetter() } && fallback.priorityRank <= preferred.priorityRank + 2) {
            fallback.companyName
        } else {
            preferred.companyName
        }

        val hardRejected = primary.isUnconfirmedOrRumored || incoming.isUnconfirmedOrRumored

        return preferred.copy(
            companyName = bestName,
            gmpRupees = resolvedGmp,
            priceLow = bestLow,
            priceHigh = effectiveHigh,
            lotSize = bestLot,
            issueSizeCr = bestIssueSize,
            openDate = bestOpen,
            closeDate = bestClose,
            listingDate = bestListing,
            boardType = bestBoard,
            status = bestStatus,
            totalSubX = bestSub,
            sourceName = combinedSource,
            hasExplicitGmp = resolvedHasGmp,
            verifiedProviders = combinedProviders,
            isUnconfirmedOrRumored = hardRejected,
            datePriorityRank = resolvedDatePriorityRank,
            listingDatePriorityRank = resolvedListingPriorityRank,
        )
    }

    internal fun parseNseIpoJson(
        rawJson: String,
        sourceName: String = "NSE India",
        priorityRank: Int = 0,
        defaultStatus: IpoStatus? = null,
    ): List<LiveGmpQuote> {
        val trimmed = rawJson.trim()
        val dataArray: JSONArray = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            trimmed.startsWith("{") -> {
                val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
                root.optJSONArray("data") ?: root.optJSONArray("ipoList") ?: return emptyList()
            }
            else -> return emptyList()
        }

        val quotes = mutableListOf<LiveGmpQuote>()
        for (i in 0 until dataArray.length()) {
            val row = dataArray.optJSONObject(i) ?: continue
            val rawName = row.optString("companyName").ifBlank { row.optString("symbol") }
            val companyName = sanitizeCompanyName(cleanHtml(rawName))
            if (companyName.length < 3) continue

            val series = row.optString("series", "").uppercase(Locale.US)
            // Skip debt/NCD/bond issues so only equity IPO filings appear
            if (series == "DEBT" || series == "NCD" || rawName.contains("NCD", ignoreCase = true)) continue

            val priceText = row.optString("issuePrice").ifBlank { row.optString("priceRange") }
            val (pLow, pHigh) = parsePriceRange(priceText)
            val lotSize = parsePositiveInt(
                row.optString("minBidQuantity").ifBlank { row.optString("lotSize") },
            ) ?: 0

            val rawSizeText = row.optString("issueSize")
            val parsedShares = rawSizeText.replace(",", "").toDoubleOrNull()
            val issueSizeCr = when {
                rawSizeText.contains("cr", ignoreCase = true) -> parseCroresValue(rawSizeText)
                parsedShares != null && parsedShares > 100_000.0 && pHigh > 0 -> {
                    // NSE issueSize is sometimes total equity shares offered; convert shares * upper price to Cr
                    ((parsedShares * pHigh.toDouble()) / 10_000_000.0).coerceAtLeast(0.0)
                }
                else -> parseCroresValue(rawSizeText)
            }

            val (openDate, closeDate, listingDate) = validateScheduleTriple(
                row.optString("issueStartDate"),
                row.optString("issueEndDate"),
                row.optString("listingDate"),
            )
            val statusText = row.optString("status", "")
            val boardType = if (series == "SME" || series == "SM") {
                BoardType.SME
            } else {
                detectBoardType(rawName, series, pLow, lotSize)
            }
            val inferredStatus = inferStatus("$rawName $statusText", openDate, closeDate, pHigh, listingDate)
            val status = when {
                defaultStatus == IpoStatus.UPCOMING -> {
                    if (openDate.isNotBlank() || closeDate.isNotBlank() || listingDate.isNotBlank()) {
                        inferredStatus
                    } else {
                        IpoStatus.UPCOMING
                    }
                }
                defaultStatus != null -> defaultStatus
                else -> inferredStatus
            }
            val isUnconfirmed = IpoFilingVerifier.isHardRejectedStatus("$rawName $statusText") ||
                (status != IpoStatus.UPCOMING && IpoFilingVerifier.containsUnfiledOrRumoredMarkers(rawName))
            val normKey = normalizeCompanyKey(companyName)
            if (normKey.isBlank()) continue

            quotes.add(
                LiveGmpQuote(
                    companyName = companyName,
                    normalizedKey = normKey,
                    gmpRupees = 0,
                    priceLow = pLow,
                    priceHigh = pHigh,
                    lotSize = lotSize,
                    issueSizeCr = issueSizeCr,
                    openDate = openDate,
                    closeDate = closeDate,
                    listingDate = listingDate,
                    boardType = boardType,
                    status = status,
                    totalSubX = parseSubMultiplier(row.optString("noOfTime", "")),
                    sourceName = sourceName,
                    priorityRank = priorityRank,
                    hasExplicitGmp = false,
                    verifiedProviders = IpoFilingVerifier.extractVerifiedProvidersFromLabel(sourceName),
                    isUnconfirmedOrRumored = isUnconfirmed,
                    datePriorityRank = datePriorityForSource(sourceName),
                ),
            )
        }
        return mergeQuotesWithoutDuplicates(quotes)
    }

    internal fun parseInvestorGainJson(
        rawJson: String,
        sourceName: String = "InvestorGain",
        priorityRank: Int = 1,
        defaultStatus: IpoStatus? = null,
        defaultBoardType: BoardType? = null,
    ): List<LiveGmpQuote> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val dataArray: JSONArray = root.optJSONArray("reportTableData")
            ?: root.optJSONArray("data")
            ?: return emptyList()

        val quotes = mutableListOf<LiveGmpQuote>()
        for (i in 0 until dataArray.length()) {
            val row = dataArray.optJSONObject(i) ?: continue
            val map = LinkedHashMap<String, String>()
            val keys = row.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k.lowercase(Locale.US)] = cleanHtml(row.optString(k, ""))
            }

            val rawName = findField(map, "name", "ipo", "company")
            val companyName = sanitizeCompanyName(rawName)
            if (companyName.length < 3) continue

            val priceText = findFieldExcluding(
                map = map,
                exactKeys = listOf("issue price", "ipo price", "offer price", "price", "price band"),
                includeTokens = listOf("price"),
                excludeTokens = listOf("list", "cmp", "gmp", "est", "expected", "current", "open", "close"),
            )
            val (pLow, pHigh) = parsePriceRange(priceText)
            val listingPriceText = findField(map, "listing price", "list price", "listing day", "cmp")
            val lAtPrice = Regex("L@\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                .find("$rawName ${findField(map, "status", "~ipo_status")}")
                ?.groupValues?.getOrNull(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
                ?.roundToInt()
                ?: 0
            val listingPrice = parsePriceRange(listingPriceText).second.takeIf { it > 0 } ?: lAtPrice

            val gmpText = findField(map, "gmp", "~gmp", "listing gain")
            val parsedGmp = parseSignedRupees(gmpText)
            val rawOpenDate = findDateField(
                map = map,
                exactKeys = listOf(
                    "open", "open date", "open_date", "issue open", "issue_open",
                    "ipo open", "offer open", "bid start", "start date", "start_date", "~open_date",
                ),
                includeTokens = listOf("open", "start"),
                excludeTokens = listOf(
                    "price", "cmp", "gmp", "gain", "est", "expected", "percent", "%",
                    "sub", "status", "update", "url", "str_", "listing", "list",
                    "close", "end", "high", "low", "allot", "refund",
                ),
            )
            val rawCloseDate = findDateField(
                map = map,
                exactKeys = listOf(
                    "close", "close date", "close_date", "issue close", "issue_close",
                    "ipo close", "offer close", "bid close", "end date", "end_date", "~close_date",
                ),
                includeTokens = listOf("close", "end"),
                excludeTokens = listOf(
                    "price", "cmp", "gmp", "gain", "est", "expected", "percent", "%",
                    "sub", "status", "update", "url", "str_", "listing", "list",
                    "open", "start", "high", "low", "allot", "refund",
                ),
            )
            val rawListingDate = findDateField(
                map = map,
                exactKeys = listOf(
                    "listing", "listing date", "listing_date", "list date", "list_date", "~listing_date",
                ),
                includeTokens = listOf("listing", "list date"),
                excludeTokens = listOf(
                    "price", "cmp", "gmp", "gain", "est", "expected", "percent", "%",
                    "sub", "status", "update", "url", "str_", "open", "close",
                    "day", "high", "low",
                ),
            )

            var extractedOpen = IpoFilingVerifier.sanitizeConfirmedDate(rawOpenDate)
            var extractedClose = IpoFilingVerifier.sanitizeConfirmedDate(rawCloseDate)
            if (extractedOpen.isBlank() || extractedClose.isBlank()) {
                val combinedRangeRaw = findDateField(
                    map = map,
                    exactKeys = listOf("ipo date", "offer date", "issue date", "bidding date", "date"),
                    includeTokens = listOf("ipo date", "offer date", "issue date", "bidding date", "date"),
                    excludeTokens = listOf(
                        "listing", "list", "allot", "refund", "credit", "demat", "update",
                        "drhp", "rhp", "fil", "price", "gmp", "gain", "est", "sub", "status", "url", "str_",
                    ),
                    allowDateRange = true,
                )
                if (combinedRangeRaw.isNotBlank()) {
                    val (rangeOpen, rangeClose) = IpoDateUtils.parseDateRange(combinedRangeRaw)
                    if (extractedOpen.isBlank()) extractedOpen = rangeOpen
                    if (extractedClose.isBlank()) extractedClose = rangeClose
                }
            }

            val (openDate, closeDate, listingDate) = validateScheduleTriple(
                extractedOpen,
                extractedClose,
                rawListingDate,
            )
            val issueSizeCr = parseCroresValue(findField(map, "size", "issue_size", "ipo_size", "total_issue"))
            val lotSize = parsePositiveInt(findField(map, "lot")) ?: 0
            val subX = parseSubMultiplier(findField(map, "sub"))
            val categoryText = findField(map, "category", "type", "ipo_category")
            val statusHint = "$rawName ${findField(map, "status", "~ipo_status")} $gmpText"
            val boardType = defaultBoardType ?: detectBoardType(rawName, categoryText, pLow, lotSize)
            val status = defaultStatus ?: inferStatus(statusHint, openDate, closeDate, pHigh, listingDate)
            val isUnconfirmed = IpoFilingVerifier.isHardRejectedStatus(statusHint) ||
                (status != IpoStatus.UPCOMING && IpoFilingVerifier.containsUnfiledOrRumoredMarkers(rawName))

            val resolvedGmp: Int
            val hasExplicitGmp: Boolean
            when {
                status == IpoStatus.CLOSED && listingPrice > 0 && pHigh > 0 -> {
                    resolvedGmp = listingPrice - pHigh
                    hasExplicitGmp = true
                }
                parsedGmp != null -> {
                    resolvedGmp = parsedGmp
                    hasExplicitGmp = true
                }
                listingPrice > 0 && pHigh > 0 -> {
                    resolvedGmp = listingPrice - pHigh
                    hasExplicitGmp = true
                }
                status == IpoStatus.CLOSED ||
                    status == IpoStatus.UPCOMING ||
                    status == IpoStatus.PRE_APPLY ||
                    pHigh > 0 ||
                    issueSizeCr > 0.0 ||
                    listingDate.isNotBlank() ||
                    openDate.isNotBlank() -> {
                    resolvedGmp = 0
                    hasExplicitGmp = false
                }
                else -> continue
            }

            val normKey = normalizeCompanyKey(companyName)
            if (normKey.isBlank()) continue

            quotes.add(
                LiveGmpQuote(
                    companyName = companyName,
                    normalizedKey = normKey,
                    gmpRupees = resolvedGmp,
                    priceLow = pLow,
                    priceHigh = pHigh,
                    lotSize = lotSize,
                    issueSizeCr = issueSizeCr,
                    openDate = openDate,
                    closeDate = closeDate,
                    listingDate = listingDate,
                    boardType = boardType,
                    status = status,
                    totalSubX = subX,
                    sourceName = sourceName,
                    priorityRank = priorityRank,
                    hasExplicitGmp = hasExplicitGmp,
                    verifiedProviders = IpoFilingVerifier.extractVerifiedProvidersFromLabel(sourceName),
                    isUnconfirmedOrRumored = isUnconfirmed,
                ),
            )
        }
        return mergeQuotesWithoutDuplicates(quotes)
    }

    internal fun parseHtmlGmpTables(
        html: String,
        sourceName: String = "IPOWatch",
        priorityRank: Int = 2,
        defaultStatus: IpoStatus? = null,
        defaultBoardType: BoardType? = null,
    ): List<LiveGmpQuote> {
        val quotes = mutableListOf<LiveGmpQuote>()
        val tableRegex = Regex("<table\\b[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rowRegex = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<(td|th)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        tableRegex.findAll(html).forEach { tableMatch ->
            val tableBody = tableMatch.groupValues[1]
            val minColumns = if (defaultStatus == IpoStatus.UPCOMING) 2 else 3
            val rows = rowRegex.findAll(tableBody).map { rowMatch ->
                cellRegex.findAll(rowMatch.groupValues[1]).map { cellMatch ->
                    cleanHtml(cellMatch.groupValues[2])
                }.toList()
            }.filter { it.size >= minColumns }.toList()

            if (rows.size < 2) return@forEach

            val headerIndexRow = rows.indexOfFirst { row ->
                val joined = row.joinToString(" ").lowercase(Locale.US)
                val hasNameCol = joined.contains("ipo") || joined.contains("company") || joined.contains("name") || joined.contains("issuer")
                val hasMarketCol = joined.contains("gmp") ||
                    joined.contains("price") ||
                    joined.contains("issue size") ||
                    joined.contains("ipo size") ||
                    joined.contains("size") ||
                    joined.contains("open") ||
                    joined.contains("listing") ||
                    (defaultStatus == IpoStatus.UPCOMING && (
                        joined.contains("date") ||
                            joined.contains("drhp") ||
                            joined.contains("rhp") ||
                            joined.contains("status") ||
                            joined.contains("exchange") ||
                            joined.contains("board") ||
                            joined.contains("type") ||
                            joined.contains("expected") ||
                            joined.contains("filing")
                        ))
                hasNameCol && hasMarketCol
            }
            if (headerIndexRow < 0) return@forEach

            val headers = rows[headerIndexRow].map { it.lowercase(Locale.US) }
            val nameIdx = headers.indexOfFirst {
                it.contains("ipo name") ||
                    it.contains("company") ||
                    it.contains("issuer") ||
                    it == "ipo" ||
                    it.contains("name")
            }.let { if (it >= 0) it else headers.indexOfFirst { h -> h.contains("ipo") } }
            val gmpIdx = headers.indexOfFirst { it == "gmp" || (it.contains("gmp") && !it.contains("%")) }
                .let { if (it >= 0) it else headers.indexOfFirst { h -> h.contains("gmp") } }
            val priceIdx = headers.indexOfFirst {
                it.contains("issue price") || it.contains("ipo price") || it.contains("offer price") || it == "price"
            }.let { if (it >= 0) it else headers.indexOfFirst { h -> h.contains("price") && !h.contains("list") && !h.contains("cmp") && !h.contains("current") } }
            val listPriceIdx = headers.indexOfFirst {
                it.contains("listing price") || it.contains("list price") || it.contains("listing open") || it.contains("listing close") || it == "cmp" || it.contains("current price")
            }
            val sizeIdx = headers.indexOfFirst { it.contains("size") }
            val lotIdx = headers.indexOfFirst { it.contains("lot") }
            val openIdx = headers.indexOfFirst { h ->
                h.contains("open") &&
                    !h.contains("close") &&
                    !h.contains("list") &&
                    !h.contains("price") &&
                    !h.contains("gain") &&
                    !h.contains("status") &&
                    !h.contains("gmp") &&
                    !h.contains("sub") &&
                    !h.contains("cmp") &&
                    !h.contains("update")
            }
            val closeIdx = headers.indexOfFirst { h ->
                h.contains("close") &&
                    !h.contains("open") &&
                    !h.contains("list") &&
                    !h.contains("price") &&
                    !h.contains("gain") &&
                    !h.contains("status") &&
                    !h.contains("gmp") &&
                    !h.contains("sub") &&
                    !h.contains("cmp") &&
                    !h.contains("update")
            }
            val listingIdx = headers.indexOfFirst { h ->
                h.contains("list") &&
                    h.contains("date") &&
                    !h.contains("price") &&
                    !h.contains("gain") &&
                    !h.contains("est") &&
                    !h.contains("open") &&
                    !h.contains("close")
            }.let { idx ->
                if (idx >= 0) {
                    idx
                } else {
                    headers.indexOfFirst { h ->
                        h.contains("list") &&
                            !h.contains("price") &&
                            !h.contains("gain") &&
                            !h.contains("est") &&
                            !h.contains("open") &&
                            !h.contains("close") &&
                            !h.contains("cmp") &&
                            !h.contains("%") &&
                            !h.contains("gmp") &&
                            !h.contains("day")
                    }
                }
            }
            val dateIdx = headers.indexOfFirst { h ->
                (h.contains("date") || (h.contains("open") && !h.contains("list"))) &&
                    !h.contains("list") &&
                    !h.contains("drhp") &&
                    !h.contains("rhp") &&
                    !h.contains("fil") &&
                    !h.contains("allot") &&
                    !h.contains("refund") &&
                    !h.contains("update") &&
                    !h.contains("price") &&
                    !h.contains("gmp") &&
                    !h.contains("gain") &&
                    !h.contains("est") &&
                    !h.contains("sub") &&
                    !h.contains("status")
            }
            val hasUpcomingMetaCol = headers.any { h ->
                h.contains("drhp") || h.contains("rhp") || h.contains("fil") || h.contains("date") || h.contains("expected")
            }
            val typeIdx = headers.indexOfFirst { it.contains("type") || it.contains("category") || it.contains("board") || it.contains("exchange") }
            val statusIdx = headers.indexOfFirst { it.contains("status") }
            val subIdx = headers.indexOfFirst { it.contains("sub") }

            if (nameIdx < 0) return@forEach
            val hasUsefulColumn = gmpIdx >= 0 || priceIdx >= 0 || sizeIdx >= 0 || listingIdx >= 0 ||
                (defaultStatus == IpoStatus.UPCOMING && (dateIdx >= 0 || openIdx >= 0 || typeIdx >= 0 || statusIdx >= 0 || hasUpcomingMetaCol))
            if (!hasUsefulColumn) return@forEach

            rows.drop(headerIndexRow + 1).forEach { cells ->
                if (cells.size <= nameIdx) return@forEach
                val rawName = cells[nameIdx]
                val companyName = sanitizeCompanyName(rawName)
                if (!IpoFilingVerifier.isValidCompanyName(companyName)) return@forEach

                val priceRaw = if (priceIdx in cells.indices) cells[priceIdx] else ""
                val (pLow, pHigh) = parsePriceRange(priceRaw)
                val listPriceRaw = if (listPriceIdx in cells.indices) cells[listPriceIdx] else ""
                val listPrice = parsePriceRange(listPriceRaw).second
                val issueSizeCr = if (sizeIdx in cells.indices) parseCroresValue(cells[sizeIdx]) else 0.0
                val lotSize = if (lotIdx in cells.indices) (parsePositiveInt(cells[lotIdx]) ?: 0) else 0

                val openRaw = if (openIdx in cells.indices) cells[openIdx] else ""
                val closeRaw = if (closeIdx in cells.indices) cells[closeIdx] else ""
                val dateRaw = if (dateIdx in cells.indices && dateIdx != listingIdx) cells[dateIdx] else ""

                var parsedOpen = ""
                var parsedClose = ""
                when {
                    openRaw.isNotBlank() && closeRaw.isNotBlank() -> {
                        parsedOpen = IpoFilingVerifier.sanitizeConfirmedDate(openRaw)
                        parsedClose = IpoFilingVerifier.sanitizeConfirmedDate(closeRaw)
                    }
                    openRaw.isNotBlank() && closeRaw.isBlank() -> {
                        val (o1, c1) = IpoDateUtils.parseDateRange(openRaw)
                        parsedOpen = o1.ifBlank { IpoFilingVerifier.sanitizeConfirmedDate(openRaw) }
                        parsedClose = c1
                        if (parsedClose.isBlank() && dateIdx != openIdx && dateRaw.isNotBlank()) {
                            val (o2, c2) = IpoDateUtils.parseDateRange(dateRaw)
                            if (parsedOpen.isBlank()) parsedOpen = o2
                            if (c2.isNotBlank()) parsedClose = c2
                        }
                    }
                    openRaw.isBlank() && closeRaw.isNotBlank() -> {
                        parsedClose = IpoFilingVerifier.sanitizeConfirmedDate(closeRaw)
                        if (dateIdx != closeIdx && dateRaw.isNotBlank()) {
                            val (o2, _) = IpoDateUtils.parseDateRange(dateRaw)
                            if (o2.isNotBlank()) parsedOpen = o2
                        }
                    }
                    defaultStatus != IpoStatus.CLOSED && dateRaw.isNotBlank() -> {
                        val (o, c) = IpoDateUtils.parseDateRange(dateRaw)
                        parsedOpen = o
                        parsedClose = c
                    }
                }

                val rawListingCell = if (listingIdx in cells.indices) cells[listingIdx] else ""
                val (openDate, closeDate, listingDate) = validateScheduleTriple(
                    parsedOpen,
                    parsedClose,
                    rawListingCell,
                )

                val rawGmpCell = if (gmpIdx in cells.indices) cells[gmpIdx] else ""
                val parsedGmp = if (gmpIdx in cells.indices) parseSignedRupees(rawGmpCell) else null
                val hasExplicitGmp: Boolean
                val gmpRupees: Int
                when {
                    parsedGmp != null -> {
                        gmpRupees = parsedGmp
                        hasExplicitGmp = true
                    }
                    listPrice > 0 && pHigh > 0 -> {
                        gmpRupees = listPrice - pHigh
                        hasExplicitGmp = true
                    }
                    else -> {
                        gmpRupees = 0
                        hasExplicitGmp = false
                    }
                }

                val typeRaw = if (typeIdx in cells.indices) cells[typeIdx] else ""
                val statusRaw = if (statusIdx in cells.indices) cells[statusIdx] else ""
                val subX = if (subIdx in cells.indices) parseSubMultiplier(cells[subIdx]) else 0.0
                val boardType = defaultBoardType ?: detectBoardType(rawName, typeRaw, pLow, lotSize)
                val inferredStatus = inferStatus("$rawName $statusRaw $rawGmpCell", openDate, closeDate, pHigh, listingDate)
                val status = when {
                    defaultStatus == IpoStatus.CLOSED -> IpoStatus.CLOSED
                    defaultStatus == IpoStatus.UPCOMING -> {
                        // Preserve LIVE/CLOSED if dates show it already opened or closed, otherwise UPCOMING
                        if (openDate.isNotBlank() || closeDate.isNotBlank() || listingDate.isNotBlank()) {
                            inferredStatus
                        } else {
                            IpoStatus.UPCOMING
                        }
                    }
                    else -> inferredStatus
                }

                if (defaultStatus != IpoStatus.UPCOMING &&
                    status != IpoStatus.UPCOMING &&
                    !hasExplicitGmp && pHigh == 0 && issueSizeCr <= 0.0 && openDate.isBlank() && listingDate.isBlank()
                ) {
                    return@forEach
                }

                val isUnconfirmed = IpoFilingVerifier.isHardRejectedStatus("$rawName $statusRaw") ||
                    (status != IpoStatus.UPCOMING && IpoFilingVerifier.containsUnfiledOrRumoredMarkers(rawName))
                val normKey = normalizeCompanyKey(companyName)
                if (normKey.isBlank()) return@forEach

                quotes.add(
                    LiveGmpQuote(
                        companyName = companyName,
                        normalizedKey = normKey,
                        gmpRupees = gmpRupees,
                        priceLow = pLow,
                        priceHigh = pHigh,
                        lotSize = lotSize,
                        issueSizeCr = issueSizeCr,
                        openDate = openDate,
                        closeDate = closeDate,
                        listingDate = listingDate,
                        boardType = boardType,
                        status = status,
                        totalSubX = subX,
                        sourceName = sourceName,
                        priorityRank = priorityRank,
                        hasExplicitGmp = hasExplicitGmp,
                        verifiedProviders = IpoFilingVerifier.extractVerifiedProvidersFromLabel(sourceName),
                        isUnconfirmedOrRumored = isUnconfirmed,
                    ),
                )
            }
        }
        return mergeQuotesWithoutDuplicates(quotes)
    }

    internal fun parseRssGmpUpdates(
        xml: String,
        sourceName: String = "Google News GMP",
        priorityRank: Int = 8,
    ): List<LiveGmpQuote> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        val quotes = mutableListOf<LiveGmpQuote>()
        var insideItem = false
        var title = ""
        var description = ""
        var eventType = parser.eventType

        val gmpHeadlineRegex = Regex(
            "(?:gmp|grey market premium)[^\\d+\\-]{0,18}([+-]?\\s*(?:₹|rs\\.?|inr)?\\s*\\d{1,4})",
            RegexOption.IGNORE_CASE,
        )

        while (eventType != XmlPullParser.END_DOCUMENT && quotes.size < 20) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.US)) {
                    "item" -> {
                        insideItem = true
                        title = ""
                        description = ""
                    }
                    "title" -> if (insideItem) title = safeNextText(parser)
                    "description" -> if (insideItem) description = safeNextText(parser)
                }

                XmlPullParser.END_TAG -> if (parser.name.equals("item", ignoreCase = true) && insideItem) {
                    insideItem = false
                    val combined = cleanHtml("$title $description")
                    val gmpMatch = gmpHeadlineRegex.find(combined)
                    val gmpValue = gmpMatch?.groupValues?.getOrNull(1)?.let { parseSignedRupees(it) }
                    val candidateName = title.substringBefore(" IPO").substringBefore(" - ").trim()
                    if (gmpValue != null && candidateName.length >= 3) {
                        val cleanName = sanitizeCompanyName(candidateName)
                        val key = normalizeCompanyKey(cleanName)
                        if (key.length >= 3) {
                            quotes.add(
                                LiveGmpQuote(
                                    companyName = cleanName,
                                    normalizedKey = key,
                                    gmpRupees = gmpValue,
                                    sourceName = sourceName,
                                    priorityRank = priorityRank,
                                    hasExplicitGmp = true,
                                    verifiedProviders = emptySet(),
                                ),
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return mergeQuotesWithoutDuplicates(quotes)
    }

    private fun safeNextText(parser: XmlPullParser): String =
        runCatching {
            if (parser.next() == XmlPullParser.TEXT) {
                val text = parser.text.orEmpty()
                parser.nextTag()
                text
            } else {
                ""
            }
        }.getOrDefault("")

    internal fun tokenizeCompanyName(name: String): List<String> {
        val stopWords = setOf(
            "ltd", "limited", "pvt", "private", "llp", "inc", "plc",
            "corp", "corporation", "co", "company", "ipo", "sme",
            "nse", "bse", "mainboard", "the", "and", "of",
        )
        val rawTokens = name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val filtered = rawTokens.filter { it !in stopWords }
        return if (filtered.isNotEmpty()) filtered else rawTokens
    }

    internal fun normalizeCompanyKey(name: String): String {
        val tokens = tokenizeCompanyName(name)
        return if (tokens.isNotEmpty()) {
            tokens.joinToString("")
        } else {
            name.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        }
    }

    private fun findField(map: Map<String, String>, vararg keyParts: String): String {
        for (part in keyParts) {
            map.entries.firstOrNull { (k, v) -> k.contains(part) && v.isNotBlank() }?.let { return it.value }
        }
        return ""
    }

    private fun findFieldExcluding(
        map: Map<String, String>,
        exactKeys: List<String>,
        includeTokens: List<String>,
        excludeTokens: List<String>,
    ): String {
        for (exact in exactKeys) {
            val value = map[exact]
            if (!value.isNullOrBlank()) return value
        }
        for (token in includeTokens) {
            map.entries.firstOrNull { (k, v) ->
                v.isNotBlank() &&
                    k.contains(token) &&
                    excludeTokens.none { ex -> k.contains(ex) }
            }?.let { return it.value }
        }
        return ""
    }

    private fun findDateField(
        map: Map<String, String>,
        exactKeys: List<String>,
        includeTokens: List<String>,
        excludeTokens: List<String>,
        allowDateRange: Boolean = false,
    ): String {
        fun isCandidateDateValue(raw: String): Boolean {
            if (raw.isBlank()) return false
            if (IpoFilingVerifier.sanitizeConfirmedDate(raw).isNotBlank()) return true
            return allowDateRange && IpoDateUtils.parseDateRange(raw).first.isNotBlank()
        }

        for (exact in exactKeys) {
            val value = map[exact]
            if (value != null && isCandidateDateValue(value)) return value
        }
        for (token in includeTokens) {
            map.entries.firstOrNull { (k, v) ->
                k.contains(token) &&
                    excludeTokens.none { ex -> k.contains(ex) } &&
                    isCandidateDateValue(v)
            }?.let { return it.value }
        }
        return ""
    }

    private fun isListingConsistentWithWindow(
        listingDate: String,
        openDate: String,
        closeDate: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val listMs = IpoDateUtils.parseFlexibleDateMs(listingDate, referenceTimeMs) ?: return false
        val closeMs = IpoDateUtils.parseFlexibleDateMs(closeDate, referenceTimeMs)
        if (closeMs != null && listMs < closeMs) return false
        val openMs = IpoDateUtils.parseFlexibleDateMs(openDate, referenceTimeMs)
        if (openMs != null && listMs < openMs) return false
        return true
    }

    internal fun validateScheduleTriple(
        rawOpenDate: String,
        rawCloseDate: String,
        rawListingDate: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Triple<String, String, String> {
        var openDate = IpoFilingVerifier.sanitizeConfirmedDate(rawOpenDate, referenceTimeMs)
        var closeDate = IpoFilingVerifier.sanitizeConfirmedDate(rawCloseDate, referenceTimeMs)
        var listingDate = IpoFilingVerifier.sanitizeConfirmedDate(rawListingDate, referenceTimeMs)

        val openMs = IpoDateUtils.parseFlexibleDateMs(openDate, referenceTimeMs)
        val closeMs = IpoDateUtils.parseFlexibleDateMs(closeDate, referenceTimeMs)
        if (openMs != null && closeMs != null && !IpoDateUtils.isValidBiddingWindow(openMs, closeMs)) {
            openDate = ""
            closeDate = ""
        }

        val validOpenMs = IpoDateUtils.parseFlexibleDateMs(openDate, referenceTimeMs)
        val validCloseMs = IpoDateUtils.parseFlexibleDateMs(closeDate, referenceTimeMs)
        val listMs = IpoDateUtils.parseFlexibleDateMs(listingDate, referenceTimeMs)
        if (listMs != null) {
            if ((validCloseMs != null && listMs < validCloseMs) || (validOpenMs != null && listMs < validOpenMs)) {
                listingDate = ""
            }
        }
        return Triple(openDate, closeDate, listingDate)
    }

    private fun cleanHtml(raw: String): String {
        val decoded = Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()
        return decoded.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun sanitizeCompanyName(raw: String): String {
        return raw
            .replace(Regex("\\b(NSE|BSE|SME)\\s*IPO\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bIPO\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\([^)]*\\)$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseSignedRupees(raw: String): Int? {
        val text = raw.trim()
        if (text.isBlank() || text == "-" || text == "--" || text.equals("na", ignoreCase = true) || text.equals("tba", ignoreCase = true)) {
            return if (text == "--" || text == "-") 0 else null
        }
        val beforeParens = text.substringBefore("(").trim()
        val match = Regex("([+-]?\\s*\\d[\\d,]*)").find(
            beforeParens.replace("₹", "").replace("Rs.", "", ignoreCase = true).replace("Rs", "", ignoreCase = true),
        ) ?: return null
        val cleaned = match.groupValues[1].replace(",", "").replace(" ", "")
        return cleaned.toIntOrNull()?.takeIf { it in -5_000..15_000 }
    }

    private fun parsePositiveInt(raw: String): Int? {
        val match = Regex("(\\d[\\d,]*)").find(raw) ?: return null
        return match.groupValues[1].replace(",", "").toIntOrNull()
    }

    private fun parseCroresValue(raw: String): Double {
        val text = raw.replace(",", "").trim()
        if (text.isBlank() || text == "-" || text == "--") return 0.0
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(text) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        return if (value in 0.5..500_000.0) value else 0.0
    }

    private fun parsePriceRange(raw: String): Pair<Int, Int> {
        val matches = Regex("(\\d[\\d,]*(?:\\.\\d+)?)").findAll(raw.replace(",", ""))
            .mapNotNull { it.groupValues[1].toDoubleOrNull()?.roundToInt() }
            .filter { it in 5..25_000 }
            .toList()
        return when {
            matches.size >= 2 -> {
                val low = minOf(matches[0], matches[1])
                val high = maxOf(matches[0], matches[1])
                low to high
            }
            matches.size == 1 -> matches[0] to matches[0]
            else -> 0 to 0
        }
    }

    private fun parseSubMultiplier(raw: String): Double {
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*x?", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    }

    internal fun detectBoardType(
        rawName: String,
        categoryRaw: String,
        priceLow: Int,
        lotSize: Int,
    ): BoardType {
        val combined = "$rawName $categoryRaw".lowercase(Locale.US)
        return if (SME_WORD_REGEX.containsMatchIn(combined) || (lotSize > 0 && priceLow > 0 && lotSize * priceLow >= 90_000)) {
            BoardType.SME
        } else {
            BoardType.MAINBOARD
        }
    }

    /**
     * Date-first IPO lifecycle status inference (IST).
     * Verified calendar dates always take precedence over stale/fuzzy status text tags,
     * and missing or unconfirmed dates NEVER produce [IpoStatus.LIVE].
     */
    internal fun inferStatus(
        statusHint: String,
        openDate: String,
        closeDate: String,
        priceHigh: Int,
        listingDate: String = "",
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): IpoStatus {
        val lower = statusHint.lowercase(Locale.US)
        val today = IpoDateUtils.startOfDayMs(referenceTimeMs)
        val openMs = IpoDateUtils.parseFlexibleDateMs(openDate, referenceTimeMs)
        val closeMs = IpoDateUtils.parseFlexibleDateMs(closeDate, referenceTimeMs)
        val listMs = IpoDateUtils.parseFlexibleDateMs(listingDate, referenceTimeMs)

        // 1. Verified calendar dates always win over fuzzy/stale text labels.
        if (listMs != null && today >= listMs) return IpoStatus.CLOSED
        if (closeMs != null && today > closeMs) return IpoStatus.CLOSED

        if (openMs != null && closeMs != null && IpoDateUtils.isValidBiddingWindow(openMs, closeMs)) {
            return when {
                today in openMs..closeMs -> {
                    if (lower.contains("listed") || lower.contains("l@")) IpoStatus.CLOSED else IpoStatus.LIVE
                }
                openMs > today -> {
                    if (openMs - today <= 5L * 86_400_000L && priceHigh > 0) IpoStatus.PRE_APPLY else IpoStatus.UPCOMING
                }
                else -> IpoStatus.CLOSED
            }
        }

        if (openMs != null) {
            return when {
                openMs > today -> {
                    if (openMs - today <= 5L * 86_400_000L && priceHigh > 0) IpoStatus.PRE_APPLY else IpoStatus.UPCOMING
                }
                today - openMs > 7L * 86_400_000L -> IpoStatus.CLOSED
                else -> IpoStatus.UPCOMING
            }
        }

        // 2. Fallback to explicit status hint ONLY when calendar dates are absent.
        // Never allow missing/TBA dates to become LIVE.
        return when {
            lower.contains("listed") || lower.contains("l@") || lower.contains("close") || lower.contains("allot") -> IpoStatus.CLOSED
            lower.contains("pre-apply") || lower.contains("pre apply") || lower.contains("preapply") -> IpoStatus.PRE_APPLY
            else -> IpoStatus.UPCOMING
        }
    }

    internal enum class SourceKind {
        NSE_JSON,
        INVESTORGAIN_JSON,
        HTML_TABLE,
        RSS_FEED,
    }

    internal data class SourceTask(
        val name: String,
        val priorityRank: Int,
        val url: String,
        val kind: SourceKind,
        val referer: String? = null,
        val defaultStatus: IpoStatus? = null,
        val defaultBoardType: BoardType? = null,
    )

    private class InMemorySessionCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = store.computeIfAbsent(host) { mutableListOf() }
            synchronized(existing) {
                for (cookie in cookies) {
                    existing.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    if (cookie.expiresAt > System.currentTimeMillis()) {
                        existing.add(cookie)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val result = mutableListOf<Cookie>()
            store.values.forEach { list ->
                synchronized(list) {
                    list.removeAll { it.expiresAt <= now }
                    list.filterTo(result) { it.matches(url) }
                }
            }
            return result
        }
    }

    companion object {
        private val SME_WORD_REGEX = Regex("\\bsme\\b")
        private const val NSE_WARMUP_INTERVAL_MS = 10L * 60_000L
        internal const val PER_SOURCE_TIMEOUT_MS = 7_000L
        internal const val NSE_WARMUP_TIMEOUT_MS = 4_000L
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        const val DATE_PRIORITY_OFFICIAL_EXCHANGE = 0
        const val DATE_PRIORITY_CHITTORGARH = 1
        const val DATE_PRIORITY_INVESTORGAIN = 2
        const val DATE_PRIORITY_IPOWATCH = 3
        const val DATE_PRIORITY_IPOCENTRAL = 4
        const val DATE_PRIORITY_IPOJI = 5
        const val DATE_PRIORITY_OTHER = 9

        fun datePriorityForSource(sourceName: String): Int {
            val lower = sourceName.lowercase(Locale.US)
            return when {
                lower.contains("nse") || lower.contains("bse") || lower.contains("sebi") -> DATE_PRIORITY_OFFICIAL_EXCHANGE
                lower.contains("chittorgarh") -> DATE_PRIORITY_CHITTORGARH
                lower.contains("investorgain") -> DATE_PRIORITY_INVESTORGAIN
                lower.contains("ipowatch") -> DATE_PRIORITY_IPOWATCH
                lower.contains("ipocentral") || lower.contains("ipo central") -> DATE_PRIORITY_IPOCENTRAL
                lower.contains("ipoji") -> DATE_PRIORITY_IPOJI
                else -> DATE_PRIORITY_OTHER
            }
        }
    }
}
