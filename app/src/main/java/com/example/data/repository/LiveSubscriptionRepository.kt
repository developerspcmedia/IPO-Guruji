package com.example.data.repository

import android.text.Html
import com.example.data.model.BoardType
import com.example.data.model.DaySubscription
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
) {
    val hasCategoryBreakdown: Boolean
        get() = qibSubX > 0.0 || bNiiSubX > 0.0 || sNiiSubX > 0.0 || niiTotalSubX > 0.0 || retailSubX > 0.0

    val hasAnySubscription: Boolean
        get() = totalSubX > 0.0 || hasCategoryBreakdown
}

data class LiveSubscriptionSnapshot(
    val quotesByKey: Map<String, LiveSubscriptionQuote>,
    val allQuotes: List<LiveSubscriptionQuote>,
    val activeSources: List<String>,
    val syncedAtTime: String,
    val syncedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Dedicated multi-source live IPO category-wise subscription fetcher.
 * Aggregates QIB, bNII (>₹10L), sNII (<₹10L), NII Total, Retail (RII), Employee, and Total
 * subscription multiples from NSE India, InvestorGain, Chittorgarh, and IPOWatch.
 */
class LiveSubscriptionRepository(
    private val enableNetworkFetch: Boolean = true,
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var lastSnapshot: LiveSubscriptionSnapshot? = null

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

        val now = Calendar.getInstance()
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
            "https://webnodejs.investorgain.com/cloud/report/data-read/333/1/$monthFormatted/$year/$fyParam/0/all?search=&v=1"
        val investorGainPrevSubUrl =
            "https://webnodejs.investorgain.com/cloud/report/data-read/333/1/$prevMonthFormatted/$prevYear/$prevFyParam/0/all?search=&v=1"

        val tasks = mutableListOf(
            SubSourceTask(
                name = "NSE India",
                priorityRank = 0,
                url = "https://www.nseindia.com/api/ipo-current-issue",
                kind = SubSourceKind.NSE_CURRENT_JSON,
                referer = "https://www.nseindia.com/market-data/all-upcoming-issues-ipo",
            ),
            SubSourceTask(
                name = "InvestorGain",
                priorityRank = 1,
                url = investorGainSubUrl,
                kind = SubSourceKind.INVESTORGAIN_JSON,
                referer = "https://www.investorgain.com/report/ipo-subscription-live/333/",
            ),
            SubSourceTask(
                name = "Chittorgarh",
                priorityRank = 2,
                url = "https://www.chittorgarh.com/report/ipo-subscription-status-live-bidding-data-bse-nse/21/",
                kind = SubSourceKind.HTML_TABLE,
                defaultBoardType = BoardType.MAINBOARD,
            ),
            SubSourceTask(
                name = "Chittorgarh SME",
                priorityRank = 3,
                url = "https://www.chittorgarh.com/report/sme-ipo-subscription-status-live-bidding-bse-nse/22/",
                kind = SubSourceKind.HTML_TABLE,
                defaultBoardType = BoardType.SME,
            ),
            SubSourceTask(
                name = "IPOWatch",
                priorityRank = 4,
                url = "https://ipowatch.in/ipo-subscription-status-today/",
                kind = SubSourceKind.HTML_TABLE,
            ),
            SubSourceTask(
                name = "InvestorGain Prev",
                priorityRank = 5,
                url = investorGainPrevSubUrl,
                kind = SubSourceKind.INVESTORGAIN_JSON,
                referer = "https://www.investorgain.com/report/ipo-subscription-live/333/",
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
                    priorityRank = -1,
                    url = "https://www.nseindia.com/api/ipo-bid-details?symbol=$encodedSymbol",
                    kind = SubSourceKind.NSE_BID_DETAIL_JSON,
                    referer = "https://www.nseindia.com/market-data/issue-information?symbol=$encodedSymbol&series=EQ&type=Active",
                    targetCompanyName = targetCompanyName,
                    targetSymbol = cleanedSymbol,
                ),
            )
        }

        val activeSources = mutableListOf<String>()
        val rawQuotes = mutableListOf<LiveSubscriptionQuote>()

        coroutineScope {
            val results = tasks.map { task ->
                async {
                    val body = withTimeoutOrNull(8_500L) {
                        fetchText(url = task.url, referer = task.referer)
                    } ?: return@async task.name to emptyList()

                    val parsed = when (task.kind) {
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
                        )
                    }
                    task.name to parsed
                }
            }.awaitAll()

            results.forEach { (sourceName, quotes) ->
                if (quotes.isNotEmpty()) {
                    activeSources.add(sourceName)
                    rawQuotes.addAll(quotes)
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
        )
        if (merged.isNotEmpty()) {
            lastSnapshot = snapshot
        }
        snapshot
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
                        obj.optString("Big NII"),
                        obj.optString("NII (>10L)"),
                    ),
                )
                val sNii = parseSubscriptionMultiplier(
                    firstNonBlank(
                        obj.optString("sNII"),
                        obj.optString("sNII (x)"),
                        obj.optString("SNII"),
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
     * (Qualified Institutional Buyers, Non Institutional Investors, Retail Individual Investors, Employees, Total).
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
                if (times <= 0.0) continue

                when {
                    category.contains("total") -> total = maxOf(total, times)
                    category.contains("qualified") || category.contains("qib") -> qib = maxOf(qib, times)
                    category.contains("above") && (category.contains("10") || category.contains("nii") || category.contains("hni")) ->
                        bNii = maxOf(bNii, times)
                    (category.contains("below") || category.contains("upto") || category.contains("2 lakh")) &&
                        (category.contains("10") || category.contains("nii") || category.contains("hni")) ->
                        sNii = maxOf(sNii, times)
                    category.contains("non institutional") || category.contains("nii") || category.contains("hni") ->
                        nii = maxOf(nii, times)
                    category.contains("retail") || category.contains("rii") -> retail = maxOf(retail, times)
                    category.contains("employee") || category.contains("emp") -> emp = maxOf(emp, times)
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
        )
        return if (quote.hasAnySubscription) listOf(quote) else emptyList()
    }

    internal fun parseHtmlSubscriptionTables(
        html: String,
        sourceName: String,
        priorityRank: Int,
        asOfLabel: String = "",
        defaultBoardType: BoardType? = null,
    ): List<LiveSubscriptionQuote> {
        val result = mutableListOf<LiveSubscriptionQuote>()
        val tableRegex = Regex("<table[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        tableRegex.findAll(html).forEach { tableMatch ->
            val rows = rowRegex.findAll(tableMatch.groupValues[1]).map { rowMatch ->
                cellRegex.findAll(rowMatch.groupValues[1])
                    .map { stripHtml(it.groupValues[1]) }
                    .toList()
            }.filter { it.size >= 4 }.toList()
            if (rows.size < 2) return@forEach

            var nameIdx = -1
            var qibIdx = -1
            var bNiiIdx = -1
            var sNiiIdx = -1
            var niiIdx = -1
            var retailIdx = -1
            var empIdx = -1
            var totalIdx = -1
            var headerRowIndex = -1

            rows.take(3).forEachIndexed { rIdx, row ->
                val lower = row.map { it.lowercase(Locale.US) }
                val foundName = lower.indexOfFirst { it.contains("company") || it.contains("ipo") || it.contains("name") }
                val foundQib = lower.indexOfFirst { it.contains("qib") }
                val foundRetail = lower.indexOfFirst { it.contains("retail") || it == "rii" || it.contains("rii") }
                val foundTotal = lower.indexOfFirst { it.contains("total") || it.contains("subscription") }
                if (foundName >= 0 && (foundQib >= 0 || foundRetail >= 0 || foundTotal >= 0)) {
                    nameIdx = foundName
                    qibIdx = foundQib
                    bNiiIdx = lower.indexOfFirst { it.contains("bnii") || it.contains("b-nii") || it.contains(">10") || it.contains("big nii") }
                    sNiiIdx = lower.indexOfFirst { it.contains("snii") || it.contains("s-nii") || it.contains("<10") || it.contains("small nii") }
                    niiIdx = lower.indexOfFirst { (it == "nii" || it.startsWith("nii") || it.contains("hni")) && !it.contains("bnii") && !it.contains("snii") }
                    retailIdx = foundRetail
                    empIdx = lower.indexOfFirst { it.contains("emp") }
                    totalIdx = foundTotal
                    headerRowIndex = rIdx
                }
            }
            if (headerRowIndex < 0 || nameIdx < 0) return@forEach

            rows.drop(headerRowIndex + 1).forEach { cells ->
                val rawName = cells.getOrNull(nameIdx).orEmpty()
                val cleanName = cleanCompanyName(rawName)
                if (cleanName.length < 3 || cleanName.equals("Company Name", ignoreCase = true)) return@forEach

                val boardType = when {
                    rawName.contains("SME", ignoreCase = true) -> BoardType.SME
                    defaultBoardType != null -> defaultBoardType
                    else -> null
                }

                val qib = qibIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                val bNii = bNiiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                val sNii = sNiiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                val nii = niiIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                val retail = retailIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                val emp = empIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0
                val total = totalIdx.takeIf { it >= 0 }?.let { parseSubscriptionMultiplier(cells.getOrNull(it)) } ?: 0.0

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
                )
                if (quote.hasAnySubscription) {
                    result.add(quote)
                }
            }
        }
        return result
    }

    internal fun mergeSubscriptionQuotes(quotes: List<LiveSubscriptionQuote>): List<LiveSubscriptionQuote> {
        if (quotes.isEmpty()) return emptyList()
        val sorted = quotes.sortedWith(
            compareBy<LiveSubscriptionQuote> { it.priorityRank }
                .thenByDescending { it.hasCategoryBreakdown }
                .thenByDescending { it.totalSubX },
        )
        val merged = mutableListOf<LiveSubscriptionQuote>()
        for (candidate in sorted) {
            val existingIndex = merged.indexOfFirst { existing ->
                areSameSubscriptionCompany(existing.companyName, candidate.companyName, existing.boardType, candidate.boardType)
            }
            if (existingIndex < 0) {
                merged.add(candidate)
            } else {
                val base = merged[existingIndex]
                val combinedSources = (base.sourceName.split(" • ") + candidate.sourceName.split(" • "))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(3)
                    .joinToString(" • ")

                // Prefer the freshest/highest non-zero values (live IPO subscription is monotonically non-decreasing)
                val resolvedQib = maxOf(base.qibSubX, candidate.qibSubX)
                val resolvedBNii = maxOf(base.bNiiSubX, candidate.bNiiSubX)
                val resolvedSNii = maxOf(base.sNiiSubX, candidate.sNiiSubX)
                val resolvedNiiTotal = maxOf(base.niiTotalSubX, candidate.niiTotalSubX)
                val resolvedRetail = maxOf(base.retailSubX, candidate.retailSubX)
                val resolvedEmp = maxOf(base.employeeSubX, candidate.employeeSubX)
                val resolvedTotal = maxOf(base.totalSubX, candidate.totalSubX)

                merged[existingIndex] = base.copy(
                    boardType = base.boardType ?: candidate.boardType,
                    qibSubX = resolvedQib,
                    bNiiSubX = if (resolvedBNii > 0.0) resolvedBNii else resolvedNiiTotal,
                    sNiiSubX = if (resolvedSNii > 0.0) resolvedSNii else resolvedNiiTotal,
                    niiTotalSubX = if (resolvedNiiTotal > 0.0) {
                        resolvedNiiTotal
                    } else if (resolvedBNii > 0.0 || resolvedSNii > 0.0) {
                        roundTwoDecimals((resolvedBNii * 2.0 + resolvedSNii) / 3.0)
                    } else {
                        0.0
                    },
                    retailSubX = resolvedRetail,
                    employeeSubX = resolvedEmp,
                    totalSubX = resolvedTotal,
                    sourceName = combinedSources,
                    symbol = base.symbol.ifBlank { candidate.symbol },
                    asOfLabel = base.asOfLabel.ifBlank { candidate.asOfLabel },
                )
            }
        }
        return merged
    }

    internal fun applySubscriptionToEntity(
        entity: IpoEntity,
        quote: LiveSubscriptionQuote,
        syncTime: String,
    ): IpoEntity {
        val nextQib = if (quote.qibSubX > 0.0) quote.qibSubX else entity.qibSubX
        val nextBNii = when {
            quote.bNiiSubX > 0.0 -> quote.bNiiSubX
            quote.niiTotalSubX > 0.0 && entity.bNiiSubX <= 0.0 -> quote.niiTotalSubX
            else -> entity.bNiiSubX
        }
        val nextSNii = when {
            quote.sNiiSubX > 0.0 -> quote.sNiiSubX
            quote.niiTotalSubX > 0.0 && entity.sNiiSubX <= 0.0 -> quote.niiTotalSubX
            else -> entity.sNiiSubX
        }
        val nextRetail = if (quote.retailSubX > 0.0) quote.retailSubX else entity.retailSubX
        val nextEmp = if (quote.employeeSubX > 0.0) quote.employeeSubX else entity.employeeSubX
        val nextTotal = maxOf(quote.totalSubX, entity.totalSubX).takeIf { quote.totalSubX > 0.0 }
            ?: entity.totalSubX

        val niiForRow = when {
            quote.niiTotalSubX > 0.0 -> quote.niiTotalSubX
            nextBNii > 0.0 || nextSNii > 0.0 -> roundTwoDecimals((nextBNii * 2.0 + nextSNii) / 3.0)
            else -> entity.niiCombinedSubX
        }

        val updatedDaily = if (nextTotal > 0.0 || nextQib > 0.0 || nextRetail > 0.0 || niiForRow > 0.0) {
            val liveRowLabel = if (entity.status == IpoStatus.LIVE) {
                "Live"
            } else {
                entity.dailySubscriptions.lastOrNull()?.dayLabel ?: "Final"
            }
            val liveRow = DaySubscription(
                dayLabel = liveRowLabel,
                qibX = roundTwoDecimals(nextQib),
                niiX = roundTwoDecimals(niiForRow),
                retailX = roundTwoDecimals(nextRetail),
                totalX = roundTwoDecimals(nextTotal),
            )
            val withoutPrevLive = entity.dailySubscriptions.filterNot {
                it.dayLabel.equals("Live", ignoreCase = true) || it.dayLabel.equals("Final", ignoreCase = true)
            }
            withoutPrevLive + liveRow
        } else {
            entity.dailySubscriptions
        }

        val cleanSources = quote.sourceName.ifBlank { "Live Exchange" }
        return entity.copy(
            qibSubX = roundTwoDecimals(nextQib),
            bNiiSubX = roundTwoDecimals(nextBNii),
            sNiiSubX = roundTwoDecimals(nextSNii),
            retailSubX = roundTwoDecimals(nextRetail),
            employeeSubX = roundTwoDecimals(nextEmp),
            totalSubX = roundTwoDecimals(nextTotal),
            dailySubscriptions = updatedDaily,
            updatedAgoLabel = "$cleanSources • $syncTime",
        )
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
    ): LiveSubscriptionQuote {
        val resolvedBNii = if (bNii > 0.0) bNii else niiTotal
        val resolvedSNii = if (sNii > 0.0) sNii else niiTotal
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
            bNiiSubX = roundTwoDecimals(resolvedBNii),
            sNiiSubX = roundTwoDecimals(resolvedSNii),
            niiTotalSubX = roundTwoDecimals(resolvedNiiTotal),
            retailSubX = roundTwoDecimals(retail),
            employeeSubX = roundTwoDecimals(employee),
            totalSubX = roundTwoDecimals(total),
            sourceName = sourceName,
            priorityRank = priorityRank,
            asOfLabel = asOfLabel,
            symbol = symbol,
        )
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

    private fun fetchText(url: String, referer: String? = null): String? {
        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept", "application/json, text/html, */*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
            if (!referer.isNullOrBlank()) {
                builder.header("Referer", referer)
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    private fun roundTwoDecimals(value: Double): Double =
        (value * 100.0).roundToInt() / 100.0

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
        val targetCompanyName: String? = null,
        val targetSymbol: String? = null,
    )

    companion object {
        const val DEFAULT_STALE_MS: Long = 2L * 60L * 1_000L // 2 minutes
        const val MARKET_POLL_INTERVAL_MS: Long = 75_000L // 75 seconds during IST bidding hours

        fun formatTimeLabel(nowMs: Long = System.currentTimeMillis()): String {
            return SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(nowMs))
        }

        /**
         * Returns true during Indian IPO bidding hours: Monday–Friday, 10:00 AM to 5:15 PM IST.
         */
        fun isMarketHoursIst(nowMs: Long = System.currentTimeMillis()): Boolean {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
                timeInMillis = nowMs
            }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
            val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return minutesOfDay in (10 * 60)..(17 * 60 + 15)
        }
    }
}
