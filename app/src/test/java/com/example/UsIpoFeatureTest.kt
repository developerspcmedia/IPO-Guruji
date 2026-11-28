package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.example.ads.AdMobManager
import com.example.ads.BannerAdPlacement
import com.example.ads.NativeAdPlacement
import com.example.data.preferences.ThemePreference
import com.example.data.local.IpoSeedData
import com.example.data.local.UsIpoDao
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoNewsArticle
import com.example.data.model.IpoStatus
import com.example.data.model.IpoTimelineBucket
import com.example.data.model.NewsCategory
import com.example.data.model.UsIpoEntity
import com.example.data.model.UsIpoFormatters
import com.example.data.model.UsIpoStatus
import com.example.data.repository.IpoNewsRepository
import com.example.data.repository.UsIpoRemoteDataSource
import com.example.data.repository.UsIpoRepository
import com.example.notifications.IpoNotificationManager
import com.example.ui.components.ExitAppDialog
import com.example.ui.components.IpoCard
import com.example.ui.screens.IpoBoardScreen
import com.example.ui.screens.IpoDetailScreen
import com.example.ui.screens.UsIpoBoardScreen
import com.example.ui.screens.UsIpoDetailScreen
import com.example.ui.theme.IpoPulseTheme
import com.example.ui.viewmodel.BoardTimelineTab
import com.example.ui.viewmodel.DetailTab
import com.example.ui.viewmodel.IpoBoardUiState
import com.example.ui.viewmodel.UsIpoUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsIpoFeatureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `nasdaq calendar parser extracts upcoming priced and filed US IPOs accurately`() {
        val sampleJson = """
            {
              "data": {
                "upcoming": {
                  "upcomingTable": {
                    "rows": [
                      {
                        "dealID": "1001-2026",
                        "proposedTickerSymbol": "KLAR",
                        "companyName": "Klarna Group plc",
                        "proposedExchange": "New York Stock Exchange",
                        "proposedSharePrice": "35.00-37.00",
                        "sharesOffered": "34,300,000",
                        "expectedPriceDate": "06/18/2026",
                        "dollarValueOfSharesOffered": "$1,269,100,000"
                      }
                    ]
                  }
                },
                "priced": {
                  "rows": [
                    {
                      "dealID": "1002-2026",
                      "proposedTickerSymbol": "CRCL",
                      "companyName": "Circle Internet Group, Inc.",
                      "proposedExchange": "NYSE",
                      "proposedSharePrice": "31.00",
                      "sharesOffered": "34,000,000",
                      "pricedDate": "06/05/2026",
                      "dollarValueOfSharesOffered": "$1,054,000,000"
                    }
                  ]
                },
                "filed": {
                  "rows": [
                    {
                      "dealID": "1003-2026",
                      "proposedTickerSymbol": "CHYM",
                      "companyName": "Chime Financial, Inc.",
                      "proposedExchange": "NASDAQ Global Select",
                      "proposedSharePrice": "24.00-26.00",
                      "sharesOffered": "32,000,000",
                      "filedDate": "05/14/2026",
                      "dollarValueOfSharesOffered": "$832,000,000"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val remote = UsIpoRemoteDataSource(enableNetworkFetch = false)
        val parsed = remote.parseNasdaqCalendarJson(sampleJson, syncedAtMs = 1_700_000_000_000L)

        assertEquals(3, parsed.size)

        val upcoming = parsed.first { it.status == UsIpoStatus.UPCOMING }
        assertEquals("KLAR", upcoming.symbol)
        assertEquals("NYSE", upcoming.exchange)
        assertEquals("$35.00-37.00", upcoming.priceRange)
        assertEquals("$1.27B", upcoming.compactDealSize)
        assertEquals("18 Jun 2026", upcoming.eventDate)

        val priced = parsed.first { it.status == UsIpoStatus.PRICED }
        assertEquals("CRCL", priced.symbol)
        assertEquals("$1.05B", priced.compactDealSize)

        val filed = parsed.first { it.status == UsIpoStatus.FILED }
        assertEquals("NASDAQ", filed.exchange)
        assertEquals("$832M", filed.compactDealSize)
    }

    @Test
    fun `sec edgar efts parser builds direct archive url and merges with nasdaq records`() {
        val secJson = """
            {
              "hits": {
                "hits": [
                  {
                    "_id": "0001193125-26-104422:d123456ds1.htm",
                    "_source": {
                      "display_names": ["Chime Financial, Inc. (CHYM) (CIK 0001795586)"],
                      "form": "S-1",
                      "file_date": "2026-05-14",
                      "adsh": "0001193125-26-104422",
                      "ciks": ["0001795586"]
                    }
                  },
                  {
                    "_id": "0001193125-26-109900:f1doc.htm",
                    "_source": {
                      "display_names": ["Nordic Cloud Corp (CIK 0002011223)"],
                      "form": "F-1",
                      "file_date": "2026-05-20",
                      "adsh": "0001193125-26-109900",
                      "ciks": ["0002011223"]
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val remote = UsIpoRemoteDataSource(enableNetworkFetch = false)
        val secItems = remote.parseSecEftsJson(secJson, syncedAtMs = 1_700_000_000_000L)

        assertEquals(2, secItems.size)
        assertEquals("Chime Financial, Inc.", secItems[0].companyName)
        assertEquals("CHYM", secItems[0].symbol)
        assertTrue(secItems[0].hasDirectSecArchiveUrl)
        assertEquals(
            "https://www.sec.gov/Archives/edgar/data/1795586/000119312526104422/d123456ds1.htm",
            secItems[0].secFilingUrl,
        )

        val nasdaqChime = UsIpoEntity(
            dealId = "nasdaq_1003",
            symbol = "CHYM",
            companyName = "Chime Financial, Inc.",
            exchange = "NASDAQ",
            status = UsIpoStatus.UPCOMING,
            priceRange = "$24.00 - $26.00",
            sharesOffered = "32,000,000",
            dealSizeUsd = "$832,000,000",
            dealSizeNumericUsd = 832_000_000.0,
            eventDate = "12 Jun 2026",
            eventDateEpochMs = UsIpoFormatters.parseEventDateEpochMs("2026-06-12"),
            secFormType = "",
            secFilingUrl = UsIpoFormatters.buildSecEdgarSearchUrl("Chime Financial, Inc.", "CHYM"),
            nasdaqUrl = UsIpoFormatters.buildNasdaqIpoUrl("CHYM"),
            sourceLabel = "Nasdaq",
            syncedAtMs = 1_700_000_000_000L,
        )

        val repo = UsIpoRepository(InMemoryUsIpoDao(), remote)
        val merged = repo.mergeNasdaqAndSecItems(
            nasdaqItems = listOf(nasdaqChime),
            secItems = secItems,
        )

        assertEquals(2, merged.size)
        val enrichedChime = merged.first { it.symbol == "CHYM" }
        assertEquals(UsIpoStatus.UPCOMING, enrichedChime.status)
        assertEquals("S-1", enrichedChime.secFormType)
        assertTrue(enrichedChime.hasDirectSecArchiveUrl)
        assertEquals("Nasdaq • SEC EDGAR", enrichedChime.sourceLabel)
    }

    @Test
    fun `us ipo board screen renders cards switches status tabs and handles click`() {
        val sampleUpcoming = UsIpoEntity(
            dealId = "nasdaq_klar",
            symbol = "KLAR",
            companyName = "Klarna Group plc",
            exchange = "NYSE",
            status = UsIpoStatus.UPCOMING,
            priceRange = "$35.00 - $37.00",
            sharesOffered = "34,300,000",
            dealSizeUsd = "$1,269,100,000",
            dealSizeNumericUsd = 1_269_100_000.0,
            eventDate = "18 Jun 2026",
            eventDateEpochMs = 1_781_740_800_000L,
            secFormType = "F-1",
            secFilingUrl = "https://www.sec.gov/Archives/edgar/data/123/456/doc.htm",
            nasdaqUrl = "https://www.nasdaq.com/market-activity/stocks/klar",
            sourceLabel = "Nasdaq • SEC EDGAR",
            syncedAtMs = 1_700_000_000_000L,
        )

        var selectedStatus = UsIpoStatus.UPCOMING
        var clickedDealId: String? = null

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = false) {
                UsIpoBoardScreen(
                    uiState = UsIpoUiState(
                        allUsIpos = listOf(sampleUpcoming),
                        filteredUsIpos = listOf(sampleUpcoming),
                        selectedStatus = UsIpoStatus.UPCOMING,
                        upcomingCount = 1,
                        pricedCount = 4,
                        filedCount = 7,
                        lastSyncedLabel = "10:15 AM",
                        hasLoadedOnce = true,
                    ),
                    onEnsureLoaded = {},
                    onSelectStatus = { selectedStatus = it },
                    onSearchQueryChange = {},
                    onToggleSearch = {},
                    onRefresh = {},
                    onOpenMenu = {},
                    onUsIpoClick = { clickedDealId = it },
                )
            }
        }

        composeTestRule.onNodeWithText("USA IPOs").assertIsDisplayed()
        composeTestRule.onNodeWithText("Klarna Group plc").assertIsDisplayed()
        composeTestRule.onNodeWithText("$1.27B").assertIsDisplayed()

        composeTestRule.onNodeWithTag("us_status_tab_priced").performClick()
        assertEquals(UsIpoStatus.PRICED, selectedStatus)

        composeTestRule.onNodeWithTag("us_ipo_card_nasdaq_klar").performClick()
        assertEquals("nasdaq_klar", clickedDealId)
    }

    @Test
    fun `us ipo detail screen displays deal size shares and SEC S-1 filing action`() {
        val sampleDeal = UsIpoEntity(
            dealId = "nasdaq_chym",
            symbol = "CHYM",
            companyName = "Chime Financial, Inc.",
            exchange = "NASDAQ",
            status = UsIpoStatus.FILED,
            priceRange = "$24.00 - $26.00",
            sharesOffered = "32,000,000",
            dealSizeUsd = "$832,000,000",
            dealSizeNumericUsd = 832_000_000.0,
            eventDate = "14 May 2026",
            eventDateEpochMs = 1_778_716_800_000L,
            secFormType = "S-1",
            secFilingUrl = "https://www.sec.gov/Archives/edgar/data/1795586/000119312526104422/d123456ds1.htm",
            nasdaqUrl = "https://www.nasdaq.com/market-activity/stocks/chym",
            sourceLabel = "Nasdaq • SEC EDGAR",
            syncedAtMs = 1_700_000_000_000L,
        )

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                UsIpoDetailScreen(
                    ipo = sampleDeal,
                    isLookingUpSecFiling = false,
                    onEnsureSecFilingResolved = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("us_detail_deal_size").assertIsDisplayed()
        composeTestRule.onNodeWithText("$832M").assertIsDisplayed()
        composeTestRule.onNodeWithText("32,000,000").assertIsDisplayed()
        composeTestRule.onNodeWithTag("us_detail_list")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("us_detail_sec_s1_button"))
        composeTestRule.onNodeWithTag("us_detail_sec_s1_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open SEC S-1 Filing").assertIsDisplayed()
    }

    @Test
    fun `us ipo rss parser classifies multi-source US IPO headlines into USA_IPOS category`() {
        val sampleUsRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>US Markets IPO Desk</title>
                <item>
                  <title>Stripe rival Klarna targets NYSE IPO valuation above $15B - Reuters</title>
                  <link>https://www.reuters.com/markets/us/klarna-ipo-2026</link>
                  <description>Fintech lender files updated Form F-1 with SEC ahead of New York Stock Exchange listing.</description>
                  <pubDate>Thu, 14 May 2026 14:30:00 GMT</pubDate>
                  <source>Reuters</source>
                </item>
                <item>
                  <title>AI chip startup prices upsized Nasdaq IPO above range - CNBC</title>
                  <link>https://www.cnbc.com/2026/05/14/ai-chip-nasdaq-ipo.html</link>
                  <description>Strong Wall Street institutional bookbuilding lifts Nasdaq debut pricing.</description>
                  <pubDate>Thu, 14 May 2026 13:10:00 GMT</pubDate>
                  <source>CNBC</source>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val repo = IpoNewsRepository(enableNetworkFetch = false)
        val parsed = repo.parseRssXml(
            xml = sampleUsRss,
            defaultSource = "US Markets IPO Desk",
            requireIpoKeyword = true,
            catalog = emptyList(),
            isUsFeed = true,
        )

        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.category == NewsCategory.USA_IPOS })
        assertEquals("Reuters", parsed[0].source)
        assertEquals("CNBC", parsed[1].source)
        assertTrue(parsed[0].title.contains("Klarna"))
    }

    @Test
    fun `news notification evaluator triggers USA IPO alerts only when USA_IPOS category is active`() {
        val usArticle = IpoNewsArticle(
            id = "us-live-1",
            title = "CoreWeave rival files SEC S-1 for $1.2B Nasdaq IPO",
            summary = "Wall Street syndicate launches roadshow for upcoming Nasdaq debut.",
            fullBody = listOf("Full filing details available on SEC EDGAR."),
            category = NewsCategory.USA_IPOS,
            source = "MarketWatch",
            publishedAgo = "15m ago",
            readTimeMinutes = 2,
        )
        val indiaArticle = IpoNewsArticle(
            id = "in-live-1",
            title = "Mainboard IPO subscription crosses 18x on Day 3",
            summary = "QIB and NII demand accelerates ahead of closing.",
            fullBody = listOf("BSE and NSE exchange data updated."),
            category = NewsCategory.UPCOMING,
            source = "ET IPO",
            publishedAgo = "20m ago",
            readTimeMinutes = 2,
        )

        val enabledResult = IpoNotificationManager.evaluateNewsNotificationEvents(
            articles = listOf(usArticle, indiaArticle),
            knownNewsIds = setOf("older-article-id"),
            unreadNewsIds = emptySet(),
            hasNewsBaseline = true,
            notifyNewsAlertsEnabled = true,
            enabledCategories = setOf(NewsCategory.USA_IPOS),
        )
        assertEquals(listOf("us-live-1"), enabledResult.newArticlesToAlert.map { it.id })
        assertTrue("us-live-1" in enabledResult.updatedUnreadNewsIds)

        val disabledResult = IpoNotificationManager.evaluateNewsNotificationEvents(
            articles = listOf(usArticle),
            knownNewsIds = setOf("older-article-id"),
            unreadNewsIds = emptySet(),
            hasNewsBaseline = true,
            notifyNewsAlertsEnabled = true,
            enabledCategories = setOf(NewsCategory.GMP_LISTING),
        )
        assertTrue(disabledResult.newArticlesToAlert.isEmpty())
    }

    @Test
    fun `us ipo board screen alert bell opens USA IPO News Alerts dialog and triggers callbacks`() {
        var alertsEnabled = true
        var testAlertSent = false

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = false) {
                UsIpoBoardScreen(
                    uiState = UsIpoUiState(
                        hasLoadedOnce = true,
                        lastSyncedLabel = "10:20 AM",
                    ),
                    onEnsureLoaded = {},
                    onSelectStatus = {},
                    onSearchQueryChange = {},
                    onToggleSearch = {},
                    onRefresh = {},
                    onOpenMenu = {},
                    onUsIpoClick = {},
                    usaNewsAlertsEnabled = alertsEnabled,
                    onToggleUsaNewsAlerts = { alertsEnabled = it },
                    onSendTestUsaNewsAlert = { testAlertSent = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("us_board_news_alert_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("USA IPO News Alerts").assertIsDisplayed()
        composeTestRule.onNodeWithTag("us_board_news_alert_switch").performClick()
        assertFalse(alertsEnabled)
        composeTestRule.onNodeWithTag("us_board_news_alert_test_button").performClick()
        assertTrue(testAlertSent)
    }

    @Test
    fun `secondary admob native and banner unit ids resolve accurately for release and debug`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "ca-app-pub-3318993523524673/7479450947",
            context.getString(R.string.admob_banner_unit_id_2),
        )
        assertEquals(
            "ca-app-pub-3318993523524673/2610267649",
            context.getString(R.string.admob_native_unit_id_2),
        )
        assertEquals(
            "ca-app-pub-3318993523524673/7479450947",
            AdMobManager.resolveBannerUnitId(
                context = context,
                isDebug = false,
                placement = BannerAdPlacement.SECONDARY,
            ),
        )
        assertEquals(
            "ca-app-pub-3318993523524673/2610267649",
            AdMobManager.resolveNativeUnitId(
                context = context,
                isDebug = false,
                placement = NativeAdPlacement.SECONDARY,
            ),
        )
        assertEquals(
            "ca-app-pub-3318993523524673/9107752028",
            AdMobManager.resolveBannerUnitId(
                context = context,
                isDebug = false,
                placement = BannerAdPlacement.PRIMARY,
            ),
        )
        assertEquals(
            "ca-app-pub-3318993523524673/6776318332",
            AdMobManager.resolveNativeUnitId(
                context = context,
                isDebug = false,
                placement = NativeAdPlacement.PRIMARY,
            ),
        )
        assertEquals(
            AdMobManager.TEST_BANNER_UNIT_ID,
            AdMobManager.resolveBannerUnitId(
                context = context,
                isDebug = true,
                placement = BannerAdPlacement.SECONDARY,
            ),
        )
        assertEquals(
            AdMobManager.TEST_NATIVE_UNIT_ID,
            AdMobManager.resolveNativeUnitId(
                context = context,
                isDebug = true,
                placement = NativeAdPlacement.SECONDARY,
            ),
        )

        var stayClicked = false
        var exitClicked = false
        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = false) {
                ExitAppDialog(
                    onDismiss = { stayClicked = true },
                    onConfirmExit = { exitClicked = true },
                )
            }
        }
        composeTestRule.onNodeWithTag("exit_app_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("exit_dialog_stay_button").assertIsDisplayed().performClick()
        assertTrue(stayClicked)
        composeTestRule.onNodeWithTag("exit_dialog_confirm_button").assertIsDisplayed().performClick()
        assertTrue(exitClicked)
    }

    @Test
    fun `allotment phase IPOs stay in current and upcoming list until listing date arrives`() {
        val refMs = IpoDateUtils.parseFlexibleDateMs("23 Nov 2026")!!
        val catalog = IpoSeedData.createInitialCatalog()

        // Closed bidding (19-21 Nov) but listing date is 24-26 Nov -> still in Allotment Phase (NOT listed)
        val crystal = catalog.first { it.id == "crystal-crop-protection" }
        val mahanadi = catalog.first { it.id == "mahanadi-coalfields" }
        val antala = catalog.first { it.id == "antala-industries-sme" }
        // Listing date arrived (18 Nov) -> Listed
        val ntpc = catalog.first { it.id == "ntpc-green-energy" }

        assertFalse(IpoDateUtils.isListingDateArrived(crystal.listingDate, crystal.closeDate, crystal.status, refMs))
        assertTrue(IpoDateUtils.isInAllotmentPhase(crystal.closeDate, crystal.listingDate, crystal.status, refMs))

        assertFalse(IpoDateUtils.isListingDateArrived(mahanadi.listingDate, mahanadi.closeDate, mahanadi.status, refMs))
        assertTrue(IpoDateUtils.isInAllotmentPhase(mahanadi.closeDate, mahanadi.listingDate, mahanadi.status, refMs))

        assertFalse(IpoDateUtils.isListingDateArrived(antala.listingDate, antala.closeDate, antala.status, refMs))
        assertTrue(IpoDateUtils.isInAllotmentPhase(antala.closeDate, antala.listingDate, antala.status, refMs))

        // Live-synced IPO marked CLOSED with future listing date must ALSO stay in Current & Upcoming
        assertFalse(IpoDateUtils.isListingDateArrived("27 Nov 2026", "21 Nov 2026", IpoStatus.CLOSED, refMs))
        assertTrue(IpoDateUtils.isInAllotmentPhase("21 Nov 2026", "27 Nov 2026", IpoStatus.CLOSED, refMs))

        // Already listed IPO moves to Listed
        assertTrue(IpoDateUtils.isListingDateArrived(ntpc.listingDate, ntpc.closeDate, ntpc.status, refMs))
        assertFalse(IpoDateUtils.isInAllotmentPhase(ntpc.closeDate, ntpc.listingDate, ntpc.status, refMs))
    }

    @Test
    fun `ipo card shows expected listing price and detail screen shows check allotment fab`() {
        val crystal = IpoSeedData.createInitialCatalog().first { it.id == "crystal-crop-protection" }
            .copy(openDate = "20 Nov 2026", closeDate = "22 Nov 2026", listingDate = "29 Nov 2026")
        var trackedId: String? = null

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = false) {
                IpoCard(
                    ipo = crystal,
                    onClick = {},
                    onShareClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Expected Listing Price", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("expected_listing_price_${crystal.id}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `ipo detail screen check allotment floating button opens quick sheet and triggers tracker`() {
        val crystal = IpoSeedData.createInitialCatalog().first { it.id == "crystal-crop-protection" }
        var trackedIpoId: String? = null

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = false) {
                IpoDetailScreen(
                    ipo = crystal,
                    selectedTab = DetailTab.OVERVIEW,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                    onTrackAllotment = { trackedIpoId = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_check_allotment_fab").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_check_allotment_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_check_allotment_registrar_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_check_allotment_tracker_btn").assertIsDisplayed().performClick()
        assertEquals(crystal.id, trackedIpoId)
    }

    @Test
    fun `three tab timeline algorithm classifies current open allotment upcoming DRHP and listed IPOs accurately`() {
        val refMs = IpoDateUtils.parseFlexibleDateMs("24 Nov 2026")!!
        val catalog = IpoSeedData.createInitialCatalog()

        // 1. Currently open for bidding -> CURRENT
        val phonePe = catalog.first { it.id == "phonepe-india" } // open 24 Nov, close 26 Nov, list 01 Dec
        val vedant = catalog.first { it.id == "vedant-poly-fabs-sme" } // open 21 Nov, close 25 Nov, list 28 Nov
        assertEquals(
            IpoTimelineBucket.CURRENT,
            IpoDateUtils.classifyTimelineTab(phonePe.openDate, phonePe.closeDate, phonePe.listingDate, phonePe.status, refMs),
        )
        assertTrue(IpoDateUtils.isBiddingOpenToday(phonePe.openDate, phonePe.closeDate, phonePe.listingDate, phonePe.status, refMs))
        assertEquals(
            IpoTimelineBucket.CURRENT,
            IpoDateUtils.classifyTimelineTab(vedant.openDate, vedant.closeDate, vedant.listingDate, vedant.status, refMs),
        )
        assertTrue(IpoDateUtils.isBiddingOpenToday(vedant.openDate, vedant.closeDate, vedant.listingDate, vedant.status, refMs))

        // 2. Closed bidding / Allotment phase, listing date NOT arrived -> CURRENT
        val crystal = catalog.first { it.id == "crystal-crop-protection" } // close 20 Nov, list 25 Nov
        val mahanadi = catalog.first { it.id == "mahanadi-coalfields" } // close 21 Nov, list 26 Nov
        val watrana = catalog.first { it.id == "watrana-rentals-sme" } // close 23 Nov, list 26 Nov
        assertEquals(
            IpoTimelineBucket.CURRENT,
            IpoDateUtils.classifyTimelineTab(crystal.openDate, crystal.closeDate, crystal.listingDate, crystal.status, refMs),
        )
        assertEquals(
            IpoTimelineBucket.CURRENT,
            IpoDateUtils.classifyTimelineTab(mahanadi.openDate, mahanadi.closeDate, mahanadi.listingDate, mahanadi.status, refMs),
        )
        assertEquals(
            IpoTimelineBucket.CURRENT,
            IpoDateUtils.classifyTimelineTab(watrana.openDate, watrana.closeDate, watrana.listingDate, watrana.status, refMs),
        )
        // Closed status with future listing date -> still CURRENT
        assertEquals(
            IpoTimelineBucket.CURRENT,
            IpoDateUtils.classifyTimelineTab("18 Nov 2026", "21 Nov 2026", "27 Nov 2026", IpoStatus.CLOSED, refMs),
        )

        // 3. Future DRHP/RHP-filed IPOs (openDate > today or TBA UPCOMING) -> UPCOMING
        val razorpay = catalog.first { it.id == "razorpay-software" } // open 25 Nov
        val zepto = catalog.first { it.id == "zepto-kiranakart" } // open 27 Nov
        val tataCapital = catalog.first { it.id == "tata-capital" } // open 28 Nov
        assertEquals(
            IpoTimelineBucket.UPCOMING,
            IpoDateUtils.classifyTimelineTab(razorpay.openDate, razorpay.closeDate, razorpay.listingDate, razorpay.status, refMs),
        )
        assertEquals(
            IpoTimelineBucket.UPCOMING,
            IpoDateUtils.classifyTimelineTab(zepto.openDate, zepto.closeDate, zepto.listingDate, zepto.status, refMs),
        )
        assertEquals(
            IpoTimelineBucket.UPCOMING,
            IpoDateUtils.classifyTimelineTab(tataCapital.openDate, tataCapital.closeDate, tataCapital.listingDate, tataCapital.status, refMs),
        )
        assertEquals(
            IpoTimelineBucket.UPCOMING,
            IpoDateUtils.classifyTimelineTab("TBA", "TBA", "TBA", IpoStatus.UPCOMING, refMs),
        )

        // 4. Listing date arrived (listingDate <= today) -> LISTED
        val antala = catalog.first { it.id == "antala-industries-sme" } // list 24 Nov
        val ntpc = catalog.first { it.id == "ntpc-green-energy" } // list 18 Nov
        assertEquals(
            IpoTimelineBucket.LISTED,
            IpoDateUtils.classifyTimelineTab(antala.openDate, antala.closeDate, antala.listingDate, antala.status, refMs),
        )
        assertEquals(
            IpoTimelineBucket.LISTED,
            IpoDateUtils.classifyTimelineTab(ntpc.openDate, ntpc.closeDate, ntpc.listingDate, ntpc.status, refMs),
        )
    }

    @Test
    fun `home screen renders Current Upcoming Listed tabs and removes status filter chips`() {
        var selectedTab = BoardTimelineTab.CURRENT
        val catalog = IpoSeedData.createInitialCatalog()

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = false) {
                IpoBoardScreen(
                    uiState = IpoBoardUiState(
                        allIpos = catalog,
                        filteredIpos = catalog.take(3),
                        timelineTab = selectedTab,
                        isLoading = false,
                    ),
                    onSelectBoardFilter = {},
                    onSelectStatusFilter = {},
                    onSearchQueryChange = {},
                    onToggleSearch = {},
                    onRefresh = {},
                    onOpenRadar = {},
                    onOpenSettings = {},
                    onIpoClick = {},
                    onSelectTimelineTab = { selectedTab = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("timeline_tab_current").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timeline_tab_upcoming").assertIsDisplayed().performClick()
        assertEquals(BoardTimelineTab.UPCOMING, selectedTab)
        composeTestRule.onNodeWithTag("timeline_tab_listed").assertIsDisplayed().performClick()
        assertEquals(BoardTimelineTab.LISTED, selectedTab)

        // Status and quick-sort filter chips (All IPOs, Open, Pre-Apply, Saved, Featured, Top GMP, Closing Soon) are removed
        composeTestRule.onNodeWithTag("status_chip_all").assertDoesNotExist()
        composeTestRule.onNodeWithTag("status_chip_live").assertDoesNotExist()
        composeTestRule.onNodeWithTag("status_chip_pre_apply").assertDoesNotExist()
        composeTestRule.onNodeWithTag("status_chip_upcoming").assertDoesNotExist()
        composeTestRule.onNodeWithTag("status_chip_allotment").assertDoesNotExist()
        composeTestRule.onNodeWithTag("status_chip_saved").assertDoesNotExist()
        composeTestRule.onNodeWithTag("board_sort_row").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sort_chip_default").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sort_chip_highest_gmp").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sort_chip_closing_soon").assertDoesNotExist()
    }

    @Test
    fun `dark material minimalistic theme renders canonical charcoal palette and updated settings label`() {
        var bgColor = androidx.compose.ui.graphics.Color.Unspecified
        var surfaceColor = androidx.compose.ui.graphics.Color.Unspecified
        var onSurfaceColor = androidx.compose.ui.graphics.Color.Unspecified
        var isDark = false
        var isAmoled = true

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = com.example.data.preferences.ThemePreference.DARK) {
                val cs = androidx.compose.material3.MaterialTheme.colorScheme
                val glass = com.example.ui.theme.LocalGlassStyle.current
                bgColor = cs.background
                surfaceColor = cs.surface
                onSurfaceColor = cs.onSurface
                isDark = glass.isDark
                isAmoled = glass.isAmoled
                com.example.ui.screens.SettingsBottomSheet(
                    currentTheme = com.example.data.preferences.ThemePreference.DARK,
                    adImpressionCount = 0,
                    onSelectTheme = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(isDark)
        assertFalse(isAmoled)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF121212), bgColor)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1C1C1F), surfaceColor)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF4F4F5), onSurfaceColor)
        composeTestRule.onNodeWithTag("theme_option_dark").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Dark Minimal"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size >= 2,
        )
        composeTestRule.onNodeWithText("Material dark", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `upcoming html parser extracts 2-column DRHP and TBA tables without mistaking filing date for open date`() {
        val gmpRepo = com.example.data.repository.LiveGmpRepository(enableNetworkFetch = false)
        val html = """
            <table>
              <tr><th>Company Name</th><th>DRHP Filing Date</th></tr>
              <tr><td>Zepto KiranaKart Technologies Ltd</td><td>12 Sep 2026</td></tr>
              <tr><td>PhonePe India Pvt Ltd</td><td>05 Oct 2026</td></tr>
              <tr><td>Total</td><td>2</td></tr>
              <tr><td>Click Here</td><td>-</td></tr>
            </table>
            <table>
              <tr><th>IPO Name</th><th>Status</th><th>Exchange</th></tr>
              <tr><td>Lenskart Solutions Ltd</td><td>DRHP Filed</td><td>NSE, BSE</td></tr>
              <tr><td>Groww Broking Ltd</td><td>SEBI Approved</td><td>NSE</td></tr>
              <tr><td>Cancelled Ventures Ltd</td><td>Withdrawn</td><td>NSE</td></tr>
            </table>
        """.trimIndent()

        val parsed = gmpRepo.parseHtmlGmpTables(
            html = html,
            sourceName = "Chittorgarh Upcoming",
            priorityRank = 4,
            defaultStatus = IpoStatus.UPCOMING,
        )

        val names = parsed.map { it.companyName }
        assertTrue(names.any { it.contains("Zepto") })
        assertTrue(names.any { it.contains("PhonePe") })
        assertTrue(names.any { it.contains("Lenskart") })
        assertTrue(names.any { it.contains("Groww") })
        assertFalse(names.any { it.equals("Total", ignoreCase = true) })
        assertFalse(names.any { it.equals("Click Here", ignoreCase = true) })

        // Past DRHP filing date must NOT become openDate (otherwise IPO would be misclassified as LIVE/CLOSED)
        val zepto = parsed.first { it.companyName.contains("Zepto") }
        assertEquals("", zepto.openDate)
        assertEquals(IpoStatus.UPCOMING, zepto.status)
        assertTrue(com.example.data.repository.IpoFilingVerifier.isDisplayableUpcomingQuote(zepto))

        // Withdrawn row is flagged and excluded from displayable upcoming quotes
        val cancelled = parsed.firstOrNull { it.companyName.contains("Cancelled") }
        if (cancelled != null) {
            assertFalse(com.example.data.repository.IpoFilingVerifier.isDisplayableUpcomingQuote(cancelled))
        }
    }

    @Test
    fun `repository persists ALL fetched upcoming IPOs without artificial cap including single-source TBA entries`() = kotlinx.coroutines.runBlocking {
        val gmpRepo = com.example.data.repository.LiveGmpRepository(enableNetworkFetch = false)
        val distinctNames = listOf(
            "Aarav Solar Systems Ltd",
            "Bhartiya Biotech Labs Ltd",
            "Crestline Mobility Tech Ltd",
            "Deccan Semiconductors Ltd",
            "Eklavya Logistics Corp Ltd",
            "Finbridge Capital Markets Ltd",
            "Garuda Aerospace Defence Ltd",
            "Himalaya Wellness Foods Ltd",
            "IndusGrid Power Storage Ltd",
            "Janseva Microfinance Ltd",
            "Kaveri Precision Robotics Ltd",
            "Lumina Opticals Retail Ltd",
            "Malabar Specialty Chemicals Ltd",
            "Navkar Marine Terminals Ltd",
            "Ojasvi Green Hydrogen Ltd",
            "Pragati Cloud Infrastructure Ltd",
            "Quasar Medical Diagnostics Ltd",
            "Rajasthani Heritage Hotels Ltd",
            "Sahyadri Agro Exports Ltd",
            "Triveni Electric Vehicles Ltd",
            "Udaipur Marble Quarries Ltd",
            "Vayuputra Wind Turbines Ltd",
            "Western Rail Signalling Ltd",
            "Xylo Photonics India Ltd",
            "Yamuna Clean Water Tech Ltd",
            "Zenith Quantum Photonics Ltd",
        )
        val upcomingQuotes = distinctNames.mapIndexed { idx, name ->
            com.example.data.repository.LiveGmpQuote(
                companyName = name,
                normalizedKey = gmpRepo.normalizeCompanyKey(name),
                gmpRupees = 0,
                priceLow = 0,
                priceHigh = 0,
                lotSize = 0,
                issueSizeCr = 0.0,
                openDate = "",
                closeDate = "",
                listingDate = "",
                boardType = if (idx % 3 == 0) com.example.data.model.BoardType.SME else com.example.data.model.BoardType.MAINBOARD,
                status = IpoStatus.UPCOMING,
                sourceName = if (idx % 2 == 0) "Chittorgarh Upcoming" else "IPOWatch Upcoming",
                priorityRank = 5,
                hasExplicitGmp = false,
                verifiedProviders = setOf(if (idx % 2 == 0) "Chittorgarh" else "IPOWatch"),
            )
        }

        val context = RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.IpoDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val repo = com.example.data.repository.IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = gmpRepo,
            )
            repo.applySnapshotToDatabase(
                existingById = emptyMap(),
                gmpSnapshot = com.example.data.repository.LiveGmpSnapshot(
                    quotesByKey = upcomingQuotes.associateBy { it.normalizedKey },
                    allQuotes = upcomingQuotes,
                    activeSources = listOf("Chittorgarh Upcoming", "IPOWatch Upcoming"),
                    syncedAtTime = "09:30 AM",
                ),
            )

            val saved = db.ipoDao().getAllSnapshot()
            val expectedNameSet = distinctNames.toSet()
            val savedUpcomingFetched = saved.filter { it.companyName in expectedNameSet }
            // All 26 fetched upcoming IPOs must be persisted (no 10 or 20 cap) and pass display gate
            assertEquals(26, savedUpcomingFetched.size)
            assertTrue(savedUpcomingFetched.all { com.example.data.repository.IpoFilingVerifier.isDisplayableEntity(it) })
            assertTrue(savedUpcomingFetched.all { it.openDate == "TBA" })
            assertTrue(savedUpcomingFetched.all { it.gmpSourceLabel.startsWith("Fetched •") })
        } finally {
            db.close()
        }
    }

    @Test
    fun `ipo card shows TBA for GMP when fetched upcoming IPO has TBA price band and zero GMP`() {
        val tbaUpcoming = IpoSeedData.createInitialCatalog().first { it.id == "razorpay-software" }.copy(
            id = "live_tba_drhp_test",
            companyName = "Ather Energy DRHP Ltd",
            status = IpoStatus.UPCOMING,
            openDate = "TBA",
            closeDate = "TBA",
            listingDate = "TBA",
            priceLow = 0,
            priceHigh = 0,
            lotSize = 0,
            issueSizeCr = 0.0,
            gmpRupees = 0,
        )

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = false) {
                IpoCard(
                    ipo = tbaUpcoming,
                    onClick = {},
                    onShareClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Ather Energy DRHP Ltd", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("TBA"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `admin override merger applies manual edits, adds custom IPOs, hides entries, and resets cleanly`() {
        val baseCatalog = IpoSeedData.createInitialCatalog().take(3)
        val first = baseCatalog[0]
        val second = baseCatalog[1]

        val editOverride = com.example.data.model.IpoOverrideDocument(
            id = first.id,
            targetIpoId = first.id,
            normalizedKey = com.example.data.model.IpoOverrideMerger.normalizeCompanyKey(first.companyName),
            companyName = first.companyName,
            published = true,
            customIpo = false,
            hidden = false,
            priceLow = 950,
            priceHigh = 1000,
            gmpRupees = 275,
            lotSize = 15,
            status = com.example.data.model.IpoStatus.LIVE.name,
            boardType = com.example.data.model.BoardType.MAINBOARD.name,
            registrarName = "KFin Technologies Ltd",
            registrarUrl = "https://kosmic.kfintech.com/ipostatus/",
        )
        val hideOverride = com.example.data.model.IpoOverrideDocument(
            id = second.id,
            targetIpoId = second.id,
            normalizedKey = com.example.data.model.IpoOverrideMerger.normalizeCompanyKey(second.companyName),
            companyName = second.companyName,
            published = true,
            hidden = true,
        )
        val customIpoOverride = com.example.data.model.IpoOverrideDocument(
            id = "custom_aurora-robotics",
            targetIpoId = "custom_aurora-robotics",
            normalizedKey = "aurorarobotics",
            companyName = "Aurora Robotics India Ltd",
            published = true,
            customIpo = true,
            hidden = false,
            priceLow = 410,
            priceHigh = 435,
            gmpRupees = 95,
            lotSize = 34,
            status = com.example.data.model.IpoStatus.UPCOMING.name,
            boardType = com.example.data.model.BoardType.MAINBOARD.name,
        )

        val merged = com.example.data.model.IpoOverrideMerger.applyOverridesToCatalog(
            baseCatalog = baseCatalog,
            overrides = listOf(editOverride, hideOverride, customIpoOverride),
        )

        // Second IPO is hidden; custom IPO is added -> 3 total items
        assertEquals(3, merged.size)
        assertTrue(merged.none { it.id == second.id })

        val edited = merged.first { it.id == first.id }
        assertEquals(950, edited.priceLow)
        assertEquals(1000, edited.priceHigh)
        assertEquals(275, edited.gmpRupees)
        assertEquals(15, edited.lotSize)
        assertEquals("KFin Technologies Ltd", edited.registrarName)
        assertTrue(com.example.data.model.IpoOverrideMerger.isAdminManagedEntity(edited))
        assertTrue(com.example.data.repository.IpoFilingVerifier.isDisplayableEntity(edited))

        val custom = merged.first { it.id == "custom_aurora-robotics" }
        assertEquals("Aurora Robotics India Ltd", custom.companyName)
        assertEquals(435, custom.priceHigh)
        assertTrue(com.example.data.repository.IpoFilingVerifier.isDisplayableEntity(custom))

        // Resetting (removing overrides) immediately restores live catalog values
        val afterReset = com.example.data.model.IpoOverrideMerger.applyOverridesToCatalog(
            baseCatalog = baseCatalog,
            overrides = emptyList(),
        )
        assertEquals(baseCatalog, afterReset)
    }

    @Test
    fun `admin validator and auth email allowlist enforce owner-only and schema rules`() {
        assertTrue(
            com.example.data.firebase.AdminAuthManager.isAuthorizedAdminEmail(
                email = "developers.pcmedia@gmail.com",
                isEmailVerified = true,
            ),
        )
        assertFalse(
            com.example.data.firebase.AdminAuthManager.isAuthorizedAdminEmail(
                email = "developers.pcmedia@gmail.com",
                isEmailVerified = false,
            ),
        )
        assertFalse(
            com.example.data.firebase.AdminAuthManager.isAuthorizedAdminEmail(
                email = "someone.else@gmail.com",
                isEmailVerified = true,
            ),
        )

        val validDoc = com.example.data.model.IpoOverrideDocument(
            id = "ipo_valid",
            targetIpoId = "ipo_valid",
            normalizedKey = "validcompany",
            companyName = "Valid Company Ltd",
            priceLow = 100,
            priceHigh = 120,
            lotSize = 100,
            gmpRupees = 25,
            registrarUrl = "https://kosmic.kfintech.com/ipostatus/",
        )
        assertEquals(null, com.example.data.repository.AdminOverrideRepository.validateOverrideAgainstRules(validDoc))

        val badPrice = validDoc.copy(priceLow = 500, priceHigh = 400)
        assertTrue(com.example.data.repository.AdminOverrideRepository.validateOverrideAgainstRules(badPrice) != null)

        val badUrl = validDoc.copy(registrarUrl = "javascript:alert(1)")
        assertTrue(com.example.data.repository.AdminOverrideRepository.validateOverrideAgainstRules(badUrl) != null)

        val blankName = validDoc.copy(companyName = " ")
        assertTrue(com.example.data.repository.AdminOverrideRepository.validateOverrideAgainstRules(blankName) != null)
    }

    @Test
    fun `admin dashboard screen shows Google sign-in gate when signed out and viewer lock for non-admin`() {
        val sessionState = androidx.compose.runtime.mutableStateOf(
            com.example.data.firebase.AdminAuthSession(isSignedIn = false),
        )

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.screens.AdminDashboardScreen(
                    authSession = sessionState.value,
                    authErrorMessage = null,
                    isSigningIn = false,
                    uiState = com.example.ui.viewmodel.AdminDashboardUiState(),
                    onGoogleSignInClick = {},
                    onSignOutClick = {},
                    onBack = {},
                    onSearchQueryChange = {},
                    onSelectFilter = {},
                    onRefreshCloudOverrides = {},
                    onSaveOverride = { _, _ -> },
                    onToggleHideIpo = {},
                    onResetToLiveSync = { _, _, _ -> },
                    onClearFeedback = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("admin_sign_in_gate").assertIsDisplayed()
        composeTestRule.onNodeWithTag("admin_google_sign_in_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("admin_add_ipo_fab").assertDoesNotExist()

        // Switch to non-admin Google account -> read-only viewer gate
        sessionState.value = com.example.data.firebase.AdminAuthSession(
            uid = "viewer-1",
            displayName = "Viewer User",
            email = "viewer@example.com",
            isEmailVerified = true,
            isSignedIn = true,
            isAuthorizedAdmin = false,
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("admin_unauthorized_gate").assertIsDisplayed()
        composeTestRule.onNodeWithTag("admin_add_ipo_fab").assertDoesNotExist()
    }

    @Test
    fun `admin dashboard screen displays profile banner, controls, and Add IPO FAB for authorized admin`() {
        val sampleIpo = IpoSeedData.createInitialCatalog().first()
        val row = com.example.ui.viewmodel.AdminIpoRowItem(
            id = sampleIpo.id,
            effectiveIpo = sampleIpo,
            baseLiveIpo = sampleIpo,
            overrideDoc = com.example.data.model.IpoOverrideDocument(
                id = sampleIpo.id,
                targetIpoId = sampleIpo.id,
                normalizedKey = "sample",
                companyName = sampleIpo.companyName,
                adminNote = "Corrected upper price band",
            ),
        )

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.screens.AdminDashboardScreen(
                    authSession = com.example.data.firebase.AdminAuthSession(
                        uid = "owner-uid",
                        displayName = "IPO Guruji Admin",
                        email = "developers.pcmedia@gmail.com",
                        isEmailVerified = true,
                        isSignedIn = true,
                        isAuthorizedAdmin = true,
                    ),
                    authErrorMessage = null,
                    isSigningIn = false,
                    uiState = com.example.ui.viewmodel.AdminDashboardUiState(
                        rows = listOf(row),
                        totalCount = 1,
                        overriddenCount = 1,
                        customCount = 0,
                        hiddenCount = 0,
                    ),
                    onGoogleSignInClick = {},
                    onSignOutClick = {},
                    onBack = {},
                    onSearchQueryChange = {},
                    onSelectFilter = {},
                    onRefreshCloudOverrides = {},
                    onSaveOverride = { _, _ -> },
                    onToggleHideIpo = {},
                    onResetToLiveSync = { _, _, _ -> },
                    onClearFeedback = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("admin_profile_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("admin_add_ipo_fab").assertIsDisplayed()
        composeTestRule.onNodeWithTag("admin_ipo_card_${sampleIpo.id}").assertExists()
        composeTestRule.onNodeWithTag("admin_ipo_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("admin_ipo_card_${sampleIpo.id}"),
        )
        composeTestRule.onNodeWithTag("admin_edit_button_${sampleIpo.id}").assertExists()
        composeTestRule.onNodeWithTag("admin_reset_button_${sampleIpo.id}").assertExists()
        composeTestRule.onNodeWithTag("admin_card_sub_live_${sampleIpo.id}").assertExists()
    }

    @Test
    fun `user profile validation and Firestore create payload match security rules schema`() {
        assertEquals(
            null,
            com.example.data.repository.UserProfileRepository.validateInvestorDetails(
                displayName = "Aarav Mehta",
                bio = "Long-term mainboard and SME IPO applicant",
            ),
        )
        assertTrue(
            com.example.data.repository.UserProfileRepository.validateInvestorDetails(
                displayName = "   ",
                bio = "",
            ) != null,
        )
        assertTrue(
            com.example.data.repository.UserProfileRepository.validateInvestorDetails(
                displayName = "A".repeat(81),
                bio = "",
            ) != null,
        )
        assertTrue(
            com.example.data.repository.UserProfileRepository.validateInvestorDetails(
                displayName = "Aarav",
                bio = "B".repeat(241),
            ) != null,
        )

        val doc = com.example.data.model.UserProfileDocument(
            id = "uid-investor-1",
            userId = "uid-investor-1",
            displayName = "Aarav Mehta",
            email = "aarav@example.com",
            investorCategory = com.example.data.model.InvestorCategory.S_HNI.name,
            bio = "Retail + sHNI applicant",
            bookmarkedIpoIds = listOf("phonepe-india", "zepto-kiranakart"),
            preferences = com.example.data.preferences.CloudSyncedPrefsSnapshot(
                themePreference = ThemePreference.DARK,
                compactCards = true,
                notifyNewIpo = true,
                notifyOpenIpo = false,
                notifyAllotment = true,
                notifyNewsAlerts = true,
            ),
        )
        val createMap = doc.toCreateMap()
        assertFalse(createMap.containsKey("id"))
        assertFalse(createMap.containsKey("role"))
        assertFalse(createMap.containsKey("isAdmin"))
        assertEquals("uid-investor-1", createMap["userId"])
        assertEquals("Aarav Mehta", createMap["displayName"])
        assertEquals("aarav@example.com", createMap["email"])
        assertEquals("S_HNI", createMap["investorCategory"])
        assertEquals(listOf("phonepe-india", "zepto-kiranakart"), createMap["bookmarkedIpoIds"])
        assertTrue(createMap.containsKey("createdAt"))
        assertTrue(createMap.containsKey("updatedAt"))
    }

    @Test
    fun `navigation drawer completely hides admin controls from guests and normal users and shows them only for authorized admin`() {
        val sessionState = androidx.compose.runtime.mutableStateOf(
            com.example.data.firebase.AdminAuthSession(isSignedIn = false),
        )
        var openedProfile = false
        var openedAdmin = false

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.components.AppNavigationDrawerSheet(
                    activeItem = com.example.ui.components.DrawerMenuItemId.HOME,
                    liveIpoCount = 12,
                    lastSyncedLabel = "Just now",
                    drawerWidth = 300.dp,
                    onSelectItem = {},
                    onQuickSyncGmp = {},
                    adminSession = sessionState.value,
                    userDisplayName = if (sessionState.value.isSignedIn) "Aarav Mehta" else "",
                    investorCategory = com.example.data.model.InvestorCategory.RETAIL,
                    bookmarkedCount = 3,
                    activeOverrideCount = 2,
                    onOpenUserProfile = { openedProfile = true },
                    onOpenAdminDashboard = { openedAdmin = true },
                )
            }
        }

        // 1. Signed-out guest -> sees Investor Profile + Sign In, ZERO Admin elements
        composeTestRule.onNodeWithTag("drawer_user_profile_card").assertExists()
        composeTestRule.onNodeWithTag("drawer_google_sign_in_button").assertExists()
        composeTestRule.onNodeWithTag("drawer_open_user_profile_button").assertExists()
        composeTestRule.onNodeWithTag("drawer_open_admin_dashboard_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("drawer_admin_verified_badge").assertDoesNotExist()

        // 2. Normal signed-in user -> sees category badge + Open Profile, ZERO Admin elements
        sessionState.value = com.example.data.firebase.AdminAuthSession(
            uid = "normal-user-1",
            displayName = "Aarav Mehta",
            email = "aarav@example.com",
            isEmailVerified = true,
            isSignedIn = true,
            isAuthorizedAdmin = false,
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_user_category_badge").assertExists()
        composeTestRule.onNodeWithTag("drawer_open_user_profile_button").assertExists().performClick()
        assertTrue(openedProfile)
        composeTestRule.onNodeWithTag("drawer_open_admin_dashboard_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("drawer_admin_verified_badge").assertDoesNotExist()

        // 3. Authorized owner admin -> sees verified ADMIN pill + Admin Dashboard button + My Profile button
        sessionState.value = com.example.data.firebase.AdminAuthSession(
            uid = "owner-admin-uid",
            displayName = "IPO Guruji Admin",
            email = "developers.pcmedia@gmail.com",
            isEmailVerified = true,
            isSignedIn = true,
            isAuthorizedAdmin = true,
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_admin_verified_badge").assertExists()
        composeTestRule.onNodeWithTag("drawer_open_user_profile_button").assertExists()
        composeTestRule.onNodeWithTag("drawer_open_admin_dashboard_button").assertExists().performClick()
        assertTrue(openedAdmin)
    }

    @Test
    fun `user profile screen supports guest mode and signed-in investor editing without exposing any admin controls`() {
        val sampleIpo = IpoSeedData.createInitialCatalog().first()
        val uiStateHolder = androidx.compose.runtime.mutableStateOf(
            com.example.ui.viewmodel.UserProfileUiState(
                isSignedIn = false,
                bookmarkedIpos = listOf(sampleIpo),
                bookmarkedIds = setOf(sampleIpo.id),
            ),
        )
        var savedName: String? = null

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.screens.UserProfileScreen(
                    uiState = uiStateHolder.value,
                    authErrorMessage = null,
                    isSigningIn = false,
                    onBack = {},
                    onGoogleSignInClick = {},
                    onSignOutClick = {},
                    onSaveInvestorDetails = { name, _, _ -> savedName = name },
                    onOpenIpoDetail = {},
                    onRemoveBookmark = {},
                    onSelectTheme = {},
                    onToggleCompactCards = {},
                    onToggleNotifyNewIpo = {},
                    onToggleNotifyOpenIpo = {},
                    onToggleNotifyAllotment = {},
                    onToggleNotifyNewsAlerts = {},
                    onClearFeedback = {},
                )
            }
        }

        // Guest mode: optional Google Sign-In card visible, no Admin UI
        composeTestRule.onNodeWithTag("user_profile_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("user_profile_google_sign_in_button").assertExists()
        composeTestRule.onNodeWithTag("admin_add_ipo_fab").assertDoesNotExist()
        composeTestRule.onNodeWithTag("drawer_open_admin_dashboard_button").assertDoesNotExist()

        // Signed-in normal user: sees Investor Details form, Save button, and Saved Watchlist row
        uiStateHolder.value = com.example.ui.viewmodel.UserProfileUiState(
            isSignedIn = true,
            isCloudSynced = true,
            displayName = "Priya Sharma",
            email = "priya@example.com",
            investorCategory = com.example.data.model.InvestorCategory.RETAIL,
            bio = "Tracking Mainboard IPOs",
            bookmarkedIpos = listOf(sampleIpo),
            bookmarkedIds = setOf(sampleIpo.id),
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("user_profile_google_sign_in_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("user_profile_content_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("user_profile_investor_details_card"),
        )
        composeTestRule.onNodeWithTag("user_profile_display_name_input").assertExists()
        composeTestRule.onNodeWithTag("user_profile_save_details_button")
            .assertExists()
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals("Priya Sharma", savedName)

        composeTestRule.onNodeWithTag("user_profile_content_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("watchlist_ipo_row_${sampleIpo.id}"),
        )
        composeTestRule.onNodeWithTag("watchlist_ipo_row_${sampleIpo.id}").assertExists()
    }

    @Test
    fun `updated privacy policy discloses optional Google Sign-In cloud profile sync, local PANs, and data deletion`() {
        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.screens.PrivacyPolicyScreen(onBack = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("privacy_policy_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy_policy_highlights_card").assertExists()
        composeTestRule.onNodeWithText("1. Optional Google Sign-In, Investor Profile & Cloud Sync").assertExists()
        composeTestRule.onNodeWithTag("privacy_policy_screen_list").performScrollToNode(
            androidx.compose.ui.test.hasText("2. Private PAN Profiles & Allotment Checks"),
        )
        composeTestRule.onNodeWithText("2. Private PAN Profiles & Allotment Checks").assertExists()

        composeTestRule.onNodeWithTag("privacy_policy_screen_list").performScrollToNode(
            androidx.compose.ui.test.hasText("9. Your Controls, Data Deletion & Contact Us"),
        )
        composeTestRule.onNodeWithText("9. Your Controls, Data Deletion & Contact Us").assertExists()

        composeTestRule.onNodeWithTag("privacy_policy_screen_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("privacy_policy_email_button"),
        )
        composeTestRule.onNodeWithTag("privacy_policy_email_button").assertExists()
    }

    @Test
    fun `user profile screen offers in-app delete account confirmation only for signed-in users`() {
        var deleteConfirmed = false
        val uiStateHolder = androidx.compose.runtime.mutableStateOf(
            com.example.ui.viewmodel.UserProfileUiState(isSignedIn = false),
        )

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.screens.UserProfileScreen(
                    uiState = uiStateHolder.value,
                    authErrorMessage = null,
                    isSigningIn = false,
                    onBack = {},
                    onGoogleSignInClick = {},
                    onSignOutClick = {},
                    onSaveInvestorDetails = { _, _, _ -> },
                    onOpenIpoDetail = {},
                    onRemoveBookmark = {},
                    onSelectTheme = {},
                    onToggleCompactCards = {},
                    onToggleNotifyNewIpo = {},
                    onToggleNotifyOpenIpo = {},
                    onToggleNotifyAllotment = {},
                    onToggleNotifyNewsAlerts = {},
                    onDeleteAccount = { deleteConfirmed = true },
                    onClearFeedback = {},
                )
            }
        }

        // Guest: no Delete Account button
        composeTestRule.onNodeWithTag("user_profile_delete_account_button").assertDoesNotExist()

        // Signed-in user: Delete Account card + confirmation dialog work
        uiStateHolder.value = com.example.ui.viewmodel.UserProfileUiState(
            isSignedIn = true,
            isCloudSynced = true,
            displayName = "Rohan Patel",
            email = "rohan@example.com",
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("user_profile_content_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("user_profile_account_data_card"),
        )
        composeTestRule.onNodeWithTag("user_profile_delete_account_button")
            .assertExists()
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("user_profile_delete_account_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("user_profile_confirm_delete_button").assertExists().performClick()
        composeTestRule.waitForIdle()
        assertTrue(deleteConfirmed)
    }

    @Test
    fun `partial admin override preserves live subscription and live GMP even with legacy subscription override fields`() {
        val liveBase = IpoSeedData.createInitialCatalog().first().copy(
            priceLow = 300,
            priceHigh = 320,
            lotSize = 46,
            gmpRupees = 85,
            gmpSourceLabel = "Verified • InvestorGain • Chittorgarh",
            updatedAgoLabel = "InvestorGain • 11:30 AM",
            qibSubX = 14.25,
            bNiiSubX = 9.80,
            sNiiSubX = 6.40,
            retailSubX = 4.15,
            employeeSubX = 1.90,
            totalSubX = 8.72,
        )

        // Legacy override doc: edited ONLY lotSize & openDate, plus stale legacy subscription numbers
        val legacyPartialOverride = com.example.data.model.IpoOverrideDocument(
            id = liveBase.id,
            targetIpoId = liveBase.id,
            normalizedKey = com.example.data.model.IpoOverrideMerger.normalizeCompanyKey(liveBase.companyName),
            companyName = liveBase.companyName,
            published = true,
            customIpo = false,
            hidden = false,
            lotSize = 50,
            openDate = "28 Nov 2026",
            // Legacy frozen subscription fields that MUST be ignored at merge time
            qibSubX = 0.5,
            bNiiSubX = 0.2,
            sNiiSubX = 0.1,
            retailSubX = 0.3,
            employeeSubX = 0.0,
            totalSubX = 0.4,
        )

        val merged = com.example.data.model.IpoOverrideMerger.overlayEntity(liveBase, legacyPartialOverride)

        // Only edited fields changed
        assertEquals(50, merged.lotSize)
        assertEquals("28 Nov 2026", merged.openDate)
        assertEquals(2, legacyPartialOverride.overriddenFieldCount(liveBase))

        // Untouched price band & GMP stay 100% live-synced
        assertEquals(300, merged.priceLow)
        assertEquals(320, merged.priceHigh)
        assertEquals(85, merged.gmpRupees)
        assertEquals("Verified • InvestorGain • Chittorgarh", merged.gmpSourceLabel)

        // Subscription ALWAYS comes from liveBase (ignoring legacy override values)
        assertEquals(14.25, merged.qibSubX, 0.0001)
        assertEquals(9.80, merged.bNiiSubX, 0.0001)
        assertEquals(6.40, merged.sNiiSubX, 0.0001)
        assertEquals(4.15, merged.retailSubX, 0.0001)
        assertEquals(1.90, merged.employeeSubX, 0.0001)
        assertEquals(8.72, merged.totalSubX, 0.0001)

        // Partially overridden entity remains displayable & admin-managed
        assertTrue(com.example.data.model.IpoOverrideMerger.isAdminManagedEntity(merged))
        assertTrue(com.example.data.repository.IpoFilingVerifier.isDisplayableEntity(merged))

        // Explicit GMP edit overrides GMP while still keeping subscription live
        val gmpEdited = com.example.data.model.IpoOverrideMerger.overlayEntity(
            liveBase,
            legacyPartialOverride.copy(gmpRupees = 110),
        )
        assertEquals(110, gmpEdited.gmpRupees)
        assertEquals("Admin Verified • Manual", gmpEdited.gmpSourceLabel)
        assertEquals(8.72, gmpEdited.totalSubX, 0.0001)
        assertEquals(14.25, gmpEdited.qibSubX, 0.0001)

        // Firestore create/update maps never write subscription and delete legacy subscription + empty sections
        val createMap = legacyPartialOverride.toCreateMap()
        assertFalse(createMap.containsKey("subscription"))
        assertFalse(createMap.containsKey("meta"))
        assertFalse(createMap.containsKey("registrar"))
        assertTrue(createMap.containsKey("pricing"))
        assertTrue(createMap.containsKey("schedule"))

        val updateMap = legacyPartialOverride.toUpdateMap()
        assertEquals(com.google.firebase.firestore.FieldValue.delete(), updateMap["subscription"])
        assertEquals(com.google.firebase.firestore.FieldValue.delete(), updateMap["meta"])
        assertEquals(com.google.firebase.firestore.FieldValue.delete(), updateMap["registrar"])
        assertTrue(updateMap["pricing"] is Map<*, *>)
        assertTrue(updateMap["schedule"] is Map<*, *>)
    }

    @Test
    fun `admin editor shows read-only always-live subscription card and saves diff-only field overrides`() {
        val liveBase = IpoSeedData.createInitialCatalog().first().copy(
            lotSize = 40,
            gmpRupees = 65,
            qibSubX = 11.40,
            retailSubX = 5.20,
            totalSubX = 7.85,
        )
        val row = com.example.ui.viewmodel.AdminIpoRowItem(
            id = liveBase.id,
            effectiveIpo = liveBase,
            baseLiveIpo = liveBase,
            overrideDoc = null,
        )
        var savedDoc: com.example.data.model.IpoOverrideDocument? = null

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.screens.AdminIpoEditorSheet(
                    editingRow = row,
                    isSaving = false,
                    onDismiss = {},
                    onSave = { savedDoc = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Read-only Live Subscription card & ALWAYS LIVE badge exist; editable subscription inputs are gone
        composeTestRule.onNodeWithTag("admin_live_subscription_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("admin_subscription_always_live_badge").assertExists()
        composeTestRule.onNodeWithTag("admin_field_total_sub").assertDoesNotExist()
        composeTestRule.onNodeWithTag("admin_field_qib_sub").assertDoesNotExist()
        composeTestRule.onNodeWithTag("admin_field_retail_sub").assertDoesNotExist()

        // Edit GMP then click Reset GMP to verify inline reset returns GMP to live sync
        composeTestRule.onNodeWithTag("admin_field_gmp").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("admin_field_gmp").performTextInput("120")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("admin_reset_field_gmp").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("admin_reset_field_gmp").assertDoesNotExist()

        // Edit ONLY lot size, then save
        composeTestRule.onNodeWithTag("admin_field_lot_size").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("admin_field_lot_size").performTextInput("55")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("admin_editor_save_button").performClick()
        composeTestRule.waitForIdle()

        val result = savedDoc
        assertTrue("Expected diff-only override document to be saved", result != null)
        assertEquals(55, result!!.lotSize)
        assertEquals(null, result.gmpRupees)
        assertEquals(null, result.priceLow)
        assertEquals(null, result.priceHigh)
        assertEquals(null, result.openDate)
        assertEquals(null, result.registrarName)
        assertEquals(null, result.qibSubX)
        assertEquals(null, result.bNiiSubX)
        assertEquals(null, result.sNiiSubX)
        assertEquals(null, result.retailSubX)
        assertEquals(null, result.employeeSubX)
        assertEquals(null, result.totalSubX)
        assertEquals(1, result.overriddenFieldCount(liveBase))
    }

    @Test
    fun `live subscription strict source priority overwrites stale higher cached figures without sticky maxOf`() {
        val repo = com.example.data.repository.LiveSubscriptionRepository(enableNetworkFetch = false)
        val nseQuote = com.example.data.repository.LiveSubscriptionQuote(
            companyName = "Zenith Precision Ltd",
            normalizedKey = repo.normalizeCompanyKey("Zenith Precision Ltd"),
            boardType = com.example.data.model.BoardType.MAINBOARD,
            qibSubX = 1.85,
            bNiiSubX = 5.40,
            sNiiSubX = 3.10,
            niiTotalSubX = 4.63,
            retailSubX = 4.65,
            totalSubX = 4.20,
            sourceName = "NSE Live Bid",
            priorityRank = com.example.data.repository.LiveSubscriptionRepository.PRIORITY_NSE_LIVE_BID,
            asOfLabel = "26 Nov, 02:15 PM",
        )
        val lowerPriorityHigherQuote = com.example.data.repository.LiveSubscriptionQuote(
            companyName = "Zenith Precision IPO",
            normalizedKey = repo.normalizeCompanyKey("Zenith Precision IPO"),
            boardType = com.example.data.model.BoardType.MAINBOARD,
            qibSubX = 8.00,
            bNiiSubX = 14.50,
            sNiiSubX = 9.20,
            niiTotalSubX = 12.73,
            retailSubX = 11.20,
            employeeSubX = 1.45,
            totalSubX = 9.90,
            sourceName = "InvestorGain",
            priorityRank = com.example.data.repository.LiveSubscriptionRepository.PRIORITY_INVESTORGAIN_SUMMARY,
            asOfLabel = "26 Nov, 02:10 PM",
        )

        val merged = repo.mergeSubscriptionQuotes(listOf(lowerPriorityHigherQuote, nseQuote))
        assertEquals(1, merged.size)
        val winner = merged.first()
        // NSE strictly wins over lower-priority InvestorGain even when InvestorGain reports higher numbers
        assertEquals(4.20, winner.totalSubX, 0.001)
        assertEquals(1.85, winner.qibSubX, 0.001)
        assertEquals(5.40, winner.bNiiSubX, 0.001)
        assertEquals(3.10, winner.sNiiSubX, 0.001)
        assertEquals(4.65, winner.retailSubX, 0.001)
        // Category fields from lower-priority quotes are NEVER mixed into the winning quote
        assertEquals(0.0, winner.employeeSubX, 0.001)
        assertTrue(winner.sourceName.startsWith("NSE Live Bid"))

        // Applying authoritative quote MUST overwrite stale/higher cached entity figures downward
        val staleEntity = IpoSeedData.createInitialCatalog().first().copy(
            companyName = "Zenith Precision Ltd",
            qibSubX = 10.00,
            bNiiSubX = 15.00,
            sNiiSubX = 11.00,
            retailSubX = 9.50,
            totalSubX = 12.50,
        )
        val updated = repo.applySubscriptionToEntity(staleEntity, winner, "26 Nov, 02:15 PM")
        assertEquals(4.20, updated.totalSubX, 0.001)
        assertEquals(1.85, updated.qibSubX, 0.001)
        assertEquals(5.40, updated.bNiiSubX, 0.001)
        assertEquals(3.10, updated.sNiiSubX, 0.001)
        assertEquals(4.65, updated.retailSubX, 0.001)
        assertTrue(updated.subscriptionSourceLabel.contains("NSE Live Bid"))
    }

    @Test
    fun `html subscription table parser disambiguates Total Applications from Total multiplier and extracts detail URL`() {
        val repo = com.example.data.repository.LiveSubscriptionRepository(enableNetworkFetch = false)
        val sampleHtml = """
            <table>
              <thead>
                <tr>
                  <th>Company Name</th>
                  <th>QIB</th>
                  <th>bNII</th>
                  <th>sNII</th>
                  <th>Retail</th>
                  <th>Total Applications</th>
                  <th>Total (x)</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td><a href="/ipo_subscription/acme-tech/1234/">Acme Tech IPO</a></td>
                  <td>3.40x</td>
                  <td>8.15x</td>
                  <td>4.90x</td>
                  <td>5.25x</td>
                  <td>1,45,200</td>
                  <td>6.45x</td>
                </tr>
              </tbody>
            </table>
        """.trimIndent()

        val quotes = repo.parseHtmlSubscriptionTables(
            html = sampleHtml,
            sourceName = "Chittorgarh",
            priorityRank = com.example.data.repository.LiveSubscriptionRepository.PRIORITY_CHITTORGARH_SUMMARY,
            asOfLabel = "26 Nov, 03:00 PM",
            defaultBoardType = com.example.data.model.BoardType.MAINBOARD,
            baseUrl = "https://www.chittorgarh.com",
        )
        assertEquals(1, quotes.size)
        val q = quotes.first()
        assertEquals(3.40, q.qibSubX, 0.001)
        assertEquals(8.15, q.bNiiSubX, 0.001)
        assertEquals(4.90, q.sNiiSubX, 0.001)
        assertEquals(5.25, q.retailSubX, 0.001)
        // Must NOT mistake Total Applications (145,200) for Total multiplier
        assertEquals(6.45, q.totalSubX, 0.001)
        assertEquals("https://www.chittorgarh.com/ipo_subscription/acme-tech/1234/", q.detailUrl)
        val totalAppRow = q.applicationCounts.firstOrNull { it.categoryId == "total" }
        assertTrue(totalAppRow != null)
        assertEquals(145_200L, totalAppRow!!.appliedApplications)
    }

    @Test
    fun `per-IPO detail HTML and NSE bid details parsers extract Day 1 2 3 history and live application-wise breakup counts`() {
        val repo = com.example.data.repository.LiveSubscriptionRepository(enableNetworkFetch = false)
        val detailHtml = """
            <table>
              <tr><th>Day</th><th>Date</th><th>QIB</th><th>bNII</th><th>sNII</th><th>Retail</th><th>Total</th></tr>
              <tr><td>Day 1</td><td>18 Nov 2026</td><td>0.45</td><td>1.20</td><td>0.95</td><td>1.80</td><td>1.15</td></tr>
              <tr><td>Day 2</td><td>19 Nov 2026</td><td>2.10</td><td>4.85</td><td>3.40</td><td>4.60</td><td>3.72</td></tr>
              <tr><td>Day 3</td><td>20 Nov 2026</td><td>18.40</td><td>14.20</td><td>9.60</td><td>8.95</td><td>13.25</td></tr>
            </table>
            <table>
              <tr><th>Investor Category</th><th>Reserved Applications</th><th>Total Applications</th><th>Subscription (x)</th></tr>
              <tr><td>Retail</td><td>2,10,000</td><td>18,79,500</td><td>8.95</td></tr>
              <tr><td>S-HNI</td><td>4,200</td><td>40,320</td><td>9.60</td></tr>
              <tr><td>Big HNI</td><td>8,400</td><td>1,19,280</td><td>14.20</td></tr>
              <tr><td>Employee</td><td>1,500</td><td>3,150</td><td>2.10</td></tr>
              <tr><td>Total</td><td>2,24,100</td><td>20,42,250</td><td>13.25</td></tr>
            </table>
        """.trimIndent()

        val detailQuote = repo.parseHtmlIpoDetailSubscriptionPage(
            html = detailHtml,
            companyName = "Acme Tech Ltd",
            boardType = com.example.data.model.BoardType.MAINBOARD,
            sourceName = "Chittorgarh Detail",
            priorityRank = com.example.data.repository.LiveSubscriptionRepository.PRIORITY_CHITTORGARH_DETAIL,
            asOfLabel = "20 Nov, 05:00 PM",
        )
        assertTrue(detailQuote != null)
        assertEquals(3, detailQuote!!.dailyHistory.size)
        assertEquals("Day 1", detailQuote.dailyHistory[0].dayLabel)
        assertEquals("18 Nov 2026", detailQuote.dailyHistory[0].dateLabel)
        assertEquals(1.20, detailQuote.dailyHistory[0].bNiiX, 0.001)
        assertEquals(0.95, detailQuote.dailyHistory[0].sNiiX, 0.001)
        assertEquals("Day 3", detailQuote.dailyHistory[2].dayLabel)
        assertEquals(13.25, detailQuote.dailyHistory[2].totalX, 0.001)

        val retailApp = detailQuote.applicationCounts.first { it.categoryId == "retail" }
        assertEquals(210_000L, retailApp.reservedApplications)
        assertEquals(1_879_500L, retailApp.appliedApplications)
        assertEquals(2_042_250L, detailQuote.applicationCounts.first { it.categoryId == "total" }.appliedApplications)

        // NSE bid details JSON with noOfapplication
        val nseJson = """
            {
              "dataList": [
                {"category": "Qualified Institutional Buyers (QIBs)", "noOfTime": "19.10", "noOfapplication": "142"},
                {"category": "Non Institutional Investors (Above 10 Lakh)", "noOfTime": "15.00", "noOfapplication": "126000"},
                {"category": "Non Institutional Investors (Below 10 Lakh)", "noOfTime": "10.20", "noOfapplication": "42840"},
                {"category": "Retail Individual Investors (RIIs)", "noOfTime": "9.15", "noOfapplication": "1921500"},
                {"category": "Total", "noOfTime": "13.85", "noOfapplication": "2090482"}
              ]
            }
        """.trimIndent()
        val nseQuotes = repo.parseNseBidDetailsJson(
            rawJson = nseJson,
            companyName = "Acme Tech Ltd",
            symbol = "ACMETECH",
            sourceName = "NSE Live Bid",
            priorityRank = com.example.data.repository.LiveSubscriptionRepository.PRIORITY_NSE_LIVE_BID,
            asOfLabel = "20 Nov, 05:05 PM",
        )
        assertEquals(1, nseQuotes.size)
        val merged = repo.mergeSubscriptionQuotes(listOf(detailQuote, nseQuotes.first())).first()
        // NSE bid numbers win for multipliers & applied counts, while Chittorgarh Detail enriches Day 1/2/3 & reserved counts
        assertEquals(13.85, merged.totalSubX, 0.001)
        assertEquals(3, merged.dailyHistory.size)
        assertEquals(1_921_500L, merged.applicationCounts.first { it.categoryId == "retail" }.appliedApplications)
        assertEquals(210_000L, merged.applicationCounts.first { it.categoryId == "retail" }.reservedApplications)
    }

    @Test
    fun `ipo detail subscription tab renders 6-column Day 1 2 3 table and switches LIVE EXCHANGE vs ESTIMATED application breakup badge`() {
        val baseIpo = IpoSeedData.createInitialCatalog().first().copy(
            qibSubX = 18.40,
            bNiiSubX = 14.20,
            sNiiSubX = 9.60,
            retailSubX = 8.95,
            totalSubX = 13.25,
            subscriptionSourceLabel = "NSE Live Bid • 20 Nov, 04:45 PM",
            dailySubscriptions = listOf(
                com.example.data.model.DaySubscription("Day 1", 0.45, 1.12, 1.80, 1.15, "18 Nov", 1.20, 0.95),
                com.example.data.model.DaySubscription("Day 2", 2.10, 4.37, 4.60, 3.72, "19 Nov", 4.85, 3.40),
                com.example.data.model.DaySubscription("Day 3", 18.40, 12.67, 8.95, 13.25, "20 Nov", 14.20, 9.60),
            ),
            liveApplicationCounts = emptyList(),
        )
        val ipoState = androidx.compose.runtime.mutableStateOf(baseIpo)

        composeTestRule.setContent {
            IpoPulseTheme(darkTheme = true) {
                com.example.ui.screens.IpoDetailScreen(
                    ipo = ipoState.value,
                    selectedTab = com.example.ui.viewmodel.DetailTab.SUBSCRIPTION,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. LiveSubscriptionCard displays authoritative source label
        composeTestRule.onNodeWithTag("detail_lazy_column").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("live_subscription_card"),
        )
        composeTestRule.onNodeWithTag("live_subscription_source_value").assertExists()

        // 2. ApplicationBreakupCard shows ESTIMATED badge when live exchange counts are not yet available
        composeTestRule.onNodeWithTag("detail_lazy_column").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("application_breakup_card"),
        )
        composeTestRule.onNodeWithTag("application_breakup_source_badge").assertExists()
        composeTestRule.onNodeWithText("ESTIMATED", useUnmergedTree = true).assertExists()

        // 3. DailySubscriptionTableCard renders 6-column Day 1 / Day 2 / Day 3 table with bNII, sNII & dates
        composeTestRule.onNodeWithTag("detail_lazy_column").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("daily_subscription_table_card"),
        )
        composeTestRule.onNodeWithTag("daily_sub_header_bnii").assertExists()
        composeTestRule.onNodeWithTag("daily_sub_header_snii").assertExists()
        composeTestRule.onNodeWithTag("daily_sub_row_0").assertExists()
        composeTestRule.onNodeWithTag("daily_sub_row_1").assertExists()
        composeTestRule.onNodeWithTag("daily_sub_row_2").assertExists()
        composeTestRule.onNodeWithText("Day 1", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("18 Nov", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Day 3", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("20 Nov", useUnmergedTree = true).assertExists()

        // 4. Switching to live exchange application counts immediately flips badge to LIVE EXCHANGE
        ipoState.value = baseIpo.copy(
            liveApplicationCounts = listOf(
                com.example.data.model.LiveApplicationCount("retail", "Retail", 210_000L, 1_879_500L, 8.95),
                com.example.data.model.LiveApplicationCount("shni", "S-HNI", 4_200L, 40_320L, 9.60),
                com.example.data.model.LiveApplicationCount("bhni", "Big HNI", 8_400L, 119_280L, 14.20),
                com.example.data.model.LiveApplicationCount("total", "Total", 222_600L, 2_039_100L, 13.25),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_lazy_column").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("application_breakup_card"),
        )
        composeTestRule.onNodeWithText("LIVE EXCHANGE", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `smart date range parser preserves hyphenated single dates and expands compact and cross-month ranges`() {
        val refMs = com.example.data.model.IpoDateUtils.parseFlexibleDateMs("20 Nov 2026")!!

        // Single hyphenated dates must NEVER be split or duplicated into closeDate == openDate
        assertEquals("12 Nov 2026" to "", com.example.data.model.IpoDateUtils.parseDateRange("12-Nov-2026", refMs))
        assertEquals("12 Nov 2026" to "", com.example.data.model.IpoDateUtils.parseDateRange("2026-11-12", refMs))

        // Compact same-month & month-first ranges
        assertEquals("12 Nov 2026" to "14 Nov 2026", com.example.data.model.IpoDateUtils.parseDateRange("12-14 Nov 2026", refMs))
        assertEquals("12 Nov 2026" to "14 Nov 2026", com.example.data.model.IpoDateUtils.parseDateRange("Nov 12–14, 2026", refMs))

        // Cross-month and Dec->Jan year rollover ranges
        assertEquals("28 Nov 2026" to "02 Dec 2026", com.example.data.model.IpoDateUtils.parseDateRange("28 Nov - 2 Dec 2026", refMs))
        assertEquals("30 Dec 2026" to "02 Jan 2027", com.example.data.model.IpoDateUtils.parseDateRange("30 Dec - 2 Jan 2027", refMs))

        // Non-dates (currency, percentages, bare numbers, TBA) are strictly rejected
        assertEquals("", com.example.data.repository.IpoFilingVerifier.sanitizeConfirmedDate("₹285", refMs))
        assertEquals("", com.example.data.repository.IpoFilingVerifier.sanitizeConfirmedDate("₹320 (18%)", refMs))
        assertEquals("", com.example.data.repository.IpoFilingVerifier.sanitizeConfirmedDate("12", refMs))
        assertEquals("", com.example.data.repository.IpoFilingVerifier.sanitizeConfirmedDate("TBA", refMs))
    }

    @Test
    fun `InvestorGain JSON and HTML parsers ignore Listing Open or Close prices and extract true Offer and Close dates`() {
        val repo = com.example.data.repository.LiveGmpRepository(enableNetworkFetch = false)
        val json = """
            {
              "reportTableData": [
                {
                  "Name": "Aurora Mobility Systems IPO",
                  "Listing Open": "₹285",
                  "Listing Close": "₹310",
                  "Open Price": "₹250",
                  "Est Listing": "₹320 (18%)",
                  "Issue Price": "₹240-₹255",
                  "GMP": "₹42 (16.5%)",
                  "Open": "25-Nov-2026",
                  "Close": "27-Nov-2026",
                  "Listing": "02-Dec-2026",
                  "Lot": "58",
                  "IPO Size": "₹650 Cr"
                }
              ]
            }
        """.trimIndent()

        val parsedJson = repo.parseInvestorGainJson(json, sourceName = "InvestorGain", priorityRank = 1)
        assertEquals(1, parsedJson.size)
        val q = parsedJson.first()
        assertEquals("25 Nov 2026", q.openDate)
        assertEquals("27 Nov 2026", q.closeDate)
        assertEquals("02 Dec 2026", q.listingDate)
        assertEquals(255, q.priceHigh)
        assertEquals(42, q.gmpRupees)

        val html = """
            <table>
              <tr>
                <th>IPO Name</th>
                <th>Listing Open</th>
                <th>Issue Price</th>
                <th>IPO Date</th>
                <th>Listing Date</th>
                <th>GMP</th>
              </tr>
              <tr>
                <td>Vertex Photonics IPO</td>
                <td>₹410</td>
                <td>₹350</td>
                <td>25-27 Nov 2026</td>
                <td>02 Dec 2026</td>
                <td>₹55</td>
              </tr>
            </table>
        """.trimIndent()
        val parsedHtml = repo.parseHtmlGmpTables(html, sourceName = "Chittorgarh", priorityRank = 4)
        assertEquals(1, parsedHtml.size)
        val hq = parsedHtml.first()
        assertEquals("25 Nov 2026", hq.openDate)
        assertEquals("27 Nov 2026", hq.closeDate)
        assertEquals("02 Dec 2026", hq.listingDate)
    }

    @Test
    fun `date merging strictly prioritizes NSE over Chittorgarh over InvestorGain while keeping InvestorGain GMP`() {
        val repo = com.example.data.repository.LiveGmpRepository(enableNetworkFetch = false)

        val investorGainQuote = com.example.data.repository.LiveGmpQuote(
            companyName = "Helios Semiconductor Ltd",
            normalizedKey = repo.normalizeCompanyKey("Helios Semiconductor Ltd"),
            gmpRupees = 68,
            priceLow = 310,
            priceHigh = 325,
            openDate = "21 Nov 2026",
            closeDate = "23 Nov 2026",
            listingDate = "28 Nov 2026",
            sourceName = "InvestorGain",
            priorityRank = 1,
            hasExplicitGmp = true,
        )
        val chittorgarhQuote = com.example.data.repository.LiveGmpQuote(
            companyName = "Helios Semiconductor Ltd",
            normalizedKey = repo.normalizeCompanyKey("Helios Semiconductor Ltd"),
            gmpRupees = 0,
            priceLow = 310,
            priceHigh = 325,
            openDate = "24 Nov 2026",
            closeDate = "26 Nov 2026",
            listingDate = "01 Dec 2026",
            sourceName = "Chittorgarh",
            priorityRank = 4,
            hasExplicitGmp = false,
        )
        val nseQuote = com.example.data.repository.LiveGmpQuote(
            companyName = "Helios Semiconductor Ltd",
            normalizedKey = repo.normalizeCompanyKey("Helios Semiconductor Ltd"),
            gmpRupees = 0,
            priceLow = 310,
            priceHigh = 325,
            openDate = "25 Nov 2026",
            closeDate = "27 Nov 2026",
            listingDate = "",
            sourceName = "NSE India",
            priorityRank = 0,
            hasExplicitGmp = false,
        )

        // 1. Chittorgarh beats InvestorGain for dates, while InvestorGain keeps GMP
        val chittoVsIg = repo.mergeQuotesWithoutDuplicates(listOf(investorGainQuote, chittorgarhQuote)).first()
        assertEquals("24 Nov 2026", chittoVsIg.openDate)
        assertEquals("26 Nov 2026", chittoVsIg.closeDate)
        assertEquals("01 Dec 2026", chittoVsIg.listingDate)
        assertEquals(68, chittoVsIg.gmpRupees)

        // 2. NSE beats both Chittorgarh and InvestorGain for Open/Close dates, inherits Chittorgarh's consistent Listing date, and keeps InvestorGain GMP
        val allThree = repo.mergeQuotesWithoutDuplicates(listOf(investorGainQuote, chittorgarhQuote, nseQuote)).first()
        assertEquals("25 Nov 2026", allThree.openDate)
        assertEquals("27 Nov 2026", allThree.closeDate)
        assertEquals("01 Dec 2026", allThree.listingDate)
        assertEquals(68, allThree.gmpRupees)
    }

    @Test
    fun `date-first status inference and Current tab routing keep missing or TBA dates in Upcoming and include open plus awaiting-listing in Current`() {
        val repo = com.example.data.repository.LiveGmpRepository(enableNetworkFetch = false)
        val todayRefMs = com.example.data.model.IpoDateUtils.parseFlexibleDateMs("26 Nov 2026")!!

        // 1. Stale "Open" hint on an IPO that closed on 24 Nov 2026 must still infer CLOSED (date-first!)
        val staleHintStatus = repo.inferStatus(
            statusHint = "Open Live",
            openDate = "22 Nov 2026",
            closeDate = "24 Nov 2026",
            priceHigh = 250,
            listingDate = "29 Nov 2026",
            referenceTimeMs = todayRefMs,
        )
        assertEquals(IpoStatus.CLOSED, staleHintStatus)

        // 2. Missing/TBA dates with "Open" hint must NEVER become LIVE or enter CURRENT
        val missingDateStatus = repo.inferStatus(
            statusHint = "Open Live",
            openDate = "",
            closeDate = "",
            priceHigh = 250,
            listingDate = "",
            referenceTimeMs = todayRefMs,
        )
        assertEquals(IpoStatus.UPCOMING, missingDateStatus)
        assertEquals(
            com.example.data.model.IpoTimelineBucket.UPCOMING,
            com.example.data.model.IpoDateUtils.classifyTimelineTab(
                openDate = "TBA",
                closeDate = "TBA",
                listingDate = "",
                status = IpoStatus.LIVE,
                referenceTimeMs = todayRefMs,
            ),
        )

        // 3. Inverted/corrupted bidding dates (close < open) must route to UPCOMING, never CURRENT
        assertEquals(
            com.example.data.model.IpoTimelineBucket.UPCOMING,
            com.example.data.model.IpoDateUtils.classifyTimelineTab(
                openDate = "28 Nov 2026",
                closeDate = "24 Nov 2026",
                listingDate = "",
                status = IpoStatus.LIVE,
                referenceTimeMs = todayRefMs,
            ),
        )

        // 4. Actively open today (25 Nov .. 27 Nov) -> CURRENT
        assertEquals(
            com.example.data.model.IpoTimelineBucket.CURRENT,
            com.example.data.model.IpoDateUtils.classifyTimelineTab(
                openDate = "25 Nov 2026",
                closeDate = "27 Nov 2026",
                listingDate = "02 Dec 2026",
                status = IpoStatus.LIVE,
                referenceTimeMs = todayRefMs,
            ),
        )

        // 5. Recently closed (24 Nov) awaiting listing (29 Nov) -> CURRENT (allotment phase)
        assertEquals(
            com.example.data.model.IpoTimelineBucket.CURRENT,
            com.example.data.model.IpoDateUtils.classifyTimelineTab(
                openDate = "22 Nov 2026",
                closeDate = "24 Nov 2026",
                listingDate = "29 Nov 2026",
                status = IpoStatus.CLOSED,
                referenceTimeMs = todayRefMs,
            ),
        )

        // 6. Future open date (30 Nov) -> UPCOMING; listing date arrived (26 Nov) -> LISTED
        assertEquals(
            com.example.data.model.IpoTimelineBucket.UPCOMING,
            com.example.data.model.IpoDateUtils.classifyTimelineTab(
                openDate = "30 Nov 2026",
                closeDate = "02 Dec 2026",
                listingDate = "07 Dec 2026",
                status = IpoStatus.PRE_APPLY,
                referenceTimeMs = todayRefMs,
            ),
        )
        assertEquals(
            com.example.data.model.IpoTimelineBucket.LISTED,
            com.example.data.model.IpoDateUtils.classifyTimelineTab(
                openDate = "19 Nov 2026",
                closeDate = "21 Nov 2026",
                listingDate = "26 Nov 2026",
                status = IpoStatus.CLOSED,
                referenceTimeMs = todayRefMs,
            ),
        )
    }

    @Test
    fun `empty replaceCatalog never wipes existing DB rows and atomic replaceCatalog inserts new rows while pruning obsolete ids`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.IpoDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val dao = db.ipoDao()
            val seed = IpoSeedData.createInitialCatalog().take(3)
            val rowA = seed[0].copy(id = "ipo_a", companyName = "Alpha Precision Ltd", gmpRupees = 45, updatedAgoLabel = "NSE • 10:00 AM")
            val rowB = seed[1].copy(id = "ipo_b", companyName = "Beta Energy Ltd", gmpRupees = 30, updatedAgoLabel = "NSE • 10:00 AM")
            val rowC = seed[2].copy(id = "ipo_c", companyName = "Gamma BioTech Ltd", gmpRupees = 18, updatedAgoLabel = "NSE • 10:00 AM")

            dao.insertAll(listOf(rowA, rowB))
            assertEquals(setOf("ipo_a", "ipo_b"), dao.getAllSnapshot().map { it.id }.toSet())

            // 1. Empty items or empty activeIds must be a no-op (never runs SQLite "NOT IN ()")
            dao.replaceCatalog(emptyList(), emptyList())
            dao.replaceCatalog(listOf(rowC), emptyList())
            assertEquals(setOf("ipo_a", "ipo_b"), dao.getAllSnapshot().map { it.id }.toSet())

            // 2. Atomic replaceCatalog inserts/updates active rows and removes obsolete rows in one transaction
            val updatedRowA = rowA.copy(gmpRupees = 72)
            dao.replaceCatalog(listOf(updatedRowA, rowC), listOf("ipo_a", "ipo_c"))
            val afterReplace = dao.getAllSnapshot().associateBy { it.id }
            assertEquals(setOf("ipo_a", "ipo_c"), afterReplace.keys)
            assertEquals(72, afterReplace["ipo_a"]?.gmpRupees)
            assertEquals("Gamma BioTech Ltd", afterReplace["ipo_c"]?.companyName)
        } finally {
            db.close()
        }
    }

    @Test
    fun `offline sync leaves populated DB untouched, seeds only on empty DB, and never fabricates Synced labels on unmatched seed rows`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.IpoDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val dao = db.ipoDao()
            val gmpRepo = com.example.data.repository.LiveGmpRepository(enableNetworkFetch = false)
            val repo = com.example.data.repository.IpoRepository(ipoDao = dao, liveGmpRepository = gmpRepo)

            val offlineSnapshot = com.example.data.repository.LiveGmpSnapshot(
                quotesByKey = emptyMap(),
                allQuotes = emptyList(),
                activeSources = emptyList(),
                syncedAtTime = "27 Nov 2026, 09:45 AM",
            )

            // 1. First launch (completely empty DB) falls back to seed catalog without fabricating "Synced ..." labels
            val firstLaunchSummary = repo.applySnapshotToDatabase(
                existingById = emptyMap(),
                gmpSnapshot = offlineSnapshot,
            )
            assertFalse(firstLaunchSummary.isLiveFromNetwork)
            val seededRows = dao.getAllSnapshot()
            assertTrue(seededRows.isNotEmpty())
            assertTrue(seededRows.none { it.updatedAgoLabel.contains("Synced 27 Nov 2026, 09:45 AM") })

            // 2. Populated DB with live cached row: subsequent offline sync leaves DB 100% untouched
            val cachedLiveRow = seededRows.first().copy(
                id = "live_preserved_custom",
                companyName = "Preserved Live Solar Ltd",
                gmpRupees = 99,
                updatedAgoLabel = "NSE India • Yesterday 04:30 PM",
            )
            dao.replaceCatalog(listOf(cachedLiveRow), listOf(cachedLiveRow.id))
            val existingBeforeOffline = dao.getAllSnapshot().associateBy { it.id }

            val offlineAgainSummary = repo.applySnapshotToDatabase(
                existingById = existingBeforeOffline,
                gmpSnapshot = offlineSnapshot.copy(syncedAtTime = "27 Nov 2026, 11:15 AM"),
            )
            assertFalse(offlineAgainSummary.isLiveFromNetwork)
            val afterOffline = dao.getAllSnapshot()
            assertEquals(1, afterOffline.size)
            assertEquals("live_preserved_custom", afterOffline.first().id)
            assertEquals(99, afterOffline.first().gmpRupees)
            assertEquals("NSE India • Yesterday 04:30 PM", afterOffline.first().updatedAgoLabel)

            // 3. Partial live sync (< 3 verified quotes) never stamps unmatched seed rows with "Synced $syncTime"
            val singleUpcomingQuote = com.example.data.repository.LiveGmpQuote(
                companyName = "Fresh Partial Discovery Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Fresh Partial Discovery Ltd"),
                gmpRupees = 0,
                status = IpoStatus.UPCOMING,
                sourceName = "Chittorgarh Upcoming",
                priorityRank = 4,
                hasExplicitGmp = false,
                verifiedProviders = setOf("Chittorgarh"),
            )
            val partialSyncTime = "27 Nov 2026, 12:30 PM"
            repo.applySnapshotToDatabase(
                existingById = existingBeforeOffline,
                gmpSnapshot = com.example.data.repository.LiveGmpSnapshot(
                    quotesByKey = mapOf(singleUpcomingQuote.normalizedKey to singleUpcomingQuote),
                    allQuotes = listOf(singleUpcomingQuote),
                    activeSources = listOf("Chittorgarh Upcoming"),
                    syncedAtTime = partialSyncTime,
                ),
            )
            val afterPartial = dao.getAllSnapshot()
            val unmatchedSeedRows = afterPartial.filter { !it.id.startsWith("live_") }
            assertTrue(unmatchedSeedRows.isNotEmpty())
            assertTrue(unmatchedSeedRows.none { it.updatedAgoLabel.contains("Synced $partialSyncTime") })
        } finally {
            db.close()
        }
    }

    @Test
    fun `AdminOverrideDao replaceAll is atomic and AdminOverrideRepository skips cache wipe on empty isFromCache snapshot`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.IpoDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val overrideDao = db.adminOverrideDao()
            val overrideRepo = com.example.data.repository.AdminOverrideRepository(
                db = null,
                overrideDao = overrideDao,
                auth = null,
            )

            val doc1 = com.example.data.model.IpoOverrideDocument(
                id = "ipo_override_1",
                targetIpoId = "ipo_override_1",
                normalizedKey = "firstoverride",
                companyName = "First Override Ltd",
                gmpRupees = 55,
            )
            val doc2 = com.example.data.model.IpoOverrideDocument(
                id = "ipo_override_2",
                targetIpoId = "ipo_override_2",
                normalizedKey = "secondoverride",
                companyName = "Second Override Ltd",
                gmpRupees = 80,
            )

            // 1. Authoritative server snapshot populates cache atomically
            overrideRepo.applyCloudSnapshotToCache(isFromCache = false, docs = listOf(doc1))
            assertEquals(listOf("ipo_override_1"), overrideDao.getAllSnapshot().map { it.overrideId })

            // 2. Empty from-cache Firestore snapshot MUST NOT wipe cached overrides
            val preserved = overrideRepo.applyCloudSnapshotToCache(isFromCache = true, docs = emptyList())
            assertEquals(listOf("ipo_override_1"), preserved.map { it.id })
            assertEquals(listOf("ipo_override_1"), overrideDao.getAllSnapshot().map { it.overrideId })

            // 3. New authoritative snapshot replaces atomically via @Transaction replaceAll
            overrideRepo.applyCloudSnapshotToCache(isFromCache = false, docs = listOf(doc2))
            assertEquals(listOf("ipo_override_2"), overrideDao.getAllSnapshot().map { it.overrideId })
            assertEquals(80, overrideDao.getAllSnapshot().first().gmpRupees)
        } finally {
            db.close()
        }
    }

    @Test
    fun `hung GMP source times out individually while finished sources are returned, failed NSE warmup skips only NSE JSON, and cancellation propagates`() = kotlinx.coroutines.runBlocking {
        val repo = com.example.data.repository.LiveGmpRepository(enableNetworkFetch = false)
        val tasks = listOf(
            com.example.data.repository.LiveGmpRepository.SourceTask(
                name = "NSE India",
                priorityRank = 0,
                url = "https://www.nseindia.com/api/ipo-current-issue",
                kind = com.example.data.repository.LiveGmpRepository.SourceKind.NSE_JSON,
            ),
            com.example.data.repository.LiveGmpRepository.SourceTask(
                name = "InvestorGain",
                priorityRank = 1,
                url = "https://webnodejs.investorgain.com/test",
                kind = com.example.data.repository.LiveGmpRepository.SourceKind.INVESTORGAIN_JSON,
            ),
            com.example.data.repository.LiveGmpRepository.SourceTask(
                name = "Hung Source",
                priorityRank = 2,
                url = "https://hung.example.com/gmp",
                kind = com.example.data.repository.LiveGmpRepository.SourceKind.HTML_TABLE,
            ),
            com.example.data.repository.LiveGmpRepository.SourceTask(
                name = "Chittorgarh",
                priorityRank = 4,
                url = "https://www.chittorgarh.com/test",
                kind = com.example.data.repository.LiveGmpRepository.SourceKind.HTML_TABLE,
            ),
        )

        var nseCalled = false
        val snapshot = repo.fetchSourcesInParallel(
            sourceTasks = tasks,
            nowMs = 1_000_000L,
            syncTime = "27 Nov 2026, 03:30 PM",
            perSourceTimeoutMs = 250L,
            nseWarmupTimeoutMs = 150L,
            nseWarmupRunner = {
                // Simulate NSE warm-up hanging past its timeout -> should skip NSE JSON only
                kotlinx.coroutines.delay(5_000L)
                true
            },
            sourceFetcher = { task ->
                when (task.name) {
                    "NSE India" -> {
                        nseCalled = true
                        emptyList()
                    }
                    "Hung Source" -> {
                        // Hang far longer than perSourceTimeoutMs
                        kotlinx.coroutines.delay(10_000L)
                        emptyList()
                    }
                    "InvestorGain" -> listOf(
                        com.example.data.repository.LiveGmpQuote(
                            companyName = "Solaris Grid Systems Ltd",
                            normalizedKey = repo.normalizeCompanyKey("Solaris Grid Systems Ltd"),
                            gmpRupees = 58,
                            priceLow = 210,
                            priceHigh = 220,
                            openDate = "25 Nov 2026",
                            closeDate = "27 Nov 2026",
                            sourceName = "InvestorGain",
                            priorityRank = 1,
                            hasExplicitGmp = true,
                        ),
                    )
                    "Chittorgarh" -> listOf(
                        com.example.data.repository.LiveGmpQuote(
                            companyName = "Apex BioLabs Ltd",
                            normalizedKey = repo.normalizeCompanyKey("Apex BioLabs Ltd"),
                            gmpRupees = 32,
                            priceLow = 140,
                            priceHigh = 150,
                            openDate = "26 Nov 2026",
                            closeDate = "28 Nov 2026",
                            sourceName = "Chittorgarh",
                            priorityRank = 4,
                            hasExplicitGmp = true,
                        ),
                    )
                    else -> emptyList()
                }
            },
        )

        // 1. NSE JSON source was skipped when warmup timed out
        assertFalse(nseCalled)
        // 2. Both fast sources' quotes were preserved despite the hung sibling source
        assertEquals(listOf("InvestorGain", "Chittorgarh"), snapshot.activeSources)
        assertEquals(2, snapshot.allQuotes.size)
        assertTrue(snapshot.allQuotes.any { it.companyName == "Solaris Grid Systems Ltd" && it.gmpRupees == 58 })
        assertTrue(snapshot.allQuotes.any { it.companyName == "Apex BioLabs Ltd" && it.gmpRupees == 32 })

        // 3. CancellationException must NOT be swallowed by per-source catch blocks
        var caughtCancellation = false
        try {
            repo.fetchSourcesInParallel(
                sourceTasks = listOf(tasks[1]),
                nowMs = 1_000_000L,
                syncTime = "27 Nov 2026, 03:31 PM",
                perSourceTimeoutMs = 2_000L,
                nseWarmupTimeoutMs = 1_000L,
                nseWarmupRunner = { true },
                sourceFetcher = { throw kotlinx.coroutines.CancellationException("Parent cancelled") },
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
    }

    @Test
    fun `company matching distinguishes generic industry words, enforces boardType on equal keys, and uses word-boundary SME detection`() {
        val repo = com.example.data.repository.LiveGmpRepository()

        // Distinct distinguishing words ("Industries" vs "Technologies") must NOT collide
        assertFalse(repo.areSameCompanyNames("Alpha Industries Ltd", "Alpha Technologies Ltd"))
        // Legal suffixes ("Ltd" vs "Limited") MUST still match as the same company
        assertTrue(repo.areSameCompanyNames("Alpha Industries Ltd", "Alpha Industries Limited"))

        // Equal normalizedKey with mismatched boardType must NOT match when both have priceHigh > 0
        val mainboardQuote = com.example.data.repository.LiveGmpQuote(
            companyName = "Alpha Industries Ltd",
            normalizedKey = repo.normalizeCompanyKey("Alpha Industries Ltd"),
            gmpRupees = 20,
            priceLow = 100,
            priceHigh = 110,
            boardType = com.example.data.model.BoardType.MAINBOARD,
            sourceName = "Source A",
            priorityRank = 1,
        )
        val smeQuoteWithPrice = mainboardQuote.copy(
            boardType = com.example.data.model.BoardType.SME,
            priceLow = 100,
            priceHigh = 110,
            sourceName = "Source B",
        )
        val smeQuoteUnknownPrice = mainboardQuote.copy(
            boardType = com.example.data.model.BoardType.SME,
            priceLow = 0,
            priceHigh = 0,
            sourceName = "Source C",
        )
        assertFalse(repo.areSameCompany(mainboardQuote, smeQuoteWithPrice))
        assertTrue(repo.areSameCompany(mainboardQuote, smeQuoteUnknownPrice))

        // Word-boundary SME regex must not misclassify "Cosmetics" as SME
        assertEquals(
            com.example.data.model.BoardType.MAINBOARD,
            repo.detectBoardType(rawName = "Sree Cosmetics Ltd", categoryRaw = "", priceLow = 200, lotSize = 70),
        )
        assertEquals(
            com.example.data.model.BoardType.MAINBOARD,
            repo.detectBoardType(rawName = "Sree Cosmetics Ltd", categoryRaw = "Mainboard", priceLow = 0, lotSize = 0),
        )
        assertEquals(
            com.example.data.model.BoardType.SME,
            repo.detectBoardType(rawName = "Sree Cosmetics SME IPO", categoryRaw = "", priceLow = 0, lotSize = 0),
        )
    }

    @Test
    fun `crypto box round trip uses random 12-byte IV and allotment repository stores only ciphertext while returning plaintext`() = kotlinx.coroutines.runBlocking {
        val pan = "ABCDE1234F"
        val encrypted1 = com.example.security.CryptoBox.encrypt(pan)
        val encrypted2 = com.example.security.CryptoBox.encrypt(pan)

        assertTrue(encrypted1 != pan)
        assertFalse(encrypted1.contains(pan))
        assertTrue(encrypted1 != encrypted2) // random 12-byte IV produces unique ciphertexts
        val decoded = android.util.Base64.decode(encrypted1, android.util.Base64.NO_WRAP)
        // 12-byte IV + 10-byte plaintext + 16-byte GCM auth tag = 38 bytes
        assertEquals(38, decoded.size)
        assertEquals(pan, com.example.security.CryptoBox.decrypt(encrypted1))
        assertEquals(pan, com.example.security.CryptoBox.decrypt(encrypted2))
        assertEquals("", com.example.security.CryptoBox.encrypt(""))
        assertEquals("", com.example.security.CryptoBox.decrypt(""))

        // Tampered ciphertext must fail authentication tag verification
        val tamperedBytes = decoded.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }
        val tamperedBase64 = android.util.Base64.encodeToString(tamperedBytes, android.util.Base64.NO_WRAP)
        assertTrue(runCatching { com.example.security.CryptoBox.decrypt(tamperedBase64) }.isFailure)

        // End-to-end repository check: DAO holds ciphertext, repository returns plaintext & working maskedPan
        val context = RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.IpoDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            var checkedPan: String? = null
            val fakeChecker = object : com.example.data.repository.RegistrarAllotmentChecker {
                override suspend fun checkStatus(
                    watch: com.example.data.model.AllotmentWatchEntity,
                    panNumber: String,
                    referenceTimeMs: Long,
                ): com.example.data.repository.RegistrarCheckOutcome {
                    checkedPan = panNumber
                    return com.example.data.repository.RegistrarCheckOutcome(
                        status = com.example.data.model.AllotmentWatchStatus.PENDING,
                        summary = "Checked",
                    )
                }
            }
            val allotmentRepo = com.example.data.repository.AllotmentRepository(db.allotmentDao(), checker = fakeChecker)
            val saved = allotmentRepo.saveProfile(
                displayName = "Self",
                panNumber = "ABCDE1234F",
                dematId = "1208160012345678",
                upiHandle = "self@okaxis",
            ).getOrThrow()

            // Repository returns plaintext & maskedPan works
            assertEquals("ABCDE1234F", saved.panNumber)
            assertEquals("1208160012345678", saved.dematId)
            assertEquals("self@okaxis", saved.upiHandle)
            assertEquals("ABCD••••4F", saved.maskedPan)

            // Raw DAO row contains ciphertext only
            val rawRow = db.allotmentDao().getProfileById(saved.id)!!
            assertTrue(rawRow.panNumber != "ABCDE1234F" && !rawRow.panNumber.contains("ABCDE1234F"))
            assertTrue(rawRow.dematId != "1208160012345678" && !rawRow.dematId.contains("1208160012345678"))
            assertTrue(rawRow.upiHandle != "self@okaxis" && !rawRow.upiHandle.contains("self@okaxis"))
            assertEquals("ABCDE1234F", com.example.security.CryptoBox.decrypt(rawRow.panNumber))

            // Reading via repository returns plaintext & duplicate PAN detection still works
            val loaded = allotmentRepo.getAllProfiles().single()
            assertEquals("ABCDE1234F", loaded.panNumber)
            assertEquals("ABCD••••4F", loaded.maskedPan)
            assertTrue(
                allotmentRepo.saveProfile(displayName = "Duplicate", panNumber = "ABCDE1234F").isFailure,
            )

            // Tracking and background watch check receive maskedPan and decrypted plaintext PAN
            val sampleIpo = IpoSeedData.createInitialCatalog().first()
            val watches = allotmentRepo.trackIpoForProfiles(sampleIpo, listOf(saved.id), lotsApplied = 1).getOrThrow()
            assertEquals("ABCD••••4F", watches.single().maskedPan)
            allotmentRepo.checkAllPendingWatches()
            assertEquals("ABCDE1234F", checkedPan)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 7 to 8 encrypts existing plaintext PAN, dematId, and upiHandle rows in place`() {
        val context = RuntimeEnvironment.getApplication()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory SQLite DB
            .callback(
                object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE `private_user_profiles` (
                                `id` TEXT NOT NULL,
                                `displayName` TEXT NOT NULL,
                                `panNumber` TEXT NOT NULL,
                                `dematId` TEXT NOT NULL,
                                `upiHandle` TEXT NOT NULL,
                                `isDefault` INTEGER NOT NULL,
                                `createdAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            // Seed v7 rows with plaintext PAN, Demat ID, and UPI handle
            db.execSQL(
                "INSERT INTO `private_user_profiles` VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf("profile-1", "Self", "ABCDE1234F", "1208160098765432", "investor@okhdfcbank", 1, 1000L),
            )
            db.execSQL(
                "INSERT INTO `private_user_profiles` VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf("profile-2", "Spouse", "FGHIJ5678K", "", "", 0, 2000L),
            )

            com.example.data.local.IpoDatabase.MIGRATION_7_8.migrate(db)

            db.query("SELECT `panNumber`, `dematId`, `upiHandle` FROM `private_user_profiles` WHERE `id` = 'profile-1'").use { c ->
                assertTrue(c.moveToFirst())
                val storedPan = c.getString(0)
                val storedDemat = c.getString(1)
                val storedUpi = c.getString(2)
                assertTrue(storedPan != "ABCDE1234F" && !storedPan.contains("ABCDE1234F"))
                assertTrue(storedDemat != "1208160098765432" && !storedDemat.contains("1208160098765432"))
                assertTrue(storedUpi != "investor@okhdfcbank" && !storedUpi.contains("investor@okhdfcbank"))
                assertEquals("ABCDE1234F", com.example.security.CryptoBox.decrypt(storedPan))
                assertEquals("1208160098765432", com.example.security.CryptoBox.decrypt(storedDemat))
                assertEquals("investor@okhdfcbank", com.example.security.CryptoBox.decrypt(storedUpi))
            }

            db.query("SELECT `panNumber`, `dematId`, `upiHandle` FROM `private_user_profiles` WHERE `id` = 'profile-2'").use { c ->
                assertTrue(c.moveToFirst())
                val storedPan = c.getString(0)
                val storedDemat = c.getString(1)
                val storedUpi = c.getString(2)
                assertTrue(storedPan != "FGHIJ5678K")
                assertEquals("FGHIJ5678K", com.example.security.CryptoBox.decrypt(storedPan))
                assertEquals("", storedDemat)
                assertEquals("", storedUpi)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun `adminNote is excluded from public ipo_overrides maps and Room CachedAdminOverrideEntity`() {
        val docWithNote = com.example.data.model.IpoOverrideDocument(
            id = "test-ipo",
            targetIpoId = "test-ipo",
            normalizedKey = "testipo",
            companyName = "Test IPO Ltd",
            shortCode = "TEST",
            adminNote = "Internal private admin note",
        )

        val createMap = docWithNote.toCreateMap()
        val createMeta = createMap["meta"] as? Map<*, *>
        assertTrue(createMeta != null)
        assertEquals("TEST", createMeta!!["shortCode"])
        assertFalse(createMeta.containsKey("adminNote"))

        val updateMap = docWithNote.toUpdateMap()
        val updateMeta = updateMap["meta"] as? Map<*, *>
        assertTrue(updateMeta != null)
        assertFalse(updateMeta!!.containsKey("adminNote"))

        // When only adminNote was set in meta, toUpdateMap() deletes meta from existing public docs
        val noteOnlyDoc = docWithNote.copy(shortCode = null)
        val noteOnlyUpdate = noteOnlyDoc.toUpdateMap()
        assertEquals(com.google.firebase.firestore.FieldValue.delete(), noteOnlyUpdate["meta"])

        // Room cache never stores or reads adminNote
        val cached = com.example.data.model.CachedAdminOverrideEntity.fromDocument(docWithNote)
        assertEquals(null, cached.adminNote)
        assertEquals(null, cached.copy(adminNote = "legacy-cached-note").toDocument().adminNote)
    }

    @Test
    fun `missing listing date uses T+3 working days in IST so Thursday close is not listed on Monday but is on Wednesday`() {
        // 19 Nov 2026 is a Thursday:
        // +1 working day = Fri 20 Nov, skip Sat 21 & Sun 22,
        // +2 working days = Mon 23 Nov, +3 working days = Tue 24 Nov (expected listing date).
        // Treated as listed only when today is AFTER Tue 24 Nov -> Wed 25 Nov.
        val closeDate = "19 Nov 2026"
        val mondayMs = IpoDateUtils.parseFlexibleDateMs("23 Nov 2026")!!
        val tuesdayMs = IpoDateUtils.parseFlexibleDateMs("24 Nov 2026")!!
        val wednesdayMs = IpoDateUtils.parseFlexibleDateMs("25 Nov 2026")!!

        assertFalse(
            IpoDateUtils.isListingDateArrived(
                listingDate = "",
                closeDate = closeDate,
                status = IpoStatus.CLOSED,
                referenceTimeMs = mondayMs,
            ),
        )
        assertEquals(
            IpoTimelineBucket.CURRENT,
            IpoDateUtils.classifyTimelineTab(
                openDate = "17 Nov 2026",
                closeDate = closeDate,
                listingDate = "TBA",
                status = IpoStatus.CLOSED,
                referenceTimeMs = mondayMs,
            ),
        )

        // Expected listing day itself (Tue) is not yet AFTER expected date
        assertFalse(
            IpoDateUtils.isListingDateArrived(
                listingDate = "TBA",
                closeDate = closeDate,
                status = IpoStatus.CLOSED,
                referenceTimeMs = tuesdayMs,
            ),
        )

        // Following Wednesday (after Tue expected listing) is treated as listed
        assertTrue(
            IpoDateUtils.isListingDateArrived(
                listingDate = "",
                closeDate = closeDate,
                status = IpoStatus.CLOSED,
                referenceTimeMs = wednesdayMs,
            ),
        )
        assertEquals(
            IpoTimelineBucket.LISTED,
            IpoDateUtils.classifyTimelineTab(
                openDate = "17 Nov 2026",
                closeDate = closeDate,
                listingDate = "",
                status = IpoStatus.CLOSED,
                referenceTimeMs = wednesdayMs,
            ),
        )
    }

    @Test
    fun `parseFlexibleDateMs returns identical results before and after caching including nulls and yearless dates across days`() {
        IpoDateUtils.clearParseCacheForTest()
        assertEquals(0, IpoDateUtils.parseCacheSizeForTest())

        val refNov2026 = IpoDateUtils.parseFlexibleDateMs("20 Nov 2026")!!
        val refJan2027 = IpoDateUtils.parseFlexibleDateMs("05 Jan 2027")!!
        IpoDateUtils.clearParseCacheForTest()

        val samples = listOf(
            "24 Nov 2026",
            "5-Nov-2026",
            "Nov 24, 2026",
            "2026-11-24",
            "24/11/2026",
            "24 Nov 26",
            "Thu, 19th Nov 2026 (Tentative) 10:00 AM IST",
            "15 Jun",
            "15 Dec",
            "10 Jan",
            "TBA",
            "Not a date",
            "32 Nov 2026",
            "12 Nov 2010",
            "₹450",
            "",
        )

        // Verify null results are cached in LruCache
        assertEquals(null, IpoDateUtils.parseFlexibleDateMs("TBA", refNov2026))
        assertEquals(1, IpoDateUtils.parseCacheSizeForTest())
        IpoDateUtils.clearParseCacheForTest()

        // Cold pass (uncached)
        val coldResultsNov = samples.map { IpoDateUtils.parseFlexibleDateMs(it, refNov2026) }
        val coldResultsJan = samples.map { IpoDateUtils.parseFlexibleDateMs(it, refJan2027) }
        assertTrue(IpoDateUtils.parseCacheSizeForTest() > 0)

        // Warm pass (cached) must return identical results for every valid and null input
        val warmResultsNov = samples.map { IpoDateUtils.parseFlexibleDateMs(it, refNov2026) }
        val warmResultsJan = samples.map { IpoDateUtils.parseFlexibleDateMs(it, refJan2027) }
        assertEquals(coldResultsNov, warmResultsNov)
        assertEquals(coldResultsJan, warmResultsJan)

        // Verify yearless dates resolve differently across reference days without stale cache collisions
        assertEquals("15 Jun 2026", IpoDateUtils.formatCanonicalDate(warmResultsNov[samples.indexOf("15 Jun")]!!))
        assertEquals("15 Jun 2027", IpoDateUtils.formatCanonicalDate(warmResultsJan[samples.indexOf("15 Jun")]!!))
        assertEquals("10 Jan 2027", IpoDateUtils.formatCanonicalDate(warmResultsNov[samples.indexOf("10 Jan")]!!))
        assertEquals("15 Dec 2026", IpoDateUtils.formatCanonicalDate(warmResultsJan[samples.indexOf("15 Dec")]!!))
        assertEquals(null, warmResultsNov[samples.indexOf("TBA")])
        assertEquals(null, warmResultsNov[samples.indexOf("32 Nov 2026")])
    }

    private class InMemoryUsIpoDao : UsIpoDao {
        private val items = MutableStateFlow<List<UsIpoEntity>>(emptyList())

        override fun observeAllUsIpos(): Flow<List<UsIpoEntity>> = items

        override suspend fun getAllSnapshot(): List<UsIpoEntity> = items.value

        override fun observeById(dealId: String): Flow<UsIpoEntity?> =
            items.map { list -> list.firstOrNull { it.dealId == dealId } }

        override suspend fun getById(dealId: String): UsIpoEntity? =
            items.value.firstOrNull { it.dealId == dealId }

        override suspend fun upsertAll(items: List<UsIpoEntity>) {
            this.items.value = items
        }

        override suspend fun clearAll() {
            items.value = emptyList()
        }

        override suspend fun updateSecFiling(
            dealId: String,
            formType: String,
            filingUrl: String,
            sourceLabel: String,
        ) {
            items.value = items.value.map {
                if (it.dealId == dealId) {
                    it.copy(secFormType = formType, secFilingUrl = filingUrl, sourceLabel = sourceLabel)
                } else {
                    it
                }
            }
        }
    }
}
