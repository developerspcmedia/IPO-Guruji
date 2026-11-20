package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ads.AdBannerSlot
import com.example.ads.AdMobManager
import com.example.data.local.IpoDatabase
import com.example.data.local.IpoSeedData
import com.example.data.model.AllotmentWatchStatus
import com.example.data.model.BoardType
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoNewsCatalog
import com.example.data.model.IpoStatus
import com.example.data.model.PanPrivacyUtils
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.LoadAdError
import com.example.data.preferences.ThemePreference
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AllotmentRepository
import com.example.data.repository.DirectRegistrarAllotmentChecker
import com.example.data.repository.FilingRejectionReason
import com.example.data.repository.IpoFilingVerifier
import com.example.data.repository.IpoNewsRepository
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveGmpQuote
import com.example.data.repository.LiveGmpRepository
import com.example.data.repository.LiveGmpSnapshot
import com.example.data.repository.LiveSubscriptionRepository
import com.example.data.repository.LiveSubscriptionSnapshot
import com.example.data.repository.RegistrarAllotmentChecker
import com.example.data.repository.RegistrarCheckOutcome
import com.example.notifications.IpoNotificationManager
import com.example.ui.components.IpoCard
import com.example.ui.components.buildIpoShareMessage
import com.example.ui.components.computeClosingUrgencyLabel
import com.example.data.model.NewsCategory
import com.example.ui.navigation.AppNavigation
import com.example.ui.screens.AllotmentAlertsScreen
import com.example.ui.screens.AppOpenSplashScreen
import com.example.ui.screens.CalendarEventType
import com.example.ui.screens.CalendarTimelineItem
import com.example.ui.screens.ContactUsDialog
import com.example.ui.screens.IpoBoardScreen
import com.example.ui.screens.IpoDetailScreen
import com.example.ui.screens.IpoEventCalendarBuilder
import com.example.ui.screens.IpoNewsScreen
import com.example.ui.screens.MarketRadarScreen
import com.example.ui.screens.SettingsBottomSheet
import com.example.ui.screens.YearWiseIpoListScreen
import com.example.ui.screens.YearWiseSubscriptionScreen
import com.example.ui.screens.extractIpoYear
import com.example.ui.theme.AppTypography
import com.example.ui.theme.InterSans
import com.example.ui.theme.IpoPulseTheme
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.AllotmentAlertsUiState
import com.example.ui.viewmodel.BoardFilter
import com.example.ui.viewmodel.BoardSortMode
import com.example.ui.viewmodel.BoardTimelineTab
import com.example.ui.viewmodel.DetailTab
import com.example.ui.viewmodel.IpoBoardUiState
import com.example.ui.viewmodel.IpoBoardViewModel
import com.example.ui.viewmodel.IpoDetailViewModel
import com.example.ui.viewmodel.RadarSortMode
import com.example.ui.viewmodel.SubscriptionSyncUiState
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.PrivateUserProfileEntity
import com.example.data.preferences.GmpWatchRule
import com.example.ui.screens.AllotmentProbabilityCalculator
import com.example.ui.screens.CompareIposScreen
import com.example.ui.screens.FreshVsOfsAnalyzer
import com.example.ui.screens.IpoEventCalendarScreen
import com.example.ui.viewmodel.PortfolioPnlCalculator
import com.example.widget.IpoLiveWidgetProvider
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `app name and admob ids are configured`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("IPO Guruji", context.getString(R.string.app_name))
        assertEquals("ca-app-pub-3318993523524673~7395215103", context.getString(R.string.admob_app_id))
        assertEquals("ca-app-pub-3318993523524673/9107752028", context.getString(R.string.admob_banner_unit_id))
        assertEquals("ca-app-pub-3318993523524673/8233654054", context.getString(R.string.admob_interstitial_unit_id))
        assertEquals("ca-app-pub-3318993523524673/6776318332", context.getString(R.string.admob_native_unit_id))
        assertEquals("ca-app-pub-3318993523524673/8827766602", context.getString(R.string.admob_app_open_unit_id))
    }

    @Test
    fun `seed catalog includes mainboard and sme ipos with financials and reliable images`() {
        val catalog = IpoSeedData.createInitialCatalog()
        assertTrue(catalog.any { it.boardType == BoardType.MAINBOARD })
        assertTrue(catalog.any { it.boardType == BoardType.SME })
        assertTrue(catalog.all { it.financialYears.size == 3 && it.gmpHistory.isNotEmpty() })
        assertTrue(catalog.all { it.logoUrl.startsWith("https://") && it.bannerImageUrl.startsWith("https://") })
        val news = IpoNewsCatalog.getLatestNews()
        assertTrue(
            news.all {
                it.logoUrl.startsWith("https://") &&
                    it.imageUrl.startsWith("https://") &&
                    it.articleUrl.startsWith("https://")
            },
        )
    }

    @Test
    fun `syncCatalogOnAppOpen updates offline fallback catalog while preserving user lots`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
            )
            repo.ensureSeeded()

            val first = repo.observeAllIpos().first().first()
            repo.updateUserLots(first.id, 4)

            val syncedAt = repo.syncCatalogOnAppOpen()
            assertTrue(syncedAt.isNotBlank())

            val refreshed = repo.observeAllIpos().first()
            assertEquals(12, refreshed.size)
            assertTrue(refreshed.any { it.id == "watrana-rentals-sme" })
            assertTrue(refreshed.any { it.status == IpoStatus.PRE_APPLY })
            assertTrue(refreshed.any { it.status == IpoStatus.LIVE })
            assertTrue(refreshed.any { it.status == IpoStatus.CLOSED })
            val preserved = refreshed.first { it.id == first.id }
            assertEquals(4, preserved.userLots)
            assertTrue(preserved.updatedAgoLabel.contains("Synced "))
        } finally {
            db.close()
        }
    }

    @Test
    fun `live gmp repository parses html tables investorgain json issue size and merges without duplicates`() {
        val gmpRepo = LiveGmpRepository(enableNetworkFetch = false)
        val ipoWatchHtml = """
            <table>
              <tr>
                <th>IPO Name</th>
                <th>Price</th>
                <th>GMP</th>
                <th>Date</th>
                <th>Type</th>
                <th>Status</th>
              </tr>
              <tr>
                <td><a href="#">Crystal Crop Protection IPO</a></td>
                <td>₹259-₹272</td>
                <td><b>₹64</b> (23.5%)</td>
                <td>17 Nov - 20 Nov</td>
                <td>Mainboard</td>
                <td>Open</td>
              </tr>
              <tr>
                <td>Watrana Rentals SME IPO</td>
                <td>₹245</td>
                <td>+₹29</td>
                <td>17 Nov - 23 Nov</td>
                <td>NSE SME</td>
                <td>Pre-Apply</td>
              </tr>
            </table>
        """.trimIndent()
        val chittorgarhHtml = """
            <table>
              <tr>
                <th>Company Name</th>
                <th>Open Date</th>
                <th>Close Date</th>
                <th>Listing Date</th>
                <th>Issue Price</th>
                <th>Issue Size (Cr)</th>
                <th>Lot Size</th>
              </tr>
              <tr>
                <td>Crystal Crop Protection Ltd</td>
                <td>17 Nov 2026</td>
                <td>20 Nov 2026</td>
                <td>25 Nov 2026</td>
                <td>₹272</td>
                <td>₹801.43 Cr</td>
                <td>55</td>
              </tr>
              <tr>
                <td>ACME Solar Holdings Ltd</td>
                <td>18 Nov 2026</td>
                <td>21 Nov 2026</td>
                <td>26 Nov 2026</td>
                <td>₹289</td>
                <td>₹2,900.00 Cr</td>
                <td>51</td>
              </tr>
            </table>
        """.trimIndent()

        val ipoWatchQuotes = gmpRepo.parseHtmlGmpTables(ipoWatchHtml, sourceName = "IPOWatch", priorityRank = 2)
        val chittorgarhQuotes = gmpRepo.parseHtmlGmpTables(chittorgarhHtml, sourceName = "Chittorgarh", priorityRank = 4)
        assertEquals(2, ipoWatchQuotes.size)
        assertEquals(2, chittorgarhQuotes.size)
        assertEquals(801.43, chittorgarhQuotes[0].issueSizeCr, 0.01)
        assertEquals(55, chittorgarhQuotes[0].lotSize)

        val json = """
            {
              "reportTableData": [
                {
                  "Name": "<b>PhonePe Ltd</b> <span class='badge'>Pre-Apply</span>",
                  "Price": "955",
                  "GMP": "&#8377;225 (23.6%)",
                  "Lot": "15",
                  "Issue Size": "₹12,500 Cr",
                  "Open": "24 Nov 2026",
                  "Close": "26 Nov 2026",
                  "~IPO_Category": "Mainboard"
                },
                {
                  "Name": "ACME Solar",
                  "Price": "275-289",
                  "GMP": "₹32",
                  "~IPO_Category": "Mainboard"
                }
              ]
            }
        """.trimIndent()
        val jsonQuotes = gmpRepo.parseInvestorGainJson(json, sourceName = "InvestorGain", priorityRank = 1)
        assertEquals(2, jsonQuotes.size)
        assertEquals(12_500.0, jsonQuotes[0].issueSizeCr, 0.01)

        val merged = gmpRepo.mergeQuotesWithoutDuplicates(ipoWatchQuotes + chittorgarhQuotes + jsonQuotes)
        // Expected unique IPOs: PhonePe, ACME Solar (merged), Crystal Crop Protection (merged), Watrana Rentals = 4
        assertEquals(4, merged.size)

        val crystalMerged = merged.first { it.companyName.contains("Crystal Crop", ignoreCase = true) }
        assertEquals(64, crystalMerged.gmpRupees)
        assertEquals(801.43, crystalMerged.issueSizeCr, 0.01)
        assertEquals(55, crystalMerged.lotSize)
        assertEquals("25 Nov 2026", crystalMerged.listingDate)

        val acmeMerged = merged.first { it.companyName.contains("ACME Solar", ignoreCase = true) }
        assertEquals(32, acmeMerged.gmpRupees)
        assertEquals(2_900.0, acmeMerged.issueSizeCr, 0.01)
        assertEquals(51, acmeMerged.lotSize)
    }

    @Test
    fun `repository replaces stale seed catalog when live multi-source snapshot arrives with zero repeats`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val gmpRepo = LiveGmpRepository(enableNetworkFetch = false)
            val repo = IpoRepository(ipoDao = db.ipoDao(), liveGmpRepository = gmpRepo)
            repo.ensureSeeded()

            val liveQuotes = gmpRepo.mergeQuotesWithoutDuplicates(
                listOf(
                    LiveGmpQuote(
                        companyName = "Zinka Logistics Solution Ltd",
                        normalizedKey = gmpRepo.normalizeCompanyKey("Zinka Logistics Solution Ltd"),
                        gmpRupees = 44,
                        priceLow = 259,
                        priceHigh = 273,
                        lotSize = 54,
                        issueSizeCr = 1114.72,
                        openDate = "13 Nov 2026",
                        closeDate = "18 Nov 2026",
                        listingDate = "21 Nov 2026",
                        boardType = BoardType.MAINBOARD,
                        status = IpoStatus.LIVE,
                        sourceName = "InvestorGain",
                        priorityRank = 1,
                    ),
                    LiveGmpQuote(
                        companyName = "Zinka Logistics",
                        normalizedKey = gmpRepo.normalizeCompanyKey("Zinka Logistics"),
                        gmpRupees = 40,
                        priceLow = 259,
                        priceHigh = 273,
                        boardType = BoardType.MAINBOARD,
                        status = IpoStatus.LIVE,
                        sourceName = "IPOWatch",
                        priorityRank = 2,
                    ),
                    LiveGmpQuote(
                        companyName = "Enviro Infra Engineers Ltd",
                        normalizedKey = gmpRepo.normalizeCompanyKey("Enviro Infra Engineers Ltd"),
                        gmpRupees = 52,
                        priceLow = 140,
                        priceHigh = 148,
                        lotSize = 101,
                        issueSizeCr = 650.43,
                        openDate = "22 Nov 2026",
                        closeDate = "26 Nov 2026",
                        listingDate = "29 Nov 2026",
                        boardType = BoardType.MAINBOARD,
                        status = IpoStatus.PRE_APPLY,
                        sourceName = "InvestorGain",
                        priorityRank = 1,
                    ),
                    LiveGmpQuote(
                        companyName = "Enviro Infra Engineers",
                        normalizedKey = gmpRepo.normalizeCompanyKey("Enviro Infra Engineers"),
                        gmpRupees = 50,
                        priceLow = 140,
                        priceHigh = 148,
                        lotSize = 101,
                        issueSizeCr = 650.43,
                        openDate = "22 Nov 2026",
                        closeDate = "26 Nov 2026",
                        boardType = BoardType.MAINBOARD,
                        status = IpoStatus.PRE_APPLY,
                        sourceName = "Chittorgarh",
                        priorityRank = 4,
                    ),
                    LiveGmpQuote(
                        companyName = "C2C Advanced Systems Ltd",
                        normalizedKey = gmpRepo.normalizeCompanyKey("C2C Advanced Systems Ltd"),
                        gmpRupees = 245,
                        priceLow = 214,
                        priceHigh = 226,
                        lotSize = 600,
                        issueSizeCr = 99.07,
                        openDate = "22 Nov 2026",
                        closeDate = "26 Nov 2026",
                        boardType = BoardType.SME,
                        status = IpoStatus.PRE_APPLY,
                        sourceName = "Chittorgarh",
                        priorityRank = 4,
                    ),
                    LiveGmpQuote(
                        companyName = "C2C Advanced Systems",
                        normalizedKey = gmpRepo.normalizeCompanyKey("C2C Advanced Systems"),
                        gmpRupees = 240,
                        priceLow = 214,
                        priceHigh = 226,
                        lotSize = 600,
                        issueSizeCr = 99.07,
                        openDate = "22 Nov 2026",
                        closeDate = "26 Nov 2026",
                        boardType = BoardType.SME,
                        status = IpoStatus.PRE_APPLY,
                        sourceName = "IPOWatch",
                        priorityRank = 2,
                    ),
                ),
            )

            repo.applySnapshotToDatabase(
                existingById = db.ipoDao().getAllSnapshot().associateBy { it.id },
                gmpSnapshot = LiveGmpSnapshot(
                    quotesByKey = liveQuotes.associateBy { it.normalizedKey },
                    allQuotes = liveQuotes,
                    activeSources = listOf("InvestorGain", "IPOWatch", "Chittorgarh"),
                    syncedAtTime = "10:15 AM",
                ),
            )

            val saved = repo.observeAllIpos().first()
            assertEquals(3, saved.size)
            assertEquals(1, saved.count { it.companyName.contains("Zinka", ignoreCase = true) })
            assertEquals(1114.72, saved.first { it.companyName.contains("Zinka", ignoreCase = true) }.issueSizeCr, 0.01)
            assertFalse(saved.any { it.id == "crystal-crop-protection" })
        } finally {
            db.close()
        }
    }

    @Test
    fun `news repository parses live rss xml and links matching ipos`() = runTest {
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>PhonePe IPO GMP jumps ahead of Mainboard opening - Economic Times</title>
                  <link>https://economictimes.indiatimes.com/markets/ipos/phonepe-gmp</link>
                  <description>PhonePe Ltd sets price band for Rs 12,500 Cr issue with strong GMP.</description>
                  <pubDate>Mon, 17 Nov 2026 09:30:00 GMT</pubDate>
                  <source>Google News India IPO</source>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val newsRepo = IpoNewsRepository(enableNetworkFetch = false)
        val parsed = newsRepo.parseRssXml(
            xml = rssXml,
            defaultSource = "Google News India IPO",
            requireIpoKeyword = false,
            catalog = IpoSeedData.createInitialCatalog(),
        )
        assertEquals(1, parsed.size)
        val article = parsed.first()
        assertEquals("PhonePe IPO GMP jumps ahead of Mainboard opening", article.title)
        assertEquals("Economic Times", article.source)
        assertEquals("phonepe-india", article.linkedIpoId)
        assertEquals("https://economictimes.indiatimes.com/markets/ipos/phonepe-gmp", article.articleUrl)
    }

    @Test
    fun `ipo card displays company name listing date issue size status badge and share button`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        var sharedClicked = false
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoCard(
                    ipo = sample,
                    onClick = {},
                    onShareClick = { sharedClicked = true },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(sample.companyName).assertIsDisplayed()
        composeTestRule.onNodeWithTag(
            testTag = "company_logo_${sample.shortCode}",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Listing: ${sample.listingDate}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Issue Size").assertIsDisplayed()
        composeTestRule.onNodeWithText("OPEN").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_btn_${sample.id}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("share_btn_${sample.id}").assertIsDisplayed().performClick()
        assertTrue(sharedClicked)
    }

    @Test
    fun `ipo card displays pre-apply status badge`() {
        val catalog = IpoSeedData.createInitialCatalog()
        val preApplyIpo = catalog.first { it.status == IpoStatus.PRE_APPLY }
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoCard(
                    ipo = preApplyIpo,
                    onClick = {},
                    onShareClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("PRE-APPLY").assertIsDisplayed()
    }

    @Test
    fun `homepage replaces watchlist with share feature and switches bottom bar filters`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = IpoRepository(
            ipoDao = db.ipoDao(),
            liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
        )
        val prefs = UserPreferencesRepository(context)
        val viewModel = IpoBoardViewModel(
            ipoRepository = repo,
            preferencesRepository = prefs,
            newsRepository = IpoNewsRepository(enableNetworkFetch = false),
        )

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState().value
            IpoPulseTheme {
                AppNavigation(
                    boardViewModel = viewModel,
                    uiState = uiState,
                    ipoRepository = repo,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("opening_brand_logo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hero_brand_logo").assertDoesNotExist()
        composeTestRule.onNodeWithTag("open_watchlist_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("share_board_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("refresh_gmp_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ipo_lazy_column")
            .performScrollToNode(hasTestTag("status_chip_pre_apply"))
        composeTestRule.onNodeWithTag("status_chip_pre_apply").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.filteredIpos.all { it.status == IpoStatus.PRE_APPLY })
        composeTestRule.onNodeWithTag("status_chip_all").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("bottom_bar_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_bar_mainboard").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(BoardFilter.MAINBOARD, viewModel.uiState.value.boardFilter)

        composeTestRule.onNodeWithTag("bottom_bar_sme").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(BoardFilter.SME, viewModel.uiState.value.boardFilter)

        composeTestRule.onNodeWithTag("bottom_bar_all").performClick()
        composeTestRule.waitForIdle()
        assertEquals(BoardFilter.ALL, viewModel.uiState.value.boardFilter)

        composeTestRule.onNodeWithTag("share_board_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("share_ipo_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_copy_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Copied").performScrollTo().assertIsDisplayed()
        db.close()
    }

    @Test
    fun `clicking ipo card opens detail screen with description subscription and financials`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = IpoRepository(
            ipoDao = db.ipoDao(),
            liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
        )
        val prefs = UserPreferencesRepository(context)
        val viewModel = IpoBoardViewModel(
            ipoRepository = repo,
            preferencesRepository = prefs,
            newsRepository = IpoNewsRepository(enableNetworkFetch = false),
        )
        val firstIpo = IpoSeedData.createInitialCatalog().first()

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState().value
            IpoPulseTheme {
                AppNavigation(
                    boardViewModel = viewModel,
                    uiState = uiState,
                    ipoRepository = repo,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.filteredIpos.any { it.id == firstIpo.id }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("ipo_lazy_column")
            .performScrollToNode(hasTestTag("ipo_card_${firstIpo.id}"))
        composeTestRule.onNodeWithTag("ipo_card_${firstIpo.id}").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(firstIpo.companyName).assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_watchlist_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Business Overview & Objects"))
        composeTestRule.onNodeWithText("Business Overview & Objects").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Category-Wise Subscription Status"))
        composeTestRule.onNodeWithText("Category-Wise Subscription Status").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Restated Financial Report (₹ in Crores)"))
        composeTestRule.onNodeWithText("Restated Financial Report (₹ in Crores)").assertIsDisplayed()
        db.close()
    }

    @Test
    fun `detail screen displays comprehensive financial data description and subscription`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoDetailScreen(
                    ipo = sample,
                    selectedTab = DetailTab.ALL,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(sample.companyName).assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_refresh_gmp_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_watchlist_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("company_banner_image").assertIsDisplayed()
        composeTestRule.onNodeWithTag("company_logo_${sample.shortCode}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Business Overview & Objects"))
        composeTestRule.onNodeWithText("Business Overview & Objects").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Category-Wise Subscription Status"))
        composeTestRule.onNodeWithText("Category-Wise Subscription Status").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("3-Year Financial Performance (₹ Cr)"))
        composeTestRule.onNodeWithText("3-Year Financial Performance (₹ Cr)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Key Valuation Ratios"))
        composeTestRule.onNodeWithText("Key Valuation Ratios").assertIsDisplayed()
    }

    @Test
    fun `news tab in bottom bar opens upcoming ipo news feed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = IpoRepository(
            ipoDao = db.ipoDao(),
            liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
        )
        val prefs = UserPreferencesRepository(context)
        val viewModel = IpoBoardViewModel(
            ipoRepository = repo,
            preferencesRepository = prefs,
            newsRepository = IpoNewsRepository(enableNetworkFetch = false),
        )

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState().value
            IpoPulseTheme {
                AppNavigation(
                    boardViewModel = viewModel,
                    uiState = uiState,
                    ipoRepository = repo,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("bottom_bar_news").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("IPO News").assertIsDisplayed()
        composeTestRule.onNodeWithTag("featured_news_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("news_filter_sme_buzz").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Watrana Rentals SME IPO Opens for ₹120.10 Cr Issue at ₹232–₹245 Band")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("news_lazy_column")
            .performScrollToNode(hasText("Vedant Poly Fabs SME IPO Opens Nov 21 at Attractive 15.1x P/E"))
        composeTestRule.onNodeWithText("Vedant Poly Fabs SME IPO Opens Nov 21 at Attractive 15.1x P/E")
            .assertIsDisplayed()
        db.close()
    }

    @Test
    fun `ipo entity computes retail s-hni and big hni lot sizes and total amounts accurately`() {
        val catalog = IpoSeedData.createInitialCatalog()
        val mainboard = catalog.first { it.boardType == BoardType.MAINBOARD }
        assertEquals(13, mainboard.retailMaxLots)
        assertEquals(14, mainboard.sHniMinLots)
        assertEquals(66, mainboard.sHniMaxLots)
        assertEquals(67, mainboard.bHniMinLots)
        assertEquals(5, mainboard.categoryLotTiers.size)
        assertTrue(mainboard.categoryLotTiers.first { it.id == "retail_max" }.totalAmount <= 200_000)
        assertTrue(mainboard.categoryLotTiers.first { it.id == "shni_min" }.totalAmount > 200_000)
        assertTrue(mainboard.categoryLotTiers.first { it.id == "shni_max" }.totalAmount <= 1_000_000)
        assertTrue(mainboard.categoryLotTiers.first { it.id == "bhni_min" }.totalAmount > 1_000_000)

        val sme = catalog.first { it.boardType == BoardType.SME }
        assertEquals(1, sme.retailMaxLots)
        assertEquals(2, sme.sHniMinLots)
        assertTrue(sme.categoryLotTiers.first { it.id == "bhni_min" }.totalAmount > 1_000_000)
    }

    @Test
    fun `detail screen displays retail s-hni and big hni lot sizes and total amounts`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        var selectedLots = sample.userLots
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoDetailScreen(
                    ipo = sample.copy(userLots = selectedLots),
                    selectedTab = DetailTab.ALL,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = { selectedLots = it },
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Retail, S-HNI & Big HNI Lot Size"))
        composeTestRule.onNodeWithText("Retail, S-HNI & Big HNI Lot Size").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retail (Min)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Retail (Max)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("S-HNI (Min)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("S-HNI (Max)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Big HNI (Min)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("lot_tier_bhni_min").performClick()
        assertEquals(sample.bHniMinLots, selectedLots)
    }

    @Test
    fun `buildIpoShareMessage formats offer dates gmp and subscription details`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        val message = buildIpoShareMessage(sample)
        assertTrue(message.contains(sample.companyName))
        assertTrue(message.contains(sample.openDate))
        assertTrue(message.contains(sample.closeDate))
        assertTrue(message.contains(sample.listingDate))
        assertTrue(message.contains("₹${sample.priceLow}–₹${sample.priceHigh}"))
        assertTrue(message.contains("Latest GMP:"))
        assertTrue(message.contains("Est. Listing"))
    }

    @Test
    fun `detail screen share button opens social share sheet and copies summary`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoDetailScreen(
                    ipo = sample,
                    selectedTab = DetailTab.ALL,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hero_share_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("share_ipo_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_preview_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_whatsapp_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_telegram_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_x_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_copy_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Copied").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_system_chooser_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `homepage and detail screens display IPO terminology instead of issues`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = IpoRepository(
            ipoDao = db.ipoDao(),
            liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
        )
        val prefs = UserPreferencesRepository(context)
        val viewModel = IpoBoardViewModel(
            ipoRepository = repo,
            preferencesRepository = prefs,
            newsRepository = IpoNewsRepository(enableNetworkFetch = false),
        )

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState().value
            IpoPulseTheme {
                AppNavigation(
                    boardViewModel = viewModel,
                    uiState = uiState,
                    ipoRepository = repo,
                )
            }
        }
        val expectedActiveCount = IpoSeedData.createInitialCatalog().count {
            !IpoDateUtils.isCloseDatePassed(closeDate = it.closeDate, status = it.status)
        }
        val expectedLabel = if (expectedActiveCount == 1) "All • 1 IPO" else "All • $expectedActiveCount IPOs"

        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasText(expectedLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(expectedLabel).assertIsDisplayed()
        composeTestRule.onNodeWithTag("ipo_lazy_column")
            .performScrollToNode(hasTestTag("status_chip_all"))
        composeTestRule.onNodeWithText("All IPOs").assertIsDisplayed()
        db.close()
    }

    @Test
    fun `notification evaluation alerts for new ipo added and upcoming ipo opening`() {
        val baseCatalog = IpoSeedData.createInitialCatalog()
        val firstPreApply = baseCatalog.first { it.status == IpoStatus.PRE_APPLY }

        // First baseline pass does not spam new IPO notifications
        val baseline = IpoNotificationManager.evaluateNotificationEvents(
            previousIpos = emptyList(),
            updatedIpos = baseCatalog,
            knownIpoIds = emptySet(),
            notifiedOpenedIds = emptySet(),
            hasSeededBaseline = false,
            notifyNewIpoEnabled = true,
            notifyOpenIpoEnabled = true,
        )
        assertTrue(baseline.newIposToAlert.isEmpty())
        assertTrue(baseline.openedInterestedIposToAlert.isEmpty())

        // Now simulate a new IPO arriving + the PRE_APPLY IPO opening for subscription
        val newlyAddedIpo = baseCatalog.first().copy(
            id = "fresh-ipo-alert-test",
            companyName = "Fresh Grid Power Ltd",
            shortCode = "FGP",
            status = IpoStatus.PRE_APPLY,
        )
        val openedUpcoming = firstPreApply.copy(status = IpoStatus.LIVE)
        val nextCatalog = baseCatalog.map {
            if (it.id == openedUpcoming.id) openedUpcoming else it
        } + newlyAddedIpo

        val evaluation = IpoNotificationManager.evaluateNotificationEvents(
            previousIpos = baseCatalog,
            updatedIpos = nextCatalog,
            knownIpoIds = baseline.updatedKnownIds,
            notifiedOpenedIds = baseline.updatedOpenedIds,
            hasSeededBaseline = true,
            notifyNewIpoEnabled = true,
            notifyOpenIpoEnabled = true,
        )

        assertEquals(listOf("fresh-ipo-alert-test"), evaluation.newIposToAlert.map { it.id })
        assertEquals(listOf(openedUpcoming.id), evaluation.openedInterestedIposToAlert.map { it.id })

        // Subsequent sync with unchanged catalog does not duplicate alerts
        val repeatEvaluation = IpoNotificationManager.evaluateNotificationEvents(
            previousIpos = nextCatalog,
            updatedIpos = nextCatalog,
            knownIpoIds = evaluation.updatedKnownIds,
            notifiedOpenedIds = evaluation.updatedOpenedIds,
            hasSeededBaseline = true,
            notifyNewIpoEnabled = true,
            notifyOpenIpoEnabled = true,
        )
        assertTrue(repeatEvaluation.newIposToAlert.isEmpty())
        assertTrue(repeatEvaluation.openedInterestedIposToAlert.isEmpty())
    }

    @Test
    fun `notification manager posts android notifications with deep link extra`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = Shadows.shadowOf(notificationManager)
        notificationManager.cancelAll()

        val sample = IpoSeedData.createInitialCatalog().first()
        IpoNotificationManager.postNewIpoNotification(context, sample)
        assertEquals(0, shadowNotificationManager.allNotifications.size)

        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        IpoNotificationManager.postNewIpoNotification(context, sample)
        IpoNotificationManager.postSubscriptionOpenNotification(context, sample)

        val posted = shadowNotificationManager.allNotifications
        assertEquals(2, posted.size)
        val titles = posted.map { it.extras.getString("android.title").orEmpty() }
        assertTrue(titles.any { it.contains("New IPO Added:") })
        assertTrue(titles.any { it.contains("IPO Open Now:") })
    }

    @Test
    fun `settings bottom sheet displays and toggles local ipo alert switches`() {
        var notifyNew = true
        var notifyOpen = true
        composeTestRule.setContent {
            IpoPulseTheme {
                SettingsBottomSheet(
                    currentTheme = ThemePreference.SYSTEM,
                    adImpressionCount = 3,
                    onSelectTheme = {},
                    onDismiss = {},
                    notifyNewIpoEnabled = notifyNew,
                    notifyOpenIpoEnabled = notifyOpen,
                    hasNotificationPermission = true,
                    onToggleNotifyNewIpo = { notifyNew = it },
                    onToggleNotifyOpenIpo = { notifyOpen = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("notification_settings_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("switch_notify_new_ipo").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("switch_notify_open_ipo").performScrollTo().performClick()
        assertFalse(notifyNew)
        assertFalse(notifyOpen)
    }

    @Test
    fun `subscription tab displays application wise breakup box with reserved applied and odds`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        val breakup = sample.applicationWiseBreakup
        assertEquals(3, breakup.size)
        assertTrue(breakup.all { it.reservedApplications > 0L && it.receivedApplications > 0L })

        composeTestRule.setContent {
            IpoPulseTheme {
                IpoDetailScreen(
                    ipo = sample,
                    selectedTab = DetailTab.SUBSCRIPTION,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("application_breakup_card"))
        composeTestRule.onNodeWithTag("application_breakup_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Application-Wise Breakup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reserved Apps").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total Applied").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retail Odds").assertIsDisplayed()
        composeTestRule.onNodeWithTag("breakup_row_retail").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("breakup_row_shni").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("breakup_row_bhni").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `market pulse banner shows only last sync date time and homepage excludes past close dates`() {
        val refCal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 17, 10, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val refMs = refCal.timeInMillis
        assertTrue(IpoDateUtils.isCloseDatePassed("13 Nov 2026", IpoStatus.CLOSED, refMs))
        assertTrue(IpoDateUtils.isCloseDatePassed("14 Nov 2026", IpoStatus.CLOSED, refMs))
        assertTrue(IpoDateUtils.isCloseDatePassed("15 Nov 2026", IpoStatus.CLOSED, refMs))
        assertFalse(IpoDateUtils.isCloseDatePassed("17 Nov 2026", IpoStatus.LIVE, refMs))
        assertFalse(IpoDateUtils.isCloseDatePassed("20 Nov 2026", IpoStatus.LIVE, refMs))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = IpoRepository(
            ipoDao = db.ipoDao(),
            liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
        )
        val prefs = UserPreferencesRepository(context)
        val viewModel = IpoBoardViewModel(
            ipoRepository = repo,
            preferencesRepository = prefs,
            newsRepository = IpoNewsRepository(enableNetworkFetch = false),
        )

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState().value
            IpoPulseTheme {
                AppNavigation(
                    boardViewModel = viewModel,
                    uiState = uiState,
                    ipoRepository = repo,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodes(hasTestTag("ipo_card_crystal-crop-protection"))
                .fetchSemanticsNodes().isNotEmpty() &&
                viewModel.uiState.value.lastSyncedLabel != "Syncing..."
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("market_pulse_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Last Sync", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("last_sync_datetime", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNode(
            hasAnyAncestor(hasTestTag("market_pulse_banner")) and hasText("Open", substring = true, ignoreCase = true),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        composeTestRule.onNode(
            hasAnyAncestor(hasTestTag("market_pulse_banner")) and hasText("Pre-Apply", substring = true, ignoreCase = true),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        composeTestRule.onNode(
            hasAnyAncestor(hasTestTag("market_pulse_banner")) and hasText("Upcoming", substring = true, ignoreCase = true),
            useUnmergedTree = true,
        ).assertDoesNotExist()

        val state = viewModel.uiState.value
        assertEquals(12, state.allIpos.size)
        assertTrue(
            state.filteredIpos.none {
                IpoDateUtils.isCloseDatePassed(closeDate = it.closeDate, status = it.status)
            },
        )
        assertTrue(state.filteredIpos.any { it.id == "crystal-crop-protection" })
        assertFalse(state.filteredIpos.any { it.id == "ntpc-green-energy" })
        assertFalse(state.filteredIpos.any { it.id == "ather-energy" })
        assertFalse(state.filteredIpos.any { it.id == "il-jin-electronics-sme" })

        composeTestRule.onNodeWithTag("ipo_card_crystal-crop-protection").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ipo_card_ntpc-green-energy").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ipo_card_ather-energy").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ipo_card_il-jin-electronics-sme").assertDoesNotExist()
        db.close()
    }

    @Test
    fun `ad banner hides when no ad is loaded and expands on ad load with auto retry on failure`() {
        var capturedListener: AdListener? = null
        var loadRequests = 0
        var impressions = 0

        composeTestRule.setContent {
            IpoPulseTheme {
                AdBannerSlot(
                    onAdImpression = { impressions++ },
                    onListenerReadyForTest = { capturedListener = it },
                    onLoadRequestedForTest = { loadRequests++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        // No placeholder text should ever be displayed while hidden
        composeTestRule.onNodeWithText("AdMob Banner Placement", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Loading Sponsored Ad...", useUnmergedTree = true).assertDoesNotExist()

        // Hidden at 0 height before an ad loads
        val initialHeight = composeTestRule.onNodeWithTag("ad_banner_container")
            .fetchSemanticsNode().size.height
        assertEquals(0, initialHeight)
        assertTrue(loadRequests >= 1)

        val listener = requireNotNull(capturedListener)
        val countBeforeFailure = loadRequests
        listener.onAdFailedToLoad(LoadAdError(3, "No fill", "com.google.android.gms.ads", null, null))
        composeTestRule.waitForIdle()

        // Still hidden at 0 height after failure
        val failedHeight = composeTestRule.onNodeWithTag("ad_banner_container")
            .fetchSemanticsNode().size.height
        assertEquals(0, failedHeight)
        assertTrue(AdMobManager.lastAdStatus.value.contains("Auto-retry #1"))

        // Advance Compose clock & looper past first retry backoff (4s) and confirm automatic refresh fires
        composeTestRule.mainClock.advanceTimeBy(4_500L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(
            4_500L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        composeTestRule.waitForIdle()
        assertTrue(loadRequests > countBeforeFailure)

        // When an ad loads, container smoothly expands to non-zero height and records impressions
        listener.onAdLoaded()
        composeTestRule.mainClock.advanceTimeBy(400L)
        composeTestRule.waitForIdle()
        val loadedHeight = composeTestRule.onNodeWithTag("ad_banner_container")
            .fetchSemanticsNode().size.height
        assertTrue(loadedHeight > 0)
        assertTrue(AdMobManager.lastAdStatus.value.contains("Active"))

        listener.onAdImpression()
        assertEquals(1, impressions)
    }

    @Test
    fun `admob manager retry delays and interstitial eligibility work as expected`() {
        assertEquals(4_000L, AdMobManager.computeBannerRetryDelayMs(1))
        assertEquals(8_000L, AdMobManager.computeBannerRetryDelayMs(2))
        assertEquals(15_000L, AdMobManager.computeBannerRetryDelayMs(3))
        assertEquals(30_000L, AdMobManager.computeBannerRetryDelayMs(4))

        assertFalse(AdMobManager.shouldShowInterstitialForOpen(detailOpenCount = 1, lastShownOpenCount = 0, lastShownTimeMs = 0L, nowMs = 100_000L))
        assertTrue(AdMobManager.shouldShowInterstitialForOpen(detailOpenCount = 2, lastShownOpenCount = 0, lastShownTimeMs = 0L, nowMs = 100_000L))
        assertFalse(AdMobManager.shouldShowInterstitialForOpen(detailOpenCount = 3, lastShownOpenCount = 2, lastShownTimeMs = 100_000L, nowMs = 120_000L))
        assertFalse(AdMobManager.shouldShowInterstitialForOpen(detailOpenCount = 4, lastShownOpenCount = 2, lastShownTimeMs = 100_000L, nowMs = 120_000L))
        assertTrue(AdMobManager.shouldShowInterstitialForOpen(detailOpenCount = 4, lastShownOpenCount = 2, lastShownTimeMs = 100_000L, nowMs = 150_000L))
    }

    @Test
    fun `redesigned homepage card and segmented Current and Upcoming vs Listed toggle work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = IpoRepository(
            ipoDao = db.ipoDao(),
            liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
        )
        val prefs = UserPreferencesRepository(context)
        val viewModel = IpoBoardViewModel(
            ipoRepository = repo,
            preferencesRepository = prefs,
            newsRepository = IpoNewsRepository(enableNetworkFetch = false),
        )

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState().value
            IpoPulseTheme {
                AppNavigation(
                    boardViewModel = viewModel,
                    uiState = uiState,
                    ipoRepository = repo,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodes(hasTestTag("ipo_card_crystal-crop-protection"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()

        // Segmented toggle is visible and defaults to Current & Upcoming
        composeTestRule.onNodeWithTag("timeline_tab_current_upcoming").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timeline_tab_listed").assertIsDisplayed()
        assertEquals(BoardTimelineTab.CURRENT_UPCOMING, viewModel.uiState.value.timelineTab)

        // Scroll the first card fully into view and verify redesigned 3-column metrics & View IPO CTA
        composeTestRule.onNodeWithTag("ipo_lazy_column")
            .performScrollToNode(hasTestTag("ipo_card_crystal-crop-protection"))
        composeTestRule.onNodeWithText("Offer Price", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Lot Size", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Subs", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Exp. Premium", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Issue Size", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("card_cta_crystal-crop-protection")
            .performScrollTo()
            .assertIsDisplayed()

        // Tapping Listed switches to closed/listed IPOs
        composeTestRule.onNodeWithTag("timeline_tab_listed").performClick()
        composeTestRule.waitForIdle()
        assertEquals(BoardTimelineTab.LISTED, viewModel.uiState.value.timelineTab)
        assertTrue(viewModel.uiState.value.filteredIpos.any { it.id == "ntpc-green-energy" })
        assertFalse(viewModel.uiState.value.filteredIpos.any { it.id == "crystal-crop-protection" })

        // Tapping Current & Upcoming restores active IPO list
        composeTestRule.onNodeWithTag("timeline_tab_current_upcoming").performClick()
        composeTestRule.waitForIdle()
        assertEquals(BoardTimelineTab.CURRENT_UPCOMING, viewModel.uiState.value.timelineTab)
        assertTrue(viewModel.uiState.value.filteredIpos.any { it.id == "crystal-crop-protection" })
        db.close()
    }

    @Test
    fun `app open splash page displays branding triggers app open ad and transitions to homepage`() {
        val now = 20_000_000L
        assertTrue(AdMobManager.isAppOpenAdFresh(loadedAtMs = now - 60_000L, nowMs = now))
        assertFalse(AdMobManager.isAppOpenAdFresh(loadedAtMs = now - (4L * 60L * 60L * 1000L + 1_000L), nowMs = now))

        val readyFlow = MutableStateFlow(false)
        var adShowRequested = false
        var impressions = 0
        var finished = false

        composeTestRule.setContent {
            IpoPulseTheme {
                AppOpenSplashScreen(
                    onFinished = { finished = true },
                    onAdImpression = { impressions++ },
                    minimumDisplayMs = 500L,
                    adWaitTimeoutMs = 1_500L,
                    appOpenReadyFlow = readyFlow,
                    useDefaultAdMobShow = false,
                    onShowAppOpenAd = { onImpression, onDone ->
                        adShowRequested = true
                        onImpression()
                        onDone()
                        true
                    },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("app_open_splash_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("splash_brand_logo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("splash_loading_bar").assertIsDisplayed()
        assertFalse(finished)

        // Mark App Open Ad ready and advance past minimum splash branding duration
        readyFlow.value = true
        composeTestRule.mainClock.advanceTimeBy(700L)
        composeTestRule.waitForIdle()

        assertTrue(adShowRequested)
        assertEquals(1, impressions)
        assertTrue(finished)
    }

    @Test
    fun `app open splash page transitions smoothly to homepage when no ad fills before timeout`() {
        val readyFlow = MutableStateFlow(false)
        var finished = false

        composeTestRule.setContent {
            IpoPulseTheme {
                AppOpenSplashScreen(
                    onFinished = { finished = true },
                    minimumDisplayMs = 400L,
                    adWaitTimeoutMs = 1_000L,
                    appOpenReadyFlow = readyFlow,
                    useDefaultAdMobShow = false,
                    onShowAppOpenAd = { _, _ -> false },
                )
            }
        }
        composeTestRule.waitForIdle()
        assertFalse(finished)

        composeTestRule.mainClock.advanceTimeBy(1_300L)
        composeTestRule.waitForIdle()
        assertTrue(finished)
    }

    @Test
    fun `ipo detail page shows schedule dates at beginning with redesigned sections and hidden middle ad when unfilled`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        assertTrue(sample.reservationCategoryBreakdown.isNotEmpty())
        assertTrue(sample.reservationCategoryBreakdown.any { it.category == "Retail" && it.maxAllottees != null })

        composeTestRule.setContent {
            IpoPulseTheme {
                IpoDetailScreen(
                    ipo = sample,
                    selectedTab = DetailTab.ALL,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Hero card and IPO Schedule Dates card appear right at the beginning
        composeTestRule.onNodeWithTag("detail_hero_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("ipo_schedule_card"))
        val heroBounds = composeTestRule.onNodeWithTag("detail_hero_card").fetchSemanticsNode().boundsInRoot
        val scheduleBounds = composeTestRule.onNodeWithTag("ipo_schedule_card").fetchSemanticsNode().boundsInRoot
        assertTrue(scheduleBounds.top >= heroBounds.bottom - 4f)
        composeTestRule.onNodeWithText("IPO Schedule").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
        composeTestRule.onNodeWithText("Close").assertIsDisplayed()
        composeTestRule.onNodeWithText("Allotment*").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listing*").assertIsDisplayed()

        // 2. Live Subscription & Expected Premium cards
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("live_subscription_card"))
        composeTestRule.onNodeWithText("Live Subscription").assertIsDisplayed()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("expected_premium_card"))
        composeTestRule.onNodeWithText("Expected Premium").assertIsDisplayed()

        // 3. Applications Reserved + Middle Mobile Banner Ad (collapsed to 0px when no ad loaded)
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("applications_reserved_card"))
        composeTestRule.onNodeWithText("Applications Reserved").assertIsDisplayed()
        val middleAdBounds = composeTestRule
            .onNodeWithTag("detail_middle_banner_container")
            .fetchSemanticsNode()
            .boundsInRoot
        assertEquals(0, middleAdBounds.height.toInt())

        // 4. Reservation Category Wise, Know Before Investing (Strengths | Risks), Registrar & Disclaimer
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("reservation_category_card"))
        composeTestRule.onNodeWithText("Reservation Category Wise").assertIsDisplayed()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("know_before_investing_card"))
        composeTestRule.onNodeWithText("Know Before Investing").assertIsDisplayed()
        composeTestRule.onNodeWithTag("kbi_tab_risks").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(sample.risks.first()).assertIsDisplayed()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("ipo_disclaimer_card"))
        composeTestRule.onNodeWithText("Disclaimer").assertIsDisplayed()
    }

    @Test
    fun `pressing back on homepage shows exit confirmation dialog and handles stay and exit`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = IpoRepository(
            ipoDao = db.ipoDao(),
            liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
        )
        val prefs = UserPreferencesRepository(context)
        val viewModel = IpoBoardViewModel(
            ipoRepository = repo,
            preferencesRepository = prefs,
            newsRepository = IpoNewsRepository(enableNetworkFetch = false),
        )
        var exitConfirmed = false
        var triggerBack: (() -> Unit)? = null

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState().value
            val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            triggerBack = { dispatcher?.onBackPressed() }
            IpoPulseTheme {
                AppNavigation(
                    boardViewModel = viewModel,
                    uiState = uiState,
                    ipoRepository = repo,
                    onExitApp = { exitConfirmed = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Press back on root homepage -> Exit dialog opens
        composeTestRule.runOnUiThread { triggerBack?.invoke() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("exit_app_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exit IPO Guruji?").assertIsDisplayed()
        assertFalse(exitConfirmed)

        // Tap Stay -> dialog dismisses without exiting
        composeTestRule.onNodeWithTag("exit_dialog_stay_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("exit_app_dialog").assertDoesNotExist()
        assertFalse(exitConfirmed)

        // Press back again and tap Exit App -> confirms exit
        composeTestRule.runOnUiThread { triggerBack?.invoke() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("exit_app_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("exit_dialog_confirm_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(exitConfirmed)
        db.close()
    }

    @Test
    fun `listed ipos from live sources are parsed and preserved alongside active ipos for the Listed tab`() = runTest {
        val gmpRepo = LiveGmpRepository(enableNetworkFetch = false)

        // 1. InvestorGain Listed JSON with L@ listing badge, confirmed Lot Size, and blank/dash GMP
        val listedJson = """
            {
              "reportTableData": [
                {
                  "Name": "<b>Swiggy Ltd</b> <span class='badge'>L@420</span>",
                  "Price": "390",
                  "GMP": "--",
                  "Lot": "38",
                  "Issue Size": "₹11,327 Cr",
                  "Close": "08-Nov-2026",
                  "Listing": "13-Nov-2026",
                  "~IPO_Category": "Mainboard"
                }
              ]
            }
        """.trimIndent()
        val parsedJsonListed = gmpRepo.parseInvestorGainJson(
            rawJson = listedJson,
            sourceName = "InvestorGain Listed",
            priorityRank = 8,
            defaultStatus = IpoStatus.CLOSED,
        )
        assertEquals(1, parsedJsonListed.size)
        assertEquals(IpoStatus.CLOSED, parsedJsonListed[0].status)
        assertEquals(390, parsedJsonListed[0].priceHigh)
        assertEquals(30, parsedJsonListed[0].gmpRupees) // 420 - 390 listing gain

        // 2. Chittorgarh Listed IPO Performance HTML table without GMP column (with confirmed Lot Size)
        val chittorgarhListedHtml = """
            <table>
              <tr>
                <th>Company Name</th>
                <th>Listing Date</th>
                <th>Issue Price</th>
                <th>Listing Price</th>
                <th>Lot Size</th>
                <th>Issue Size (Cr)</th>
              </tr>
              <tr>
                <td>Sagility India Ltd</td>
                <td>12 Nov 2026</td>
                <td>₹30</td>
                <td>₹31.06</td>
                <td>500</td>
                <td>₹2,106.60 Cr</td>
              </tr>
              <tr>
                <td>ACME Solar Holdings Ltd</td>
                <td>13 Nov 2026</td>
                <td>₹289</td>
                <td>₹259</td>
                <td>51</td>
                <td>₹2,900.00 Cr</td>
              </tr>
            </table>
        """.trimIndent()
        val parsedHtmlListed = gmpRepo.parseHtmlGmpTables(
            html = chittorgarhListedHtml,
            sourceName = "Chittorgarh Listed",
            priorityRank = 10,
            defaultStatus = IpoStatus.CLOSED,
        )
        assertEquals(2, parsedHtmlListed.size)
        assertTrue(parsedHtmlListed.all { it.status == IpoStatus.CLOSED })
        assertEquals("12 Nov 2026", parsedHtmlListed[0].listingDate)

        // Independent corroborating source records for Listed IPOs (multi-source verification)
        val ipoWatchListedCorroboration = listOf(
            LiveGmpQuote(
                companyName = "Swiggy Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Swiggy Ltd"),
                gmpRupees = 30,
                priceLow = 390,
                priceHigh = 390,
                lotSize = 38,
                issueSizeCr = 11327.0,
                listingDate = "13 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.CLOSED,
                sourceName = "IPOWatch Listed",
                priorityRank = 12,
            ),
            LiveGmpQuote(
                companyName = "Sagility India Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Sagility India Ltd"),
                gmpRupees = 1,
                priceLow = 30,
                priceHigh = 30,
                lotSize = 500,
                issueSizeCr = 2106.60,
                listingDate = "12 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.CLOSED,
                sourceName = "IPOWatch Listed",
                priorityRank = 12,
            ),
            LiveGmpQuote(
                companyName = "ACME Solar Holdings Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("ACME Solar Holdings Ltd"),
                gmpRupees = -30,
                priceLow = 289,
                priceHigh = 289,
                lotSize = 51,
                issueSizeCr = 2900.0,
                listingDate = "13 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.CLOSED,
                sourceName = "IPOWatch Listed",
                priorityRank = 12,
            ),
        )

        // 3. Even when 30 verified active IPOs arrive simultaneously, Listed IPOs are never truncated out
        val manyActiveQuotes = (1..30).map { i ->
            LiveGmpQuote(
                companyName = "Active Alpha Company $i Ltd",
                normalizedKey = "activealphacompany$i",
                gmpRupees = 20 + i,
                priceLow = 100,
                priceHigh = 110,
                lotSize = 120,
                issueSizeCr = 500.0,
                openDate = "19 Nov 2026",
                closeDate = "24 Nov 2026",
                listingDate = "29 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.LIVE,
                sourceName = "InvestorGain • Chittorgarh",
                priorityRank = 1,
            )
        }
        val combinedQuotes = gmpRepo.mergeQuotesWithoutDuplicates(
            manyActiveQuotes + parsedJsonListed + parsedHtmlListed + ipoWatchListedCorroboration,
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(ipoDao = db.ipoDao(), liveGmpRepository = gmpRepo)
            repo.applySnapshotToDatabase(
                existingById = emptyMap(),
                gmpSnapshot = LiveGmpSnapshot(
                    quotesByKey = combinedQuotes.associateBy { it.normalizedKey },
                    allQuotes = combinedQuotes,
                    activeSources = listOf("InvestorGain", "InvestorGain Listed", "Chittorgarh Listed"),
                    syncedAtTime = "10:30 AM",
                ),
            )
            val persisted = repo.observeAllIpos().first()
            val persistedListed = persisted.filter {
                IpoDateUtils.isCloseDatePassed(it.closeDate, it.status, listingDate = it.listingDate)
            }
            assertEquals(3, persistedListed.size)
            assertTrue(persistedListed.any { it.companyName.contains("Swiggy", ignoreCase = true) })
            assertTrue(persistedListed.any { it.companyName.contains("Sagility", ignoreCase = true) })
            assertTrue(persistedListed.any { it.companyName.contains("ACME Solar", ignoreCase = true) })
        } finally {
            db.close()
        }
    }

    @Test
    fun `settings bottom sheet hides admob revenue setup box`() {
        composeTestRule.setContent {
            IpoPulseTheme {
                SettingsBottomSheet(
                    currentTheme = ThemePreference.SYSTEM,
                    adImpressionCount = 5,
                    onSelectTheme = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Preferences & Alerts").assertIsDisplayed()
        composeTestRule.onNodeWithText("AdMob Revenue Setup").assertDoesNotExist()
        composeTestRule.onNodeWithText("Banner ID:", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Interstitial ID:", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Native ID:", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("App Open ID:", substring = true).assertDoesNotExist()
    }

    @Test
    fun `listed tab ipo card shows Listed label instead of Listing`() {
        val listedIpo = IpoSeedData.createInitialCatalog().first { it.status == IpoStatus.CLOSED }
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoCard(
                    ipo = listedIpo,
                    onClick = {},
                    onShareClick = {},
                    isListedTab = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Listed: ${listedIpo.listingDate}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listing: ${listedIpo.listingDate}").assertDoesNotExist()
    }

    @Test
    fun `navigation menu drawer opens from top bar and navigates to reports and faqs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
            )
            val prefs = UserPreferencesRepository(context)
            val viewModel = IpoBoardViewModel(
                ipoRepository = repo,
                preferencesRepository = prefs,
                newsRepository = IpoNewsRepository(enableNetworkFetch = false),
            )

            composeTestRule.setContent {
                val uiState = viewModel.uiState.collectAsState().value
                IpoPulseTheme {
                    AppNavigation(
                        boardViewModel = viewModel,
                        uiState = uiState,
                        ipoRepository = repo,
                    )
                }
            }
            composeTestRule.waitForIdle()

            // Open drawer from top-left hamburger menu icon
            composeTestRule.onNodeWithTag("menu_drawer_button").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("app_navigation_drawer").assertIsDisplayed()
            composeTestRule.onNodeWithTag("drawer_item_home").assertIsDisplayed()
            composeTestRule.onNodeWithTag("drawer_item_allotment_hub").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("REPORTS").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("drawer_item_ipo_list_yearwise").performScrollTo().assertIsDisplayed()

            // Navigate to IPO List (Year-wise)
            composeTestRule.onNodeWithTag("drawer_item_ipo_list_yearwise").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("yearwise_ipo_list_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("yearwise_list_back_button").performClick()
            composeTestRule.waitForIdle()

            // Re-open drawer and open FAQs
            composeTestRule.onNodeWithTag("menu_drawer_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("drawer_item_faqs").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("faqs_screen").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun `private allotment repository validates pans isolates multiple users parses registrar and fires masked notification`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Verify PAN validation & masking
        assertTrue(PanPrivacyUtils.isValidPan("abcde1234f"))
        assertFalse(PanPrivacyUtils.isValidPan("INVALID123"))
        assertEquals("ABCD••••4F", PanPrivacyUtils.maskPan("ABCDE1234F"))

        // Verify direct MUFG Intime company match & share allotment parser
        val sampleListPayload = """{"d":"<NewDataSet><Table><company_id>11942</company_id><companyname>Crystal Crop Protection Limited - IPO</companyname></Table></NewDataSet>"}"""
        assertEquals("11942", DirectRegistrarAllotmentChecker.matchMufgCompanyCode(sampleListPayload, "Crystal Crop Protection"))
        val allottedOutcome = DirectRegistrarAllotmentChecker.parseMufgSearchResponse(
            """{"d":"<NewDataSet><Table><ALLOT>55</ALLOT></Table></NewDataSet>"}""",
        )
        assertEquals(AllotmentWatchStatus.ALLOTTED, allottedOutcome?.status)
        assertEquals(55, allottedOutcome?.sharesAllotted)

        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val fakeChecker = object : RegistrarAllotmentChecker {
                override suspend fun checkStatus(
                    watch: com.example.data.model.AllotmentWatchEntity,
                    panNumber: String,
                    referenceTimeMs: Long,
                ): RegistrarCheckOutcome {
                    return if (panNumber == "ABCDE1234F") {
                        RegistrarCheckOutcome(
                            status = AllotmentWatchStatus.ALLOTTED,
                            sharesAllotted = 55,
                            summary = "Confirmed via registrar: 55 shares allotted!",
                        )
                    } else {
                        RegistrarCheckOutcome(
                            status = AllotmentWatchStatus.NOT_ALLOTTED,
                            sharesAllotted = 0,
                            summary = "Checked registrar: 0 shares allotted.",
                        )
                    }
                }
            }
            val allotmentRepo = AllotmentRepository(db.allotmentDao(), checker = fakeChecker)

            val selfProfile = allotmentRepo.saveProfile(
                displayName = "Self",
                panNumber = "abcde1234f",
            ).getOrThrow()
            val spouseProfile = allotmentRepo.saveProfile(
                displayName = "Spouse",
                panNumber = "FGHIJ5678K",
            ).getOrThrow()

            // Duplicate PAN rejected cleanly
            assertTrue(allotmentRepo.saveProfile(displayName = "Duplicate", panNumber = "ABCDE1234F").isFailure)

            val ipo = IpoSeedData.createInitialCatalog().first()
            val watches = allotmentRepo.trackIpoForProfiles(
                ipo = ipo,
                profileIds = listOf(selfProfile.id, spouseProfile.id),
                lotsApplied = 1,
            ).getOrThrow()
            assertEquals(2, watches.size)

            val batch = allotmentRepo.checkAllPendingWatches()
            assertEquals(2, batch.checkedCount)
            val selfEvent = batch.events.first { it.watch.profileId == selfProfile.id }
            val spouseEvent = batch.events.first { it.watch.profileId == spouseProfile.id }
            assertTrue(selfEvent.shouldNotifyAllotted)
            assertEquals(55, selfEvent.watch.sharesAllotted)
            assertTrue(spouseEvent.shouldNotifyNotAllotted)

            // Post notification and verify full PAN is NEVER exposed in notification text
            IpoNotificationManager.postAllotmentEventNotification(context, selfEvent)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val posted = Shadows.shadowOf(notificationManager).allNotifications
            assertTrue(posted.isNotEmpty())
            val title = posted.last().extras.getString("android.title").orEmpty()
            val text = posted.last().extras.getString("android.text").orEmpty()
            assertTrue(title.contains("IPO Allotted"))
            assertTrue(text.contains("ABCD••••4F"))
            assertFalse(text.contains("ABCDE1234F"))

            // Wipe private data leaves zero profiles and zero watches
            allotmentRepo.wipeAllPrivateData()
            assertTrue(allotmentRepo.getAllProfiles().isEmpty())
            assertTrue(allotmentRepo.getAllWatches().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `allotment alerts screen guides user to add private profile and track ipo locally`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
            )
            val allotmentRepo = AllotmentRepository(
                allotmentDao = db.allotmentDao(),
                checker = DirectRegistrarAllotmentChecker(enableNetworkFetch = false),
            )
            val prefs = UserPreferencesRepository(context)
            val viewModel = IpoBoardViewModel(
                ipoRepository = repo,
                preferencesRepository = prefs,
                newsRepository = IpoNewsRepository(enableNetworkFetch = false),
            )

            composeTestRule.setContent {
                val uiState = viewModel.uiState.collectAsState().value
                IpoPulseTheme {
                    AppNavigation(
                        boardViewModel = viewModel,
                        uiState = uiState,
                        ipoRepository = repo,
                        allotmentRepository = allotmentRepo,
                    )
                }
            }
            composeTestRule.waitForIdle()

            // Tap new ALLOTMENT button directly in the bottom bar
            composeTestRule.onNodeWithTag("bottom_bar_allotment").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("allotment_alerts_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("bottom_bar_allotment").assertIsDisplayed()
            composeTestRule.onNodeWithTag("privacy_vault_banner").assertIsDisplayed()

            // Step 2: Add private profile
            composeTestRule.onNodeWithTag("step2_add_profile_btn").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("profile_name_input").performTextInput("Self")
            composeTestRule.onNodeWithTag("profile_pan_input").performTextInput("ABCDE1234F")
            composeTestRule.onNodeWithTag("save_profile_button").performClick()
            composeTestRule.waitForIdle()

            // Verify profile is saved with masked PAN (raw PAN hidden by default)
            composeTestRule.onNodeWithTag("allotment_alerts_list")
                .performScrollToNode(hasText("PAN: ABCD••••4F"))
            composeTestRule.onNodeWithText("PAN: ABCD••••4F").assertIsDisplayed()
            composeTestRule.onNodeWithText("ABCDE1234F").assertDoesNotExist()
        } finally {
            db.close()
        }
    }

    @Test
    fun `ipo event calendar builder extracts events counts boards marks today and interleaves market holidays`() {
        val refMs = IpoDateUtils.parseFlexibleDateMs("19 Nov 2026")!!
        val catalog = IpoSeedData.createInitialCatalog()
        val sampleMainboard = catalog.first { it.boardType == BoardType.MAINBOARD }.copy(
            id = "cal-main-1",
            companyName = "Mahanadi Coalfields",
            openDate = "19 Nov 2026",
            closeDate = "23 Nov 2026",
            allotmentDate = "25 Nov 2026",
            listingDate = "27 Nov 2026",
        )
        val sampleSme = catalog.first { it.boardType == BoardType.SME }.copy(
            id = "cal-sme-1",
            companyName = "Watrana Rentals",
            exchange = "NSE SME",
            openDate = "19 Nov 2026",
            closeDate = "23 Nov 2026",
            allotmentDate = "25 Nov 2026",
            listingDate = "27 Nov 2026",
        )

        val events = IpoEventCalendarBuilder.extractUpcomingEvents(
            allIpos = listOf(sampleMainboard, sampleSme),
            referenceTimeMs = refMs,
        )
        val counts = IpoEventCalendarBuilder.computeCounts(events)
        assertEquals(8, counts.allCount)
        assertEquals(4, counts.mainboardCount)
        assertEquals(4, counts.smeCount)

        val allTimeline = IpoEventCalendarBuilder.buildTimeline(
            events = events,
            boardFilter = BoardFilter.ALL,
            referenceTimeMs = refMs,
        )
        val todayGroup = allTimeline.filterIsInstance<CalendarTimelineItem.DayGroup>().first()
        assertTrue(todayGroup.isToday)
        assertEquals("Thu, 19 Nov 2026", todayGroup.formattedDate)
        assertEquals(2, todayGroup.events.size)
        assertTrue(todayGroup.events.all { it.eventType == CalendarEventType.OPEN })
        assertTrue(todayGroup.events.any { it.boardBadgeText == "MAINBOARD" })
        assertTrue(todayGroup.events.any { it.boardBadgeText == "NSE SME" })

        val holiday = allTimeline.filterIsInstance<CalendarTimelineItem.MarketHoliday>()
            .firstOrNull { it.title == "Prakash Gurpurb" }
        assertTrue(holiday != null)
        assertEquals("Tue, 24 Nov 2026", holiday?.formattedDate)

        // Board filter narrows to SME only
        val smeTimeline = IpoEventCalendarBuilder.buildTimeline(
            events = events,
            boardFilter = BoardFilter.SME,
            referenceTimeMs = refMs,
        )
        val smeDayEvents = smeTimeline.filterIsInstance<CalendarTimelineItem.DayGroup>().flatMap { it.events }
        assertEquals(4, smeDayEvents.size)
        assertTrue(smeDayEvents.all { it.boardType == BoardType.SME })
    }

    @Test
    fun `ipo event calendar opens from top bar shortcut beside search and from menu drawer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
            )
            val allotmentRepo = AllotmentRepository(
                allotmentDao = db.allotmentDao(),
                checker = DirectRegistrarAllotmentChecker(enableNetworkFetch = false),
            )
            val prefs = UserPreferencesRepository(context)
            val viewModel = IpoBoardViewModel(
                ipoRepository = repo,
                preferencesRepository = prefs,
                newsRepository = IpoNewsRepository(enableNetworkFetch = false),
            )

            composeTestRule.setContent {
                val uiState = viewModel.uiState.collectAsState().value
                IpoPulseTheme {
                    AppNavigation(
                        boardViewModel = viewModel,
                        uiState = uiState,
                        ipoRepository = repo,
                        allotmentRepository = allotmentRepo,
                    )
                }
            }
            composeTestRule.waitForIdle()

            // Shortcut button beside Search icon in top bar
            composeTestRule.onNodeWithTag("search_toggle_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("calendar_shortcut_button").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("ipo_calendar_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("calendar_filter_bar").assertIsDisplayed()
            composeTestRule.onNodeWithTag("calendar_filter_mainboard").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("calendar_filter_sme").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("calendar_filter_all").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()

            // Return to Home via Back button
            composeTestRule.onNodeWithTag("calendar_back_button").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("calendar_shortcut_button").assertIsDisplayed()

            // Also open from Menu Drawer
            composeTestRule.onNodeWithTag("menu_drawer_button").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("drawer_item_ipo_calendar").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("ipo_calendar_screen").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun `multi-source verifier excludes single-source and unconfirmed ipos and keeps only confirmed filings`() = runTest {
        val gmpRepo = LiveGmpRepository(enableNetworkFetch = false)
        val refMs = IpoDateUtils.parseFlexibleDateMs("19 Nov 2026")!!

        // 1. Official NSE JSON parses equity IPO filing and skips Debt/NCD issues
        val nseJson = """
            [
              {
                "symbol": "CRYSTALCROP",
                "companyName": "Crystal Crop Protection Limited",
                "series": "EQ",
                "issueStartDate": "18-Nov-2026",
                "issueEndDate": "20-Nov-2026",
                "issuePrice": "259-272",
                "minBidQuantity": "55",
                "issueSize": "801.43 Cr",
                "status": "Active"
              },
              {
                "symbol": "NCD2026",
                "companyName": "Sample Finance NCD Bond",
                "series": "DEBT",
                "issueStartDate": "18-Nov-2026",
                "issueEndDate": "22-Nov-2026",
                "issuePrice": "1000",
                "minBidQuantity": "10",
                "issueSize": "500 Cr"
              }
            ]
        """.trimIndent()
        val nseQuotes = gmpRepo.parseNseIpoJson(nseJson)
        assertEquals(1, nseQuotes.size)
        assertEquals("Crystal Crop Protection Limited", nseQuotes[0].companyName)
        assertEquals(55, nseQuotes[0].lotSize)
        assertEquals(801.43, nseQuotes[0].issueSizeCr, 0.01)
        assertEquals(setOf("NSE India"), nseQuotes[0].verifiedProviders)

        // 2. Build a mix of valid multi-source confirmed filings AND invalid/unconfirmed candidates:
        val rawQuotes = listOf(
            // VALID #1: Cross-verified across NSE India + InvestorGain (confirmed lot, issue size, price, dates)
            nseQuotes[0],
            LiveGmpQuote(
                companyName = "Crystal Crop Protection Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Crystal Crop Protection Ltd"),
                gmpRupees = 52,
                priceLow = 259,
                priceHigh = 272,
                lotSize = 55,
                issueSizeCr = 801.43,
                openDate = "18 Nov 2026",
                closeDate = "20 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.LIVE,
                sourceName = "InvestorGain",
                priorityRank = 1,
            ),
            // VALID #2: Cross-verified across IPOWatch + Chittorgarh (each contributing verified fields)
            LiveGmpQuote(
                companyName = "Apex Precision Systems Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Apex Precision Systems Ltd"),
                gmpRupees = 45,
                priceLow = 190,
                priceHigh = 200,
                lotSize = 75,
                issueSizeCr = 0.0, // IPOWatch missing size; Chittorgarh confirms it below
                openDate = "20 Nov 2026",
                closeDate = "24 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.PRE_APPLY,
                sourceName = "IPOWatch",
                priorityRank = 2,
            ),
            LiveGmpQuote(
                companyName = "Apex Precision Systems",
                normalizedKey = gmpRepo.normalizeCompanyKey("Apex Precision Systems"),
                gmpRupees = 40,
                priceLow = 190,
                priceHigh = 200,
                lotSize = 75,
                issueSizeCr = 620.5,
                openDate = "20 Nov 2026",
                closeDate = "24 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.PRE_APPLY,
                sourceName = "Chittorgarh",
                priorityRank = 4,
            ),
            // VALID #3: Cross-verified SME across InvestorGain + IPO Central
            LiveGmpQuote(
                companyName = "Kaveri Micro Circuits Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Kaveri Micro Circuits Ltd"),
                gmpRupees = 30,
                priceLow = 110,
                priceHigh = 118,
                lotSize = 1200,
                issueSizeCr = 54.2,
                openDate = "21 Nov 2026",
                closeDate = "25 Nov 2026",
                boardType = BoardType.SME,
                status = IpoStatus.PRE_APPLY,
                sourceName = "InvestorGain",
                priorityRank = 1,
            ),
            LiveGmpQuote(
                companyName = "Kaveri Micro Circuits",
                normalizedKey = gmpRepo.normalizeCompanyKey("Kaveri Micro Circuits"),
                gmpRupees = 28,
                priceLow = 110,
                priceHigh = 118,
                lotSize = 1200,
                issueSizeCr = 54.2,
                openDate = "21 Nov 2026",
                closeDate = "25 Nov 2026",
                boardType = BoardType.SME,
                status = IpoStatus.PRE_APPLY,
                sourceName = "IPO Central",
                priorityRank = 3,
            ),
            // REJECTED #1: Single source only (even with all details)
            LiveGmpQuote(
                companyName = "Solo Source Ventures Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Solo Source Ventures Ltd"),
                gmpRupees = 80,
                priceLow = 300,
                priceHigh = 320,
                lotSize = 46,
                issueSizeCr = 900.0,
                openDate = "20 Nov 2026",
                closeDate = "24 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.LIVE,
                sourceName = "IPOWatch",
                priorityRank = 2,
            ),
            // REJECTED #2: Two pages on the SAME provider (InvestorGain + InvestorGain Web = still 1 provider)
            LiveGmpQuote(
                companyName = "Same Site Duplicate Corp",
                normalizedKey = gmpRepo.normalizeCompanyKey("Same Site Duplicate Corp"),
                gmpRupees = 25,
                priceLow = 100,
                priceHigh = 105,
                lotSize = 140,
                issueSizeCr = 410.0,
                openDate = "20 Nov 2026",
                closeDate = "24 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.LIVE,
                sourceName = "InvestorGain",
                priorityRank = 1,
            ),
            LiveGmpQuote(
                companyName = "Same Site Duplicate Corp",
                normalizedKey = gmpRepo.normalizeCompanyKey("Same Site Duplicate Corp"),
                gmpRupees = 25,
                priceLow = 100,
                priceHigh = 105,
                lotSize = 140,
                issueSizeCr = 410.0,
                openDate = "20 Nov 2026",
                closeDate = "24 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.LIVE,
                sourceName = "InvestorGain Web",
                priorityRank = 7,
            ),
            // REJECTED #3: Multi-source BUT Lot Size not confirmed (0)
            LiveGmpQuote(
                companyName = "No Lot Size Holdings Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("No Lot Size Holdings Ltd"),
                gmpRupees = 15,
                priceLow = 200,
                priceHigh = 210,
                lotSize = 0,
                issueSizeCr = 750.0,
                openDate = "21 Nov 2026",
                closeDate = "25 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.PRE_APPLY,
                sourceName = "InvestorGain • Chittorgarh",
                priorityRank = 1,
            ),
            // REJECTED #4: Multi-source BUT Issue Size not confirmed (0.0)
            LiveGmpQuote(
                companyName = "No Issue Size Tech Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("No Issue Size Tech Ltd"),
                gmpRupees = 22,
                priceLow = 180,
                priceHigh = 190,
                lotSize = 78,
                issueSizeCr = 0.0,
                openDate = "21 Nov 2026",
                closeDate = "25 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.PRE_APPLY,
                sourceName = "InvestorGain • IPOWatch",
                priorityRank = 1,
            ),
            // REJECTED #5: Multi-source BUT Schedule dates TBA / unconfirmed (unfiled RHP)
            LiveGmpQuote(
                companyName = "Unconfirmed Dates Infra Ltd",
                normalizedKey = gmpRepo.normalizeCompanyKey("Unconfirmed Dates Infra Ltd"),
                gmpRupees = 18,
                priceLow = 150,
                priceHigh = 160,
                lotSize = 90,
                issueSizeCr = 500.0,
                openDate = "TBA",
                closeDate = "TBA",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.UPCOMING,
                sourceName = "InvestorGain • Chittorgarh",
                priorityRank = 1,
            ),
            // REJECTED #6: Rumored / DRHP-only unfiled IPO
            LiveGmpQuote(
                companyName = "Speculated Draft Only Co",
                normalizedKey = gmpRepo.normalizeCompanyKey("Speculated Draft Only Co"),
                gmpRupees = 10,
                priceLow = 100,
                priceHigh = 110,
                lotSize = 100,
                issueSizeCr = 300.0,
                openDate = "21 Nov 2026",
                closeDate = "25 Nov 2026",
                boardType = BoardType.MAINBOARD,
                status = IpoStatus.UPCOMING,
                sourceName = "InvestorGain • IPOWatch",
                priorityRank = 1,
                isUnconfirmedOrRumored = true,
            ),
        )

        val merged = gmpRepo.mergeQuotesWithoutDuplicates(rawQuotes)
        val singleSourceCheck = IpoFilingVerifier.verifyQuote(
            merged.first { it.companyName.contains("Solo Source") },
            referenceTimeMs = refMs,
        )
        assertFalse(singleSourceCheck.isVerified)
        assertTrue(FilingRejectionReason.SINGLE_SOURCE_ONLY in singleSourceCheck.rejectionReasons)

        val sameSiteCheck = IpoFilingVerifier.verifyQuote(
            merged.first { it.companyName.contains("Same Site Duplicate") },
            referenceTimeMs = refMs,
        )
        assertFalse(sameSiteCheck.isVerified)
        assertEquals(setOf("InvestorGain"), sameSiteCheck.verifiedProviders)

        val noLotCheck = IpoFilingVerifier.verifyQuote(
            merged.first { it.companyName.contains("No Lot Size") },
            referenceTimeMs = refMs,
        )
        assertFalse(noLotCheck.isVerified)
        assertTrue(FilingRejectionReason.UNCONFIRMED_LOT_SIZE in noLotCheck.rejectionReasons)

        val noSizeCheck = IpoFilingVerifier.verifyQuote(
            merged.first { it.companyName.contains("No Issue Size") },
            referenceTimeMs = refMs,
        )
        assertFalse(noSizeCheck.isVerified)
        assertTrue(FilingRejectionReason.UNCONFIRMED_ISSUE_SIZE in noSizeCheck.rejectionReasons)

        val noDatesCheck = IpoFilingVerifier.verifyQuote(
            merged.first { it.companyName.contains("Unconfirmed Dates") },
            referenceTimeMs = refMs,
        )
        assertFalse(noDatesCheck.isVerified)
        assertTrue(FilingRejectionReason.UNCONFIRMED_SCHEDULE in noDatesCheck.rejectionReasons)

        // Persist through IpoRepository and assert ONLY the 3 multi-source confirmed filings are stored
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(ipoDao = db.ipoDao(), liveGmpRepository = gmpRepo)
            val summary = repo.applySnapshotToDatabase(
                existingById = emptyMap(),
                gmpSnapshot = LiveGmpSnapshot(
                    quotesByKey = merged.associateBy { it.normalizedKey },
                    allQuotes = merged,
                    activeSources = listOf("NSE India", "InvestorGain", "IPOWatch", "Chittorgarh", "IPO Central"),
                    syncedAtTime = "08:15 PM",
                ),
            )
            assertEquals(6, summary.excludedUnconfirmedCount)

            val saved = repo.observeAllIpos().first()
            assertEquals(3, saved.size)
            assertTrue(saved.any { it.companyName.contains("Crystal Crop") })
            assertTrue(saved.any { it.companyName.contains("Apex Precision") })
            assertTrue(saved.any { it.companyName.contains("Kaveri Micro") })
            assertFalse(saved.any { it.companyName.contains("Solo Source") })
            assertFalse(saved.any { it.companyName.contains("Same Site Duplicate") })
            assertFalse(saved.any { it.companyName.contains("No Lot Size") })
            assertFalse(saved.any { it.companyName.contains("No Issue Size") })
            assertFalse(saved.any { it.companyName.contains("Unconfirmed Dates") })
            assertFalse(saved.any { it.companyName.contains("Speculated Draft") })
            assertTrue(saved.all { it.lotSize > 0 && it.issueSizeCr > 0.0 && it.priceHigh > 0 })
            assertTrue(saved.all { it.gmpSourceLabel.startsWith("Verified •") })
        } finally {
            db.close()
        }
    }

    @Test
    fun `amoled dark theme uses true black oled canvas opaque surfaces and high contrast white text`() {
        var bgColor = Color.Unspecified
        var surfaceColor = Color.Unspecified
        var onBgColor = Color.Unspecified
        var onSurfaceColor = Color.Unspecified
        var mutedColor = Color.Unspecified
        var isAmoled = false
        var isGlass = true
        var cardAlpha = 0f
        var sheetAlpha = 0f

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                val cs = MaterialTheme.colorScheme
                val glass = LocalGlassStyle.current
                val market = LocalMarketColors.current
                bgColor = cs.background
                surfaceColor = cs.surface
                onBgColor = cs.onBackground
                onSurfaceColor = cs.onSurface
                mutedColor = market.mutedText
                isAmoled = glass.isAmoled
                isGlass = glass.isGlass
                cardAlpha = glass.cardFill.alpha
                sheetAlpha = glass.sheetFill.alpha
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(isAmoled)
        assertFalse(isGlass)
        assertEquals(Color(0xFF000000), bgColor)
        assertEquals(Color(0xFFFFFFFF), onBgColor)
        assertEquals(Color(0xFFFFFFFF), onSurfaceColor)
        assertEquals(1f, surfaceColor.alpha, 0.001f)
        assertEquals(1f, cardAlpha, 0.001f)
        assertEquals(1f, sheetAlpha, 0.001f)
        // Muted text stays bright and legible against pitch-black OLED surfaces
        assertTrue(mutedColor.red > 0.65f && mutedColor.green > 0.70f && mutedColor.blue > 0.75f)
    }

    @Test
    fun `ios transparent glass theme exposes translucent frosted surfaces over aurora backdrop`() {
        var isGlass = false
        var isAmoled = true
        var cardAlpha = 1f
        var dockAlpha = 1f
        var sheetAlpha = 1f
        var onSurfaceColor = Color.Unspecified

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.IOS_GLASS) {
                val cs = MaterialTheme.colorScheme
                val glass = LocalGlassStyle.current
                isGlass = glass.isGlass
                isAmoled = glass.isAmoled
                cardAlpha = glass.cardFill.alpha
                dockAlpha = glass.dockFill.alpha
                sheetAlpha = glass.sheetFill.alpha
                onSurfaceColor = cs.onSurface
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(isGlass)
        assertFalse(isAmoled)
        assertTrue(cardAlpha in 0.55f..0.85f)
        assertTrue(dockAlpha in 0.65f..0.90f)
        assertTrue(sheetAlpha in 0.75f..0.95f)
        // Deep high-contrast dark ink on frosted white glass
        assertTrue(onSurfaceColor.red < 0.15f && onSurfaceColor.green < 0.15f)
    }

    @Test
    fun `settings appearance sheet renders amoled dark and ios glass options and updates selection`() {
        val selectedThemes = mutableListOf<ThemePreference>()
        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                SettingsBottomSheet(
                    currentTheme = ThemePreference.DARK,
                    adImpressionCount = 0,
                    onSelectTheme = { selectedThemes += it },
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("theme_option_system").assertIsDisplayed()
        composeTestRule.onNodeWithTag("theme_option_light").assertIsDisplayed()
        composeTestRule.onNodeWithTag("theme_option_dark").assertIsDisplayed()
        composeTestRule.onNodeWithTag("theme_option_ios_glass").assertIsDisplayed()

        composeTestRule.onNodeWithText("True black OLED").assertIsDisplayed()
        composeTestRule.onNodeWithText("iOS Glass").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transparent frost").assertIsDisplayed()

        composeTestRule.onNodeWithTag("theme_option_ios_glass").performClick()
        composeTestRule.onNodeWithTag("theme_option_light").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(ThemePreference.IOS_GLASS, ThemePreference.LIGHT), selectedThemes)
    }

    @Test
    fun `ipo detail screen shows recovery card instead of infinite spinner when ipo is unavailable`() {
        var refreshTapped = false
        var backTapped = false

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                IpoDetailScreen(
                    ipo = null,
                    isLoading = false,
                    selectedTab = DetailTab.ALL,
                    onSelectTab = {},
                    onBack = { backTapped = true },
                    onUpdateLots = {},
                    onAdImpression = {},
                    onRefresh = { refreshTapped = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("detail_unavailable_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("IPO Report Unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_unavailable_refresh_btn").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("detail_unavailable_back_btn").assertIsDisplayed().performClick()

        assertTrue(refreshTapped)
        assertTrue(backTapped)
    }

    @Test
    fun `ipo detail viewmodel falls back to seed catalog when db lacks rotated id`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
            )
            val seedIpo = IpoSeedData.createInitialCatalog().first()

            val seededVm = IpoDetailViewModel(ipoId = seedIpo.id, ipoRepository = repo)
            val unknownVm = IpoDetailViewModel(ipoId = "non-existent-ipo-id", ipoRepository = repo)
            val showUnknown = MutableStateFlow(false)

            composeTestRule.setContent {
                val activeVm = if (showUnknown.collectAsState().value) unknownVm else seededVm
                val loadState = activeVm.loadState.collectAsState().value
                IpoPulseTheme(themePreference = ThemePreference.DARK) {
                    IpoDetailScreen(
                        ipo = loadState.ipo,
                        isLoading = loadState.isLoading,
                        selectedTab = DetailTab.ALL,
                        onSelectTab = {},
                        onBack = {},
                        onUpdateLots = {},
                        onAdImpression = {},
                    )
                }
            }
            composeTestRule.waitForIdle()
            // Even with an empty Room DB, a rotated seed IPO opens immediately via seed fallback
            composeTestRule.onNodeWithText(seedIpo.companyName).assertIsDisplayed()

            // Switching to an unknown IPO ID transitions cleanly out of loading to recovery UI
            showUnknown.value = true
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                composeTestRule.onAllNodes(hasTestTag("detail_unavailable_card"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeTestRule.onNodeWithTag("detail_unavailable_card").assertIsDisplayed()
            composeTestRule.onNodeWithText("IPO Report Unavailable").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun `ipo detail report tabs switch cleanly handles fixed price band and unified nii formula`() {
        val base = IpoSeedData.createInitialCatalog().first()
        val fixedPriceIpo = base.copy(
            priceLow = 0,
            priceHigh = 148,
            bNiiSubX = 6.0,
            sNiiSubX = 3.0,
            dailySubscriptions = emptyList(),
        )
        // SEBI weighted NII = (6.0 * 2 + 3.0) / 3 = 5.0
        assertEquals(5.0, fixedPriceIpo.niiCombinedSubX, 0.001)

        val selectedTab = MutableStateFlow(DetailTab.ALL)
        composeTestRule.setContent {
            val tab = selectedTab.collectAsState().value
            IpoPulseTheme(themePreference = ThemePreference.IOS_GLASS) {
                IpoDetailScreen(
                    ipo = fixedPriceIpo,
                    isLoading = false,
                    selectedTab = tab,
                    onSelectTab = { selectedTab.value = it },
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("detail_tab_all"))
        composeTestRule.onNodeWithTag("detail_tab_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_tab_overview").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("ipo_details_breakdown_card"))
        composeTestRule.onNodeWithText("₹148 per share").assertIsDisplayed()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("detail_tab_subscription"))
        composeTestRule.onNodeWithTag("detail_tab_subscription").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("live_subscription_card"))
        composeTestRule.onNodeWithText("NII Total: 5.00x").assertIsDisplayed()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("detail_tab_financials"))
        composeTestRule.onNodeWithTag("detail_tab_financials").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasText("Restated Financial Report (₹ in Crores)"))
        composeTestRule.onNodeWithText("Restated Financial Report (₹ in Crores)").assertIsDisplayed()
    }

    @Test
    fun `drawer report screens extract year dynamically and filter by board cleanly`() {
        val catalog = IpoSeedData.createInitialCatalog()
        val refCal = java.util.Calendar.getInstance().apply {
            set(2027, java.util.Calendar.MARCH, 15, 10, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val yearlessIpo = catalog.first().copy(
            openDate = "18 Mar",
            closeDate = "20 Mar",
            allotmentDate = "21 Mar",
            listingDate = "24 Mar",
        )
        assertEquals("2027", extractIpoYear(yearlessIpo, refCal.timeInMillis))

        val clickedIds = mutableListOf<String>()
        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                YearWiseSubscriptionScreen(
                    allIpos = catalog,
                    onIpoClick = { clickedIds += it },
                    onBack = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("yearwise_subscription_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sub_board_chip_sme").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val firstSme = catalog
            .filter { it.boardType == BoardType.SME }
            .sortedWith(compareByDescending<com.example.data.model.IpoEntity> { it.totalSubX }.thenBy { it.status.ordinal })
            .first()
        composeTestRule.onNodeWithTag("yearwise_sub_lazy_column")
            .performScrollToNode(hasTestTag("sub_report_row_${firstSme.id}"))
        composeTestRule.onNodeWithTag("sub_report_row_${firstSme.id}").performClick()
        assertEquals(listOf(firstSme.id), clickedIds)
    }

    @Test
    fun `market radar gmp report filters by board and displays 2x3 metric grid`() {
        val catalog = IpoSeedData.createInitialCatalog()
        val radarState = IpoBoardUiState(
            allIpos = catalog,
            radarIpos = catalog.sortedByDescending { it.gmpPercent },
            radarSortMode = RadarSortMode.GMP_PERCENT,
        )
        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.IOS_GLASS) {
                MarketRadarScreen(
                    uiState = radarState,
                    onSelectSortMode = {},
                    onIpoClick = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("market_radar_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_board_mainboard").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val topMainboard = catalog
            .filter { it.boardType == BoardType.MAINBOARD }
            .maxBy { it.gmpPercent }
        composeTestRule.onNodeWithTag("radar_lazy_column")
            .performScrollToNode(hasTestTag("radar_card_${topMainboard.id}"))
        composeTestRule.onNodeWithTag("radar_card_${topMainboard.id}").assertIsDisplayed()
    }

    @Test
    fun `news notification engine filters by topic avoids duplicate alerts and posts android notifications`() {
        val baseArticles = IpoNewsCatalog.getLatestNews()

        // Initial baseline seeds known IDs without firing alerts or unread badges
        val baseline = IpoNotificationManager.evaluateNewsNotificationEvents(
            articles = baseArticles,
            knownNewsIds = emptySet(),
            unreadNewsIds = emptySet(),
            hasNewsBaseline = false,
            notifyNewsAlertsEnabled = true,
            enabledCategories = setOf(NewsCategory.GMP_LISTING, NewsCategory.UPCOMING),
        )
        assertTrue(baseline.newArticlesToAlert.isEmpty())
        assertTrue(baseline.updatedUnreadNewsIds.isEmpty())
        assertEquals(baseArticles.map { it.id }.toSet(), baseline.updatedKnownNewsIds)

        // Two new articles arrive: one matching GMP_LISTING (enabled) and one SME_BUZZ (disabled)
        val matchingArticle = baseArticles.first().copy(
            id = "breaking-gmp-alert-1",
            title = "Mainboard IPO GMP Jumps 28% Ahead of Listing",
            category = NewsCategory.GMP_LISTING,
        )
        val mutedCategoryArticle = baseArticles.first().copy(
            id = "breaking-sme-muted-2",
            title = "SME Draft Red Herring Prospectus Filed",
            category = NewsCategory.SME_BUZZ,
        )
        val updatedFeed = listOf(matchingArticle, mutedCategoryArticle) + baseArticles

        val evaluation = IpoNotificationManager.evaluateNewsNotificationEvents(
            articles = updatedFeed,
            knownNewsIds = baseline.updatedKnownNewsIds,
            unreadNewsIds = baseline.updatedUnreadNewsIds,
            hasNewsBaseline = true,
            notifyNewsAlertsEnabled = true,
            enabledCategories = setOf(NewsCategory.GMP_LISTING, NewsCategory.UPCOMING),
        )
        assertEquals(listOf("breaking-gmp-alert-1"), evaluation.newArticlesToAlert.map { it.id })
        assertEquals(setOf("breaking-gmp-alert-1"), evaluation.updatedUnreadNewsIds)

        // Post real Android news notification when permission is granted
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = Shadows.shadowOf(notificationManager)
        notificationManager.cancelAll()

        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        IpoNotificationManager.postNewsAlertNotification(context, matchingArticle)
        IpoNotificationManager.postSampleNewsAlertNotification(context, matchingArticle)

        val posted = shadowNotificationManager.allNotifications
        assertEquals(2, posted.size)
        val titles = posted.map { it.extras.getString("android.title").orEmpty() }
        assertTrue(titles.any { it.contains("GMP & Listing: Mainboard IPO GMP Jumps 28%") })
        assertTrue(titles.any { it.contains("IPO News Alert: Mainboard IPO GMP Jumps 28%") })
    }

    @Test
    fun `news screen alert controls open sheet toggle topics send test alert and settings includes news switch`() {
        var alertsEnabled = true
        val toggledCategories = mutableListOf<NewsCategory>()
        var testAlertSent = false
        var settingsNewsToggle = true

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                IpoNewsScreen(
                    onOpenIpoDetail = {},
                    notifyNewsAlertsEnabled = alertsEnabled,
                    newsAlertCategories = setOf(
                        NewsCategory.UPCOMING,
                        NewsCategory.GMP_LISTING,
                        NewsCategory.SME_BUZZ,
                        NewsCategory.SEBI_FILINGS,
                    ),
                    hasNotificationPermission = true,
                    onToggleNewsAlerts = { alertsEnabled = it },
                    onToggleNewsAlertCategory = { toggledCategories += it },
                    onSendTestNewsAlert = { testAlertSent = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("news_alerts_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("news_alerts_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("news_alerts_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("news_alerts_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("news_alert_cat_sme_buzz").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("news_alerts_test_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("news_alerts_master_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(NewsCategory.SME_BUZZ), toggledCategories)
        assertTrue(testAlertSent)
        assertFalse(alertsEnabled)
    }

    @Test
    fun `settings sheet toggles breaking news alerts and contact dialog uses updated support email`() {
        var notifyNews = true
        val showContact = MutableStateFlow(false)

        composeTestRule.setContent {
            val isContactOpen = showContact.collectAsState().value
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                if (isContactOpen) {
                    ContactUsDialog(onDismiss = {})
                } else {
                    SettingsBottomSheet(
                        currentTheme = ThemePreference.DARK,
                        adImpressionCount = 0,
                        onSelectTheme = {},
                        onDismiss = {},
                        notifyNewsAlertsEnabled = notifyNews,
                        onToggleNotifyNewsAlerts = { notifyNews = it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("switch_notify_news").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertFalse(notifyNews)

        showContact.value = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("contact_us_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("developers.pcmedia@gmail.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("support@ipoguruji.app").assertDoesNotExist()
    }

    @Test
    fun `allotment probability calculator computes multi-PAN retail odds and compares sHNI and bHNI`() {
        val sample = IpoSeedData.createInitialCatalog().first().copy(
            priceHigh = 250,
            lotSize = 60, // minInvestment = 15,000
            retailSubX = 10.0, // 10% single PAN chance
            sNiiSubX = 20.0, // 5% sHNI chance
            bNiiSubX = 4.0, // 25% bHNI chance
        )
        val onePan = AllotmentProbabilityCalculator.calculate(sample, panCount = 1)
        val threePans = AllotmentProbabilityCalculator.calculate(sample, panCount = 3)

        assertEquals(10.0, onePan.singlePanRetailPercent, 0.01)
        assertEquals(10.0, onePan.multiPanRetailPercent, 0.01)
        assertEquals(5.0, onePan.sHniPercent, 0.01)
        assertEquals(25.0, onePan.bHniPercent, 0.01)

        // 1 - (1 - 0.10)^3 = 1 - 0.729 = 27.1%
        assertEquals(27.1, threePans.multiPanRetailPercent, 0.05)
        assertTrue(threePans.multiPanRetailPercent > onePan.multiPanRetailPercent)
    }

    @Test
    fun `fresh issue vs ofs analyzer computes exact percentages and investor verdicts`() {
        val base = IpoSeedData.createInitialCatalog().first()
        val pureFresh = FreshVsOfsAnalyzer.analyze(base.copy(issueSizeCr = 500.0, freshIssueCr = 500.0, ofsCr = 0.0))!!
        assertEquals(100.0, pureFresh.freshPercent, 0.01)
        assertEquals("100% Fresh Capital", pureFresh.verdictLabel)
        assertTrue(pureFresh.isBullishSignal)

        val ofsHeavy = FreshVsOfsAnalyzer.analyze(base.copy(issueSizeCr = 1000.0, freshIssueCr = 200.0, ofsCr = 800.0))!!
        assertEquals(20.0, ofsHeavy.freshPercent, 0.01)
        assertEquals(80.0, ofsHeavy.ofsPercent, 0.01)
        assertEquals("OFS-Heavy", ofsHeavy.verdictLabel)
        assertFalse(ofsHeavy.isBullishSignal)
    }

    @Test
    fun `smart ipo alerts evaluate Day-3 2 PM IST closing reminder and GMP threshold rules without duplicates`() {
        val sample = IpoSeedData.createInitialCatalog().first().copy(
            id = "smart-ipo-1",
            status = IpoStatus.LIVE,
            closeDate = "20 Nov 2026",
            priceHigh = 100,
            gmpRupees = 35, // +35% GMP
        )
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val before2PmMs = Calendar.getInstance(ist).apply {
            set(2026, Calendar.NOVEMBER, 20, 11, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val after2PmMs = Calendar.getInstance(ist).apply {
            set(2026, Calendar.NOVEMBER, 20, 14, 15, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val rules = mapOf(
            sample.id to GmpWatchRule(ipoId = sample.id, minGmpPercent = 5, maxGmpPercent = 30),
        )

        // At 11:30 AM IST: Day-3 reminder does NOT fire yet, but GMP >= +30% fires
        val morningEval = IpoNotificationManager.evaluateSmartIpoAlerts(
            ipos = listOf(sample),
            day3ReminderIpoIds = setOf(sample.id),
            notifiedDay3Ids = emptySet(),
            gmpWatchRules = rules,
            nowEpochMs = before2PmMs,
        )
        assertTrue(morningEval.day3RemindersToAlert.isEmpty())
        assertEquals(1, morningEval.gmpAlertsToFire.size)
        assertEquals("HIGH:30", morningEval.gmpAlertsToFire.first().signature)

        // At 2:15 PM IST with updated rules: Day-3 reminder fires, GMP high alert does NOT re-fire
        val afternoonEval = IpoNotificationManager.evaluateSmartIpoAlerts(
            ipos = listOf(sample),
            day3ReminderIpoIds = setOf(sample.id),
            notifiedDay3Ids = morningEval.updatedNotifiedDay3Ids,
            gmpWatchRules = morningEval.updatedGmpWatchRules,
            nowEpochMs = after2PmMs,
        )
        assertEquals(1, afternoonEval.day3RemindersToAlert.size)
        assertTrue(afternoonEval.gmpAlertsToFire.isEmpty())

        // Subsequent evaluation at 2:15 PM does not duplicate Day-3 reminder
        val repeatEval = IpoNotificationManager.evaluateSmartIpoAlerts(
            ipos = listOf(sample),
            day3ReminderIpoIds = setOf(sample.id),
            notifiedDay3Ids = afternoonEval.updatedNotifiedDay3Ids,
            gmpWatchRules = afternoonEval.updatedGmpWatchRules,
            nowEpochMs = after2PmMs,
        )
        assertTrue(repeatEval.day3RemindersToAlert.isEmpty())
    }

    @Test
    fun `private portfolio pnl calculator computes blocked UPI capital allotted cost listing gain and win rate`() {
        val ipoA = IpoSeedData.createInitialCatalog()[0].copy(
            id = "pf-ipo-a",
            priceHigh = 100,
            lotSize = 150,
            gmpRupees = 40, // est listing 140
        )
        val ipoB = IpoSeedData.createInitialCatalog()[1].copy(
            id = "pf-ipo-b",
            priceHigh = 200,
            lotSize = 75,
            gmpRupees = 20,
        )

        val watches = listOf(
            AllotmentWatchEntity(
                id = "w1",
                profileId = "p1",
                profileName = "Self",
                maskedPan = "ABCD••••4F",
                ipoId = ipoA.id,
                ipoName = ipoA.companyName,
                registrarName = ipoA.registrarName,
                registrarUrl = ipoA.registrarUrl,
                allotmentDate = ipoA.allotmentDate,
                listingDate = ipoA.listingDate,
                lotsApplied = 1,
                sharesPerLot = 150,
                status = AllotmentWatchStatus.ALLOTTED,
                sharesAllotted = 150,
            ),
            AllotmentWatchEntity(
                id = "w2",
                profileId = "p1",
                profileName = "Self",
                maskedPan = "ABCD••••4F",
                ipoId = ipoB.id,
                ipoName = ipoB.companyName,
                registrarName = ipoB.registrarName,
                registrarUrl = ipoB.registrarUrl,
                allotmentDate = ipoB.allotmentDate,
                listingDate = ipoB.listingDate,
                lotsApplied = 2,
                sharesPerLot = 75,
                status = AllotmentWatchStatus.PENDING,
            ),
            AllotmentWatchEntity(
                id = "w3",
                profileId = "p2",
                profileName = "Spouse",
                maskedPan = "EFGH••••9K",
                ipoId = ipoA.id,
                ipoName = ipoA.companyName,
                registrarName = ipoA.registrarName,
                registrarUrl = ipoA.registrarUrl,
                allotmentDate = ipoA.allotmentDate,
                listingDate = ipoA.listingDate,
                lotsApplied = 1,
                sharesPerLot = 150,
                status = AllotmentWatchStatus.NOT_ALLOTTED,
            ),
        )

        val summary = PortfolioPnlCalculator.compute(
            watches = watches,
            iposById = mapOf(ipoA.id to ipoA, ipoB.id to ipoB),
        )
        assertEquals(30_000L, summary.blockedUpiCapital) // 2 lots * 75 * 200
        assertEquals(15_000L, summary.allottedCostCapital) // 150 * 100
        assertEquals(21_000L, summary.estimatedListingValue) // 150 * 140
        assertEquals(6_000L, summary.netProfitLossRupees)
        assertEquals(40.0, summary.netProfitLossPercent, 0.01)
        assertEquals(50, summary.winRatePercent) // 1 allotted / 2 settled
    }

    @Test
    fun `compare ipos screen renders side-by-side matrix and adds a third ipo via picker`() {
        val catalog = IpoSeedData.createInitialCatalog()
        assertTrue(catalog.size >= 3)

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                CompareIposScreen(
                    allIpos = catalog,
                    initialIpoId = catalog[0].id,
                    onIpoClick = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("compare_ipos_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare_slot_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare_slot_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare_matrix_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare_row_gmp_pct").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare_row_fresh_pct").assertExists()

        // Add 3rd IPO via bottom sheet picker
        composeTestRule.onNodeWithTag("compare_add_third_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("compare_picker_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare_pick_${catalog[2].id}").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("compare_slot_2").assertIsDisplayed()
    }

    @Test
    fun `ipo detail investor tools listed card price strip and calendar export button render cleanly`() {
        val catalog = IpoSeedData.createInitialCatalog()
        val liveIpo = catalog.first { it.status == IpoStatus.LIVE }
        val listedIpo = catalog.first { it.status == IpoStatus.CLOSED }
        var day3Enabled = false
        var ruleSet: Pair<Int?, Int?>? = null
        var compareClicked = false

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.IOS_GLASS) {
                IpoDetailScreen(
                    ipo = liveIpo,
                    selectedTab = DetailTab.ALL,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                    isDay3ReminderEnabled = day3Enabled,
                    onToggleDay3Reminder = { day3Enabled = it },
                    onSetGmpWatchRule = { minP, maxP -> ruleSet = minP to maxP },
                    onCompareIpo = { compareClicked = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("smart_ipo_alerts_card"))
        composeTestRule.onNodeWithTag("day3_reminder_switch").performClick()
        composeTestRule.onNodeWithTag("gmp_high_chip_25").performClick()
        composeTestRule.onNodeWithTag("detail_lazy_column").performTouchInput {
            swipeUp(startY = bottom * 0.72f, endY = bottom * 0.35f)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_compare_ipo_btn").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("detail_add_to_calendar_btn").assertIsDisplayed()
        composeTestRule.waitForIdle()

        assertTrue(day3Enabled)
        assertEquals(null to 25, ruleSet)
        assertTrue(compareClicked)

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("allotment_odds_card"))
        composeTestRule.onNodeWithTag("odds_pan_chip_3").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("fresh_ofs_visual_card"))
        composeTestRule.onNodeWithTag("fresh_ofs_visual_card").assertIsDisplayed()

        // Verify Listed card shows Issue -> Listed price vs Pre-GMP strip
        assertTrue(listedIpo.priceHigh > 0)
    }

    @Test
    fun `home screen widget builds remote views and listed card shows issue to listing price`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = IpoSeedData.createInitialCatalog()
        val topWidgetIpos = IpoLiveWidgetProvider.selectTopWidgetIpos(catalog)
        assertTrue(topWidgetIpos.isNotEmpty())
        assertTrue(topWidgetIpos.size <= 3)

        val remoteViews = IpoLiveWidgetProvider.buildRemoteViews(context, catalog)
        assertEquals(R.layout.widget_ipo_live, remoteViews.layoutId)

        val listedIpo = catalog.first { it.status == IpoStatus.CLOSED }
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoCard(
                    ipo = listedIpo,
                    onClick = {},
                    onShareClick = {},
                    isListedTab = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("listed_price_strip_${listedIpo.id}", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Issue ₹${listedIpo.priceHigh} → Listed ₹${listedIpo.estimatedListingPrice}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `quick sort bookmark watchlist compact card mode and closing urgency badge work seamlessly`() {
        val catalog = IpoSeedData.createInitialCatalog()
        val firstIpo = catalog.first()
        var toggledBookmarkId: String? = null
        var selectedSort: BoardSortMode? = null
        var savedOnly = false
        var compactMode = false

        // 1. Urgency badge helper for Today & Tomorrow
        val todayCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("Closes Today", computeClosingUrgencyLabel("20 Nov 2026", todayCal.timeInMillis))
        assertEquals("Closes Tomorrow", computeClosingUrgencyLabel("21 Nov 2026", todayCal.timeInMillis))
        assertEquals(null, computeClosingUrgencyLabel("25 Nov 2026", todayCal.timeInMillis))

        // 2. Render IpoBoardScreen with Quick Sort bar, Saved chip, and Bookmark icon
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoBoardScreen(
                    uiState = IpoBoardUiState(
                        filteredIpos = catalog,
                        allIpos = catalog,
                        bookmarkedIpoIds = setOf(firstIpo.id),
                        compactCardsEnabled = false,
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
                    onSelectBoardSortMode = { selectedSort = it },
                    onToggleSavedOnly = { savedOnly = it },
                    onToggleBookmarkIpo = { toggledBookmarkId = it },
                    onToggleCompactCards = { compactMode = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("status_chip_saved").assertIsDisplayed().performClick()
        assertTrue(savedOnly)

        composeTestRule.onNodeWithTag("board_sort_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sort_chip_highest_gmp").performClick()
        assertEquals(BoardSortMode.HIGHEST_GMP, selectedSort)

        composeTestRule.onNodeWithTag("toggle_card_density_btn").performClick()
        assertTrue(compactMode)

        composeTestRule.onNodeWithTag("bookmark_btn_${firstIpo.id}", useUnmergedTree = true).performClick()
        assertEquals(firstIpo.id, toggledBookmarkId)
    }

    @Test
    fun `compact ipo card renders dense layout with bookmark button and inter typography`() {
        val sample = IpoSeedData.createInitialCatalog().first { it.status == IpoStatus.LIVE }
        var bookmarked = false

        // Verify reader-first Inter font is applied to body typography
        assertEquals(InterSans, AppTypography.bodyMedium.fontFamily)
        assertEquals(InterSans, AppTypography.labelMedium.fontFamily)

        composeTestRule.setContent {
            IpoPulseTheme(themePreference = ThemePreference.DARK) {
                IpoCard(
                    ipo = sample,
                    onClick = {},
                    onShareClick = {},
                    isBookmarked = true,
                    onToggleBookmark = { bookmarked = true },
                    compactMode = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("compact_ipo_card_${sample.id}", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("bookmark_btn_${sample.id}", useUnmergedTree = true).performClick()
        assertTrue(bookmarked)
    }

    @Test
    fun `interactive visual ipo timeline stepper highlights stages and shows stage guidance`() {
        val sample = IpoSeedData.createInitialCatalog().first()
        composeTestRule.setContent {
            IpoPulseTheme {
                IpoDetailScreen(
                    ipo = sample,
                    selectedTab = DetailTab.ALL,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("ipo_schedule_card"))
        composeTestRule.onNodeWithTag("visual_timeline_stepper").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timeline_stage_detail").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timeline_stage_calendar_btn").assertIsDisplayed()

        // Tap Allotment stage (index 2) and verify guidance updates
        composeTestRule.onNodeWithTag("timeline_stage_2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Basis of Allotment • ${sample.registrarName}", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `biometric vault lock gate hides private PAN profiles until unlocked and settings exposes toggles`() {
        var unlockRequested = false
        val privateProfile = PrivateUserProfileEntity(
            id = "prof_secret",
            displayName = "My Secret PAN",
            panNumber = "ABCDE1234F",
            dematId = "1208160012345678",
        )

        // 1. Locked Vault hides PAN profiles and shows unlock CTA
        composeTestRule.setContent {
            IpoPulseTheme {
                AllotmentAlertsScreen(
                    uiState = AllotmentAlertsUiState(profiles = listOf(privateProfile)),
                    allIpos = IpoSeedData.createInitialCatalog(),
                    preselectedIpoId = null,
                    hasNotificationPermission = true,
                    onRequestNotificationPermission = {},
                    onToggleAutoMonitor = {},
                    onSelectProfileFilter = {},
                    onSaveProfile = { _, _, _, _, _, _ -> },
                    onDeleteProfile = {},
                    onTrackIpo = { _, _, _, _, _ -> },
                    onDeleteWatch = {},
                    onCheckSingleWatch = {},
                    onCheckAllNow = {},
                    onConfirmManualResult = { _, _, _ -> },
                    onSendTestNotification = {},
                    onWipeAllPrivateData = {},
                    onBack = {},
                    biometricLockEnabled = true,
                    isVaultUnlocked = false,
                    vaultAuthError = "Try fingerprint again",
                    onUnlockVault = { unlockRequested = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("private_vault_lock_gate").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_lock_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Secret PAN").assertDoesNotExist()
        composeTestRule.onNodeWithTag("unlock_vault_button").performClick()
        assertTrue(unlockRequested)
    }

    @Test
    fun `settings bottom sheet exposes compact cards and biometric lock switches`() {
        var compactToggled: Boolean? = null
        var biometricToggled: Boolean? = null

        composeTestRule.setContent {
            IpoPulseTheme {
                SettingsBottomSheet(
                    currentTheme = ThemePreference.LIGHT,
                    adImpressionCount = 0,
                    onSelectTheme = {},
                    onDismiss = {},
                    compactCardsEnabled = false,
                    onToggleCompactCards = { compactToggled = it },
                    biometricLockEnabled = false,
                    onToggleBiometricLock = { biometricToggled = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("switch_compact_cards").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("switch_biometric_lock").performScrollTo().performClick()
        assertEquals(true, compactToggled)
        assertEquals(true, biometricToggled)
    }

    @Test
    fun `hybrid live subscription engine parses multi-source QIB NII Retail data and persists into Room`() = runTest {
        val subRepo = LiveSubscriptionRepository(enableNetworkFetch = false)
        val sampleIpo = IpoSeedData.createInitialCatalog().first { it.status == IpoStatus.LIVE }

        // 1. Parse InvestorGain Live Subscription JSON
        val igJson = """
            {
              "reportTableData": [
                {
                  "Company Name": "${sampleIpo.companyName}",
                  "QIB": "24.80x",
                  "bNII": "31.50",
                  "sNII": "19.20",
                  "RII": "14.65",
                  "EMP": "3.10",
                  "Total": "22.40",
                  "~IPO_Category": "Mainboard"
                }
              ]
            }
        """.trimIndent()
        val igQuotes = subRepo.parseInvestorGainSubscriptionJson(igJson, "InvestorGain", 1, "20 Nov, 02:30 PM")
        assertEquals(1, igQuotes.size)
        assertEquals(24.80, igQuotes[0].qibSubX, 0.01)
        assertEquals(31.50, igQuotes[0].bNiiSubX, 0.01)
        assertEquals(19.20, igQuotes[0].sNiiSubX, 0.01)
        assertEquals(14.65, igQuotes[0].retailSubX, 0.01)
        assertEquals(22.40, igQuotes[0].totalSubX, 0.01)

        // 2. Parse NSE per-symbol bid details JSON with newer Day-3 QIB surge
        val nseBidJson = """
            {
              "dataList": [
                { "category": "Qualified Institutional Buyers (QIBs)", "noOfTime": "41.25" },
                { "category": "Non Institutional Investors (Above 10 Lakh)", "noOfTime": "36.40" },
                { "category": "Non Institutional Investors (Below 10 Lakh)", "noOfTime": "21.80" },
                { "category": "Retail Individual Investors (RIIs)", "noOfTime": "16.10" },
                { "category": "Total", "noOfTime": "29.95" }
              ]
            }
        """.trimIndent()
        val nseQuotes = subRepo.parseNseBidDetailsJson(
            rawJson = nseBidJson,
            companyName = sampleIpo.companyName,
            symbol = sampleIpo.shortCode,
            sourceName = "NSE Live Bid",
            priorityRank = -1,
            asOfLabel = "20 Nov, 02:35 PM",
        )
        assertEquals(1, nseQuotes.size)

        // 3. Parse Chittorgarh/IPOWatch HTML Subscription Table
        val htmlTable = """
            <table>
              <tr><th>Company Name</th><th>QIB</th><th>bNII</th><th>sNII</th><th>Retail</th><th>Total</th></tr>
              <tr><td>${sampleIpo.companyName}</td><td>38.10</td><td>34.00</td><td>20.50</td><td>15.90</td><td>27.80</td></tr>
            </table>
        """.trimIndent()
        val htmlQuotes = subRepo.parseHtmlSubscriptionTables(htmlTable, "Chittorgarh", 2, "20 Nov, 02:34 PM")
        assertEquals(1, htmlQuotes.size)

        // 4. Merge picks highest/freshest monotonic live multiples and persists into Room
        val merged = subRepo.mergeSubscriptionQuotes(igQuotes + nseQuotes + htmlQuotes)
        assertEquals(1, merged.size)
        assertEquals(41.25, merged[0].qibSubX, 0.01)
        assertEquals(36.40, merged[0].bNiiSubX, 0.01)
        assertEquals(21.80, merged[0].sNiiSubX, 0.01)
        assertEquals(16.10, merged[0].retailSubX, 0.01)
        assertEquals(29.95, merged[0].totalSubX, 0.01)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
                liveSubscriptionRepository = subRepo,
            )
            db.ipoDao().insertAll(listOf(sampleIpo))
            val updatedCount = repo.applySubscriptionSnapshotToDatabase(
                LiveSubscriptionSnapshot(
                    quotesByKey = merged.associateBy { it.normalizedKey },
                    allQuotes = merged,
                    activeSources = listOf("NSE Live Bid", "InvestorGain", "Chittorgarh"),
                    syncedAtTime = "20 Nov, 02:35 PM",
                ),
            )
            assertEquals(1, updatedCount)
            val persisted = db.ipoDao().observeIpoById(sampleIpo.id).first()!!
            assertEquals(41.25, persisted.qibSubX, 0.01)
            assertEquals(36.40, persisted.bNiiSubX, 0.01)
            assertEquals(21.80, persisted.sNiiSubX, 0.01)
            assertEquals(16.10, persisted.retailSubX, 0.01)
            assertEquals(29.95, persisted.totalSubX, 0.01)
            assertEquals("Live", persisted.dailySubscriptions.last().dayLabel)
            assertEquals(29.95, persisted.dailySubscriptions.last().totalX, 0.01)
        } finally {
            db.close()
        }

        // 5. IST Market hours check (Friday 2:30 PM IST = open; Sunday 2:30 PM IST = closed)
        val fridayOpenIst = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2026, Calendar.NOVEMBER, 20, 14, 30, 0)
        }.timeInMillis
        val sundayIst = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2026, Calendar.NOVEMBER, 22, 14, 30, 0)
        }.timeInMillis
        val lateNightIst = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2026, Calendar.NOVEMBER, 20, 21, 0, 0)
        }.timeInMillis
        assertTrue(LiveSubscriptionRepository.isMarketHoursIst(fridayOpenIst))
        assertFalse(LiveSubscriptionRepository.isMarketHoursIst(sundayIst))
        assertFalse(LiveSubscriptionRepository.isMarketHoursIst(lateNightIst))
    }

    @Test
    fun `ipo detail live subscription card displays refresh button live status strip and verify button`() {
        val sample = IpoSeedData.createInitialCatalog().first { it.status == IpoStatus.LIVE }
        var refreshClicks = 0

        composeTestRule.setContent {
            IpoPulseTheme {
                IpoDetailScreen(
                    ipo = sample,
                    selectedTab = DetailTab.SUBSCRIPTION,
                    onSelectTab = {},
                    onBack = {},
                    onUpdateLots = {},
                    onAdImpression = {},
                    subscriptionSyncState = SubscriptionSyncUiState(
                        isRefreshing = false,
                        lastSyncedLabel = "20 Nov, 02:35 PM",
                        sourceLabel = "NSE Live Bid • InvestorGain",
                        statusMessage = "Live bidding updated • 20 Nov, 02:35 PM",
                        isAutoPollingActive = true,
                    ),
                    onRefreshSubscription = { refreshClicks++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("detail_lazy_column")
            .performScrollToNode(hasTestTag("live_subscription_card"))
        composeTestRule.onNodeWithTag("refresh_live_subscription_button").assertIsDisplayed().performClick()
        assertEquals(1, refreshClicks)
        composeTestRule.onNodeWithTag("live_subscription_sync_status").assertIsDisplayed()
        composeTestRule.onNodeWithText("AUTO-LIVE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("verify_subscription_exchange_btn").assertExists()
    }

    @Test
    fun `admob resolves google test unit ids in debug and production ids in release`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(AdMobManager.TEST_BANNER_UNIT_ID, AdMobManager.resolveBannerUnitId(context, isDebug = true))
        assertEquals(AdMobManager.TEST_INTERSTITIAL_UNIT_ID, AdMobManager.resolveInterstitialUnitId(context, isDebug = true))
        assertEquals(AdMobManager.TEST_NATIVE_UNIT_ID, AdMobManager.resolveNativeUnitId(context, isDebug = true))
        assertEquals(AdMobManager.TEST_APP_OPEN_UNIT_ID, AdMobManager.resolveAppOpenUnitId(context, isDebug = true))

        assertEquals("ca-app-pub-3318993523524673/9107752028", AdMobManager.resolveBannerUnitId(context, isDebug = false))
        assertEquals("ca-app-pub-3318993523524673/8233654054", AdMobManager.resolveInterstitialUnitId(context, isDebug = false))
        assertEquals("ca-app-pub-3318993523524673/6776318332", AdMobManager.resolveNativeUnitId(context, isDebug = false))
        assertEquals("ca-app-pub-3318993523524673/8827766602", AdMobManager.resolveAppOpenUnitId(context, isDebug = false))
    }

    @Test
    fun `privacy policy discloses pan profiles biometric lock and independent status`() {
        composeTestRule.setContent {
            IpoPulseTheme {
                com.example.ui.screens.PrivacyPolicyScreen(onBack = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("privacy_policy_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("2. Private PAN Profiles & Allotment Checks").assertExists()
        composeTestRule.onNodeWithText("3. Biometric & Device Vault Lock").assertExists()
        composeTestRule.onNodeWithText("4. Market Data & Independent Status").assertExists()
    }
}









