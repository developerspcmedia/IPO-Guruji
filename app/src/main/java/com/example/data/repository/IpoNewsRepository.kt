package com.example.data.repository

import android.text.Html
import android.util.Xml
import com.example.data.local.IpoSeedData
import com.example.data.model.BoardType
import com.example.data.model.IpoEntity
import com.example.data.model.IpoNewsArticle
import com.example.data.model.IpoNewsCatalog
import com.example.data.model.NewsCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class NewsRefreshResult(
    val articles: List<IpoNewsArticle>,
    val liveSourceCount: Int,
    val activeSourceNames: List<String>,
    val syncedAtLabel: String,
)

class IpoNewsRepository(
    private val enableNetworkFetch: Boolean = true,
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val feedSources = listOf(
        FeedSource(
            name = "Google News India IPO",
            url = "https://news.google.com/rss/search?q=India+IPO+OR+GMP+OR+SME+IPO+SEBI&hl=en-IN&gl=IN&ceid=IN:en",
            requireIpoKeyword = false,
            isUsFeed = false,
        ),
        FeedSource(
            name = "Economic Times IPO",
            url = "https://economictimes.indiatimes.com/markets/ipos/fpos/rssfeeds/14655708.cms",
            requireIpoKeyword = false,
            isUsFeed = false,
        ),
        FeedSource(
            name = "LiveMint Markets",
            url = "https://www.livemint.com/rss/markets",
            requireIpoKeyword = true,
            isUsFeed = false,
        ),
        FeedSource(
            name = "Google News USA IPOs",
            url = "https://news.google.com/rss/search?q=(NASDAQ+OR+NYSE+OR+%22Wall+Street%22)+IPO+upcoming+OR+priced+OR+%22S-1%22&hl=en-US&gl=US&ceid=US:en",
            requireIpoKeyword = true,
            isUsFeed = true,
        ),
        FeedSource(
            name = "US Markets IPO Desk",
            url = "https://news.google.com/rss/search?q=US+IPO+filing+S-1+OR+F-1+site:reuters.com+OR+site:cnbc.com+OR+site:marketwatch.com+OR+site:nasdaq.com+OR+site:barrons.com&hl=en-US&gl=US&ceid=US:en",
            requireIpoKeyword = true,
            isUsFeed = true,
        ),
        FeedSource(
            name = "GlobeNewswire US IPOs",
            url = "https://www.globenewswire.com/RssFeed/subjectcode/20-Initial%20Public%20Offerings/feedTitle/GlobeNewswire%20-%20Initial%20Public%20Offerings",
            requireIpoKeyword = true,
            isUsFeed = true,
        ),
    )

    suspend fun fetchLatestNews(catalog: List<IpoEntity> = IpoSeedData.createInitialCatalog()): NewsRefreshResult =
        withContext(Dispatchers.IO) {
            val syncTime = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
            val curated = IpoNewsCatalog.getLatestNews()

            if (!enableNetworkFetch) {
                return@withContext NewsRefreshResult(
                    articles = curated,
                    liveSourceCount = 0,
                    activeSourceNames = listOf("ET IPO", "LiveMint", "Nasdaq", "SEC EDGAR"),
                    syncedAtLabel = "Synced $syncTime",
                )
            }

            val liveResults = withTimeoutOrNull(8_000L) {
                coroutineScope {
                    feedSources.map { feed ->
                        async {
                            runCatching {
                                fetchAndParseFeed(feed, catalog)
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
            }.orEmpty()

            val activeSources = mutableListOf<String>()
            val liveIndiaArticles = mutableListOf<IpoNewsArticle>()
            val liveUsaArticles = mutableListOf<IpoNewsArticle>()
            liveResults.forEachIndexed { idx, items ->
                if (items.isNotEmpty()) {
                    activeSources.add(feedSources[idx].name)
                    if (feedSources[idx].isUsFeed) {
                        liveUsaArticles.addAll(items)
                    } else {
                        liveIndiaArticles.addAll(items)
                    }
                }
            }

            // Interleave India and USA live IPO headlines so both markets appear fresh at the top
            val interleavedLive = mutableListOf<IpoNewsArticle>()
            val indiaPool = liveIndiaArticles.take(10)
            val usaPool = liveUsaArticles.take(10)
            val maxPairs = maxOf(indiaPool.size, usaPool.size)
            for (i in 0 until maxPairs) {
                if (i < indiaPool.size) interleavedLive.add(indiaPool[i])
                if (i < usaPool.size) interleavedLive.add(usaPool[i])
            }

            val seenTitles = HashSet<String>()
            val merged = buildList {
                interleavedLive.forEachIndexed { idx, item ->
                    val normalizedKey = item.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").take(48)
                    if (seenTitles.add(normalizedKey)) {
                        add(item.copy(isFeatured = idx == 0))
                    }
                }
                // Fill / append curated latest briefs so every IPO category (India & USA) has deep analysis
                curated.forEach { fallback ->
                    val normalizedKey = fallback.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").take(48)
                    if (seenTitles.add(normalizedKey)) {
                        add(
                            if (interleavedLive.isNotEmpty()) {
                                fallback.copy(isFeatured = false)
                            } else {
                                fallback
                            },
                        )
                    }
                }
            }

            val sourceNames = if (activeSources.isNotEmpty()) {
                activeSources
            } else {
                listOf("ET IPO", "LiveMint", "Nasdaq", "SEC EDGAR")
            }

            NewsRefreshResult(
                articles = merged,
                liveSourceCount = interleavedLive.size,
                activeSourceNames = sourceNames,
                syncedAtLabel = "Synced $syncTime",
            )
        }

    private fun fetchAndParseFeed(feed: FeedSource, catalog: List<IpoEntity>): List<IpoNewsArticle> {
        val request = Request.Builder()
            .url(feed.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) IPOGuruji/1.0")
            .header("Accept", "application/rss+xml, application/xml, text/xml")
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()

        return parseRssXml(
            xml = body,
            defaultSource = feed.name,
            requireIpoKeyword = feed.requireIpoKeyword,
            catalog = catalog,
            isUsFeed = feed.isUsFeed,
        )
    }

    internal fun parseRssXml(
        xml: String,
        defaultSource: String,
        requireIpoKeyword: Boolean,
        catalog: List<IpoEntity>,
        isUsFeed: Boolean = false,
    ): List<IpoNewsArticle> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        val items = mutableListOf<IpoNewsArticle>()
        var insideItem = false
        var rawTitle = ""
        var rawLink = ""
        var rawDescription = ""
        var rawPubDate = ""
        var rawSource = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && items.size < 8) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.US)) {
                        "item" -> {
                            insideItem = true
                            rawTitle = ""
                            rawLink = ""
                            rawDescription = ""
                            rawPubDate = ""
                            rawSource = ""
                        }
                        "title" -> if (insideItem) rawTitle = safeNextText(parser)
                        "link" -> if (insideItem) rawLink = safeNextText(parser)
                        "description" -> if (insideItem) rawDescription = safeNextText(parser)
                        "pubdate" -> if (insideItem) rawPubDate = safeNextText(parser)
                        "source" -> if (insideItem) rawSource = safeNextText(parser)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true) && insideItem) {
                        insideItem = false
                        val cleaned = buildArticle(
                            rawTitle = rawTitle,
                            rawLink = rawLink,
                            rawDescription = rawDescription,
                            rawPubDate = rawPubDate,
                            rawSource = rawSource.ifBlank { defaultSource },
                            requireIpoKeyword = requireIpoKeyword,
                            catalog = catalog,
                            index = items.size,
                            isUsFeed = isUsFeed,
                        )
                        if (cleaned != null) {
                            items.add(cleaned)
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun safeNextText(parser: XmlPullParser): String {
        return runCatching {
            if (parser.next() == XmlPullParser.TEXT) {
                val text = parser.text.orEmpty()
                parser.nextTag()
                text
            } else {
                ""
            }
        }.getOrDefault("")
    }

    private fun buildArticle(
        rawTitle: String,
        rawLink: String,
        rawDescription: String,
        rawPubDate: String,
        rawSource: String,
        requireIpoKeyword: Boolean,
        catalog: List<IpoEntity>,
        index: Int,
        isUsFeed: Boolean = false,
    ): IpoNewsArticle? {
        val decodedTitle = cleanText(rawTitle)
        if (decodedTitle.length < 12) return null

        // Google News titles often end with " - Source Name"
        val titleParts = decodedTitle.split(" - ")
        val finalTitle = if (titleParts.size >= 2 && titleParts.last().length <= 36) {
            titleParts.dropLast(1).joinToString(" - ").trim()
        } else {
            decodedTitle
        }
        val finalSource = if ((rawSource.startsWith("Google News") || rawSource == "US Markets IPO Desk") && titleParts.size >= 2) {
            titleParts.last().trim()
        } else {
            rawSource
        }

        val cleanedDesc = cleanText(rawDescription)
            .removePrefix(finalTitle)
            .trim()
            .ifBlank {
                if (isUsFeed) {
                    "Latest USA IPO & Wall Street primary market update reported by $finalSource."
                } else {
                    "Latest IPO & primary market update reported by $finalSource."
                }
            }

        val combinedLower = "${finalTitle.lowercase(Locale.US)} ${cleanedDesc.lowercase(Locale.US)}"
        val ipoKeywords = listOf(
            "ipo", "initial public offering", "gmp", "drhp", "rhp", "sebi",
            "allotment", "listing", "sme", "s-1", "f-1", "nasdaq", "nyse", "debut",
        )
        if (requireIpoKeyword && !ipoKeywords.any { combinedLower.contains(it) }) {
            return null
        }

        val hasIndiaSignals = listOf("sebi", "drhp", "bse", "nse", "crore", "₹", "rupee", "india", "mumbai", "dalal street")
            .any { combinedLower.contains(it) }
        val hasUsSignals = listOf("nasdaq", "nyse", "sec ", "form s-1", "s-1", "form f-1", "wall street", "u.s. ipo", "us ipo", "american ipo")
            .any { combinedLower.contains(it) }
        val isUsArticle = (isUsFeed && !hasIndiaSignals) || (hasUsSignals && !hasIndiaSignals)

        val matchedIpo = if (isUsArticle) {
            null
        } else {
            catalog.firstOrNull { ipo ->
                val shortLower = ipo.shortCode.lowercase(Locale.US)
                val firstWord = ipo.companyName.substringBefore(" ").lowercase(Locale.US)
                (shortLower.length >= 4 && combinedLower.contains(shortLower)) ||
                    (firstWord.length >= 4 && combinedLower.contains(firstWord))
            }
        }

        val category = when {
            isUsArticle -> NewsCategory.USA_IPOS
            combinedLower.contains("sme") -> NewsCategory.SME_BUZZ
            combinedLower.contains("sebi") || combinedLower.contains("drhp") || combinedLower.contains("rhp") ->
                NewsCategory.SEBI_FILINGS
            combinedLower.contains("gmp") || combinedLower.contains("listing") || combinedLower.contains("allotment") ||
                combinedLower.contains("subscription") -> NewsCategory.GMP_LISTING
            else -> NewsCategory.UPCOMING
        }

        return IpoNewsArticle(
            id = if (isUsArticle) "live_us_${finalTitle.hashCode()}_$index" else "live_${finalTitle.hashCode()}_$index",
            title = finalTitle,
            summary = cleanedDesc.take(200),
            fullBody = listOf(
                cleanedDesc,
                "Source: $finalSource • Tap 'Open Source Article' below to view full coverage and live market commentary.",
            ),
            category = category,
            source = finalSource,
            publishedAgo = formatRelativePubDate(rawPubDate),
            readTimeMinutes = 2,
            linkedIpoId = matchedIpo?.id,
            linkedIpoName = matchedIpo?.companyName ?: if (isUsArticle) "USA IPOs" else null,
            linkedShortCode = matchedIpo?.shortCode ?: if (isUsArticle) "USA" else "IPO",
            linkedBoardType = matchedIpo?.boardType ?: if (category == NewsCategory.SME_BUZZ) BoardType.SME else BoardType.MAINBOARD,
            logoUrl = matchedIpo?.logoUrl ?: if (isUsArticle) {
                "https://www.google.com/s2/favicons?domain=nasdaq.com&sz=128"
            } else {
                "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange_logo.svg"
            },
            imageUrl = matchedIpo?.bannerImageUrl ?: if (isUsArticle) {
                "https://commons.wikimedia.org/wiki/Special:FilePath/New_York_Stock_Exchange_1.jpg?width=900"
            } else {
                "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange.jpg?width=900"
            },
            articleUrl = rawLink.trim(),
        )
    }

    private fun cleanText(input: String): String {
        val stripped = Html.fromHtml(input, Html.FROM_HTML_MODE_COMPACT).toString()
        return stripped.replace(Regex("\\s+"), " ").trim()
    }

    private fun formatRelativePubDate(pubDate: String): String {
        if (pubDate.isBlank()) return "Just now"
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }.parse(pubDate.trim())
            }.getOrNull()
            if (parsed != null) {
                val diffMinutes = abs(System.currentTimeMillis() - parsed.time) / 60_000L
                return when {
                    diffMinutes < 2 -> "Just now"
                    diffMinutes < 60 -> "${diffMinutes}m ago"
                    diffMinutes < 1_440 -> "${diffMinutes / 60}h ago"
                    else -> "${diffMinutes / 1_440}d ago"
                }
            }
        }
        return "Today"
    }

    private data class FeedSource(
        val name: String,
        val url: String,
        val requireIpoKeyword: Boolean,
        val isUsFeed: Boolean = false,
    )
}
