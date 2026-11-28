package com.example.data.repository

import com.example.data.model.UsIpoEntity
import com.example.data.model.UsIpoFormatters
import com.example.data.model.UsIpoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class SecFilingMatch(
    val formType: String,
    val filingUrl: String,
    val filedDate: String,
    val filedEpochMs: Long,
)

data class UsIpoRemoteSnapshot(
    val nasdaqItems: List<UsIpoEntity>,
    val secItems: List<UsIpoEntity>,
    val activeSources: List<String>,
    val errorSummary: String? = null,
)

class UsIpoRemoteDataSource(
    private val enableNetworkFetch: Boolean = true,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    suspend fun fetchLiveSnapshot(nowMs: Long = System.currentTimeMillis()): UsIpoRemoteSnapshot =
        withContext(Dispatchers.IO) {
            if (!enableNetworkFetch) {
                return@withContext UsIpoRemoteSnapshot(
                    nasdaqItems = emptyList(),
                    secItems = emptyList(),
                    activeSources = emptyList(),
                )
            }

            val targetMonths = buildTargetYearMonths(nowMs)
            val sources = mutableListOf<String>()
            val errors = mutableListOf<String>()

            coroutineScope {
                val nasdaqDeferreds = targetMonths.map { ym ->
                    async { fetchNasdaqMonth(yearMonth = ym, syncedAtMs = nowMs) }
                }
                val secDeferred = async { fetchRecentSecFilings(nowMs = nowMs) }

                val nasdaqResults = nasdaqDeferreds.awaitAll()
                val nasdaqCombined = mutableListOf<UsIpoEntity>()
                nasdaqResults.forEach { result ->
                    result.onSuccess { list ->
                        if (list.isNotEmpty() && "Nasdaq IPO Calendar" !in sources) {
                            sources += "Nasdaq IPO Calendar"
                        }
                        nasdaqCombined += list
                    }.onFailure { err ->
                        errors += (err.message ?: "Nasdaq fetch error")
                    }
                }

                val secResult = secDeferred.await()
                val secItems = secResult.getOrElse { err ->
                    errors += (err.message ?: "SEC EDGAR fetch error")
                    emptyList()
                }
                if (secItems.isNotEmpty()) {
                    sources += "SEC EDGAR"
                }

                UsIpoRemoteSnapshot(
                    nasdaqItems = nasdaqCombined,
                    secItems = secItems,
                    activeSources = sources,
                    errorSummary = if (nasdaqCombined.isEmpty() && secItems.isEmpty()) {
                        errors.firstOrNull() ?: "Unable to reach Nasdaq or SEC EDGAR right now."
                    } else {
                        null
                    },
                )
            }
        }

    suspend fun lookupDirectSecFiling(
        companyName: String,
        symbol: String,
    ): SecFilingMatch? = withContext(Dispatchers.IO) {
        if (!enableNetworkFetch) return@withContext null
        val cleanedQuery = normalizeSearchCompanyQuery(companyName).ifBlank { symbol.trim() }
        if (cleanedQuery.isBlank()) return@withContext null

        val encodedQuery = URLEncoder.encode("\"$cleanedQuery\"", StandardCharsets.UTF_8.name())
        val url = "https://efts.sec.gov/LATEST/search-index?q=$encodedQuery&forms=S-1,F-1,S-1/A,F-1/A"
        val rawJson = executeGet(url = url, headers = secHeaders()).getOrNull() ?: return@withContext null
        return@withContext parseFirstSecFilingMatch(rawJson)
    }

    private fun fetchNasdaqMonth(yearMonth: String, syncedAtMs: Long): Result<List<UsIpoEntity>> {
        val url = "https://api.nasdaq.com/api/ipo/calendar?date=$yearMonth"
        return executeGet(url = url, headers = nasdaqHeaders()).map { body ->
            parseNasdaqCalendarJson(rawJson = body, syncedAtMs = syncedAtMs)
        }
    }

    private fun fetchRecentSecFilings(nowMs: Long): Result<List<UsIpoEntity>> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val endDate = dateFormat.format(java.util.Date(nowMs))
        val startCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -45)
        }
        val startDate = dateFormat.format(startCal.time)
        val url = "https://efts.sec.gov/LATEST/search-index?forms=S-1,F-1&dateRange=custom&startdt=$startDate&enddt=$endDate"
        return executeGet(url = url, headers = secHeaders()).map { body ->
            parseSecEftsJson(rawJson = body, syncedAtMs = nowMs)
        }
    }

    private fun executeGet(url: String, headers: Map<String, String>): Result<String> = runCatching {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} from ${response.request.url.host}")
            }
            response.body?.string().orEmpty()
        }
    }

    internal fun parseNasdaqCalendarJson(
        rawJson: String,
        syncedAtMs: Long,
    ): List<UsIpoEntity> {
        if (rawJson.isBlank()) return emptyList()
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val dataObj = root.optJSONObject("data") ?: return emptyList()

        val result = mutableListOf<UsIpoEntity>()

        // 1. Upcoming IPOs (Nasdaq nests upcomingTable.rows or direct rows)
        val upcomingObj = dataObj.optJSONObject("upcoming")
        val upcomingRows = upcomingObj?.optJSONObject("upcomingTable")?.optJSONArray("rows")
            ?: upcomingObj?.optJSONArray("rows")
        result += parseNasdaqRows(
            rows = upcomingRows,
            status = UsIpoStatus.UPCOMING,
            syncedAtMs = syncedAtMs,
        )

        // 2. Priced IPOs
        val pricedObj = dataObj.optJSONObject("priced")
        val pricedRows = pricedObj?.optJSONArray("rows")
            ?: pricedObj?.optJSONObject("pricedTable")?.optJSONArray("rows")
        result += parseNasdaqRows(
            rows = pricedRows,
            status = UsIpoStatus.PRICED,
            syncedAtMs = syncedAtMs,
        )

        // 3. Filed IPOs
        val filedObj = dataObj.optJSONObject("filed")
        val filedRows = filedObj?.optJSONArray("rows")
            ?: filedObj?.optJSONObject("filedTable")?.optJSONArray("rows")
        result += parseNasdaqRows(
            rows = filedRows,
            status = UsIpoStatus.FILED,
            syncedAtMs = syncedAtMs,
        )

        return result
    }

    private fun parseNasdaqRows(
        rows: JSONArray?,
        status: UsIpoStatus,
        syncedAtMs: Long,
    ): List<UsIpoEntity> {
        if (rows == null || rows.length() == 0) return emptyList()
        val items = mutableListOf<UsIpoEntity>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val companyName = row.optStringClean("companyName")
            if (companyName.isBlank()) continue

            val symbol = row.optStringClean("proposedTickerSymbol")
                .ifBlank { row.optStringClean("symbol") }
                .uppercase(Locale.US)
            val rawDealId = row.optStringClean("dealID")
            val dealId = if (rawDealId.isNotBlank()) {
                "nasdaq_$rawDealId"
            } else {
                val slug = companyName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                "nasdaq_${status.name.lowercase(Locale.US)}_${symbol.ifBlank { slug }}"
            }

            val rawExchange = row.optStringClean("proposedExchange")
                .ifBlank { row.optStringClean("exchange") }
            val rawPrice = row.optStringClean("proposedSharePrice")
                .ifBlank { row.optStringClean("price") }
            val shares = row.optStringClean("sharesOffered")
                .ifBlank { row.optStringClean("shares") }
            val rawDollarValue = row.optStringClean("dollarValueOfSharesOffered")
                .ifBlank { row.optStringClean("offerAmount") }
            val rawDate = when (status) {
                UsIpoStatus.UPCOMING -> row.optStringClean("expectedPriceDate")
                    .ifBlank { row.optStringClean("pricedDate") }
                    .ifBlank { row.optStringClean("dealDate") }
                UsIpoStatus.PRICED -> row.optStringClean("pricedDate")
                    .ifBlank { row.optStringClean("dealDate") }
                UsIpoStatus.FILED -> row.optStringClean("filedDate")
                    .ifBlank { row.optStringClean("dealDate") }
                    .ifBlank { row.optStringClean("pricedDate") }
            }

            val epochMs = UsIpoFormatters.parseEventDateEpochMs(rawDate)
            val displayDate = UsIpoFormatters.formatDisplayDate(rawDate, epochMs)
            val numericDealSize = UsIpoFormatters.parseUsdAmount(rawDollarValue)
            val formattedDealSize = rawDollarValue.ifBlank {
                UsIpoFormatters.formatCompactUsd(numericDealSize)
            }

            items += UsIpoEntity(
                dealId = dealId,
                symbol = symbol,
                companyName = companyName,
                exchange = UsIpoFormatters.normalizeExchange(rawExchange),
                status = status,
                priceRange = UsIpoFormatters.normalizePriceRange(rawPrice),
                sharesOffered = shares.ifBlank { "TBD" },
                dealSizeUsd = formattedDealSize,
                dealSizeNumericUsd = numericDealSize,
                eventDate = displayDate,
                eventDateEpochMs = epochMs,
                secFormType = if (status == UsIpoStatus.FILED) "S-1 / F-1" else "",
                secFilingUrl = UsIpoFormatters.buildSecEdgarSearchUrl(companyName, symbol),
                nasdaqUrl = UsIpoFormatters.buildNasdaqIpoUrl(symbol),
                sourceLabel = "Nasdaq",
                syncedAtMs = syncedAtMs,
            )
        }
        return items
    }

    internal fun parseSecEftsJson(
        rawJson: String,
        syncedAtMs: Long,
        maxItems: Int = 35,
    ): List<UsIpoEntity> {
        if (rawJson.isBlank()) return emptyList()
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val hits = root.optJSONObject("hits")?.optJSONArray("hits") ?: return emptyList()

        val items = mutableListOf<UsIpoEntity>()
        val seenCompanies = mutableSetOf<String>()

        for (i in 0 until hits.length()) {
            if (items.size >= maxItems) break
            val hit = hits.optJSONObject(i) ?: continue
            val source = hit.optJSONObject("_source") ?: continue
            val rawDisplayName = source.optJSONArray("display_names")?.optString(0).orEmpty().trim()
            if (rawDisplayName.isBlank()) continue

            val (companyName, extractedSymbol) = parseSecDisplayName(rawDisplayName)
            if (companyName.isBlank()) continue
            val dedupeKey = normalizeCompanyKey(companyName)
            if (!seenCompanies.add(dedupeKey)) continue

            val formType = source.optStringClean("form").ifBlank { "S-1" }
            val fileDateRaw = source.optStringClean("file_date")
            val accessionNo = source.optStringClean("adsh")
            val cik = source.optJSONArray("ciks")?.optString(0).orEmpty().trim()
            val hitId = hit.optStringClean("_id")
            val primaryDoc = hitId.substringAfter(":", "").takeIf { it.endsWith(".htm", ignoreCase = true) || it.endsWith(".html", ignoreCase = true) }

            val directUrl = UsIpoFormatters.buildSecDirectFilingUrl(cik, accessionNo, primaryDoc)
                .ifBlank { UsIpoFormatters.buildSecEdgarSearchUrl(companyName, extractedSymbol) }
            val epochMs = UsIpoFormatters.parseEventDateEpochMs(fileDateRaw)
            val displayDate = UsIpoFormatters.formatDisplayDate(fileDateRaw, epochMs)

            items += UsIpoEntity(
                dealId = "sec_${accessionNo.ifBlank { dedupeKey }}",
                symbol = extractedSymbol,
                companyName = companyName,
                exchange = "US Market",
                status = UsIpoStatus.FILED,
                priceRange = "TBD",
                sharesOffered = "See $formType",
                dealSizeUsd = "See $formType",
                dealSizeNumericUsd = 0.0,
                eventDate = displayDate,
                eventDateEpochMs = epochMs,
                secFormType = formType,
                secFilingUrl = directUrl,
                nasdaqUrl = UsIpoFormatters.buildNasdaqIpoUrl(extractedSymbol),
                sourceLabel = "SEC EDGAR",
                syncedAtMs = syncedAtMs,
            )
        }
        return items
    }

    internal fun parseFirstSecFilingMatch(rawJson: String): SecFilingMatch? {
        if (rawJson.isBlank()) return null
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val hits = root.optJSONObject("hits")?.optJSONArray("hits") ?: return null
        for (i in 0 until hits.length()) {
            val hit = hits.optJSONObject(i) ?: continue
            val source = hit.optJSONObject("_source") ?: continue
            val accessionNo = source.optStringClean("adsh")
            val cik = source.optJSONArray("ciks")?.optString(0).orEmpty().trim()
            val formType = source.optStringClean("form").ifBlank { "S-1" }
            val fileDateRaw = source.optStringClean("file_date")
            val hitId = hit.optStringClean("_id")
            val primaryDoc = hitId.substringAfter(":", "").takeIf {
                it.endsWith(".htm", ignoreCase = true) || it.endsWith(".html", ignoreCase = true)
            }
            val directUrl = UsIpoFormatters.buildSecDirectFilingUrl(cik, accessionNo, primaryDoc)
            if (directUrl.isNotBlank()) {
                val epochMs = UsIpoFormatters.parseEventDateEpochMs(fileDateRaw)
                return SecFilingMatch(
                    formType = formType,
                    filingUrl = directUrl,
                    filedDate = UsIpoFormatters.formatDisplayDate(fileDateRaw, epochMs),
                    filedEpochMs = epochMs,
                )
            }
        }
        return null
    }

    private fun buildTargetYearMonths(nowMs: Long): List<String> {
        val sdf = SimpleDateFormat("yyyy-MM", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMs
        }
        val currentMonth = sdf.format(cal.time)
        val nextCal = (cal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val prevCal = (cal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        return listOf(currentMonth, sdf.format(nextCal.time), sdf.format(prevCal.time)).distinct()
    }

    private fun parseSecDisplayName(raw: String): Pair<String, String> {
        // Format from SEC EFTS: "Acme Robotics Inc. (ACME) (CIK 0001999999)" or "Acme Robotics Inc. (CIK 0001999999)"
        val withoutCik = raw.replace(Regex("\\s*\\(CIK\\s*\\d+\\)", RegexOption.IGNORE_CASE), "").trim()
        val tickerMatch = Regex("\\(([A-Z0-9.\\-]{1,6})\\)\\s*$").find(withoutCik)
        val ticker = tickerMatch?.groupValues?.getOrNull(1).orEmpty().trim()
        val name = if (tickerMatch != null) {
            withoutCik.removeRange(tickerMatch.range).trim()
        } else {
            withoutCik
        }
        return name to ticker
    }

    private fun normalizeSearchCompanyQuery(companyName: String): String {
        return companyName
            .replace(Regex("\\s+(Inc\\.?|Corp\\.?|Corporation|Ltd\\.?|Limited|PLC|LLC|LP|Holdings|Group)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun JSONObject.optStringClean(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return optString(key, "").trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
    }

    private fun nasdaqHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://www.nasdaq.com",
        "Referer" to "https://www.nasdaq.com/market-activity/ipos",
    )

    private fun secHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "IPOGurujiAndroid/1.0 (developers.pcmedia@gmail.com)",
        "Accept" to "application/json",
    )

    companion object {
        internal fun normalizeCompanyKey(name: String): String {
            return name.lowercase(Locale.US)
                .replace(Regex("\\b(inc|corp|corporation|ltd|limited|plc|llc|lp|holdings|holding|group|co|company)\\b"), "")
                .replace(Regex("[^a-z0-9]"), "")
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
