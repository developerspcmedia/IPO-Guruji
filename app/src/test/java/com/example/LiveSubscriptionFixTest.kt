package com.example

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.data.local.IpoDatabase
import com.example.data.local.IpoSeedData
import com.example.data.model.BoardType
import com.example.data.model.DaySubscription
import com.example.data.model.IpoStatus
import com.example.data.model.LiveApplicationCount
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.GmpSyncSummary
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveGmpRepository
import com.example.data.repository.LiveGmpSnapshot
import com.example.data.repository.LiveSubscriptionQuote
import com.example.data.repository.LiveSubscriptionRepository
import com.example.data.repository.LiveSubscriptionSnapshot
import com.example.data.repository.SubscriptionSourceStatus
import com.example.ui.viewmodel.IpoBoardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSubscriptionFixTest {

    private fun istTimeMs(
        year: Int,
        monthZeroBased: Int,
        day: Int,
        hourOfDay: Int,
        minute: Int,
    ): Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        set(year, monthZeroBased, day, hourOfDay, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `a - subscription snapshot is saved to Room even when all GMP sources fail`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.ipoDao()
            val seedRow = IpoSeedData.createInitialCatalog().first().copy(
                id = "live_acme_solar",
                companyName = "Acme Solar Systems Ltd",
                qibSubX = 0.20,
                bNiiSubX = 0.40,
                sNiiSubX = 0.30,
                retailSubX = 0.50,
                totalSubX = 0.35,
                subscriptionUpdatedAtMs = 0L,
            )
            dao.replaceCatalog(listOf(seedRow), listOf(seedRow.id))

            val subRepo = LiveSubscriptionRepository(enableNetworkFetch = false)
            val repo = IpoRepository(
                ipoDao = dao,
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
                liveSubscriptionRepository = subRepo,
            )

            val failedGmpSnapshot = LiveGmpSnapshot(
                quotesByKey = emptyMap(),
                allQuotes = emptyList(),
                activeSources = emptyList(),
                syncedAtTime = "27 Nov, 01:30 PM",
            )
            val fetchedAtMs = istTimeMs(2026, Calendar.NOVEMBER, 27, 13, 30)
            val subQuote = LiveSubscriptionQuote(
                companyName = "Acme Solar Systems Ltd",
                normalizedKey = subRepo.normalizeCompanyKey("Acme Solar Systems Ltd"),
                boardType = seedRow.boardType,
                qibSubX = 4.10,
                bNiiSubX = 7.25,
                sNiiSubX = 5.15,
                niiTotalSubX = 6.55,
                retailSubX = 8.40,
                totalSubX = 6.95,
                sourceName = "NSE India",
                priorityRank = LiveSubscriptionRepository.PRIORITY_NSE_CURRENT,
                asOfLabel = "27 Nov, 01:30 PM",
                fetchedAtMs = fetchedAtMs,
            )
            val subSnapshot = LiveSubscriptionSnapshot(
                quotesByKey = mapOf(subQuote.normalizedKey to subQuote),
                allQuotes = listOf(subQuote),
                activeSources = listOf("NSE India"),
                syncedAtTime = "27 Nov, 01:30 PM",
                syncedAtMs = fetchedAtMs,
                sourceStatuses = listOf(
                    SubscriptionSourceStatus("NSE India", ok = true),
                    SubscriptionSourceStatus("Chittorgarh", ok = false, reason = "HTTP 503"),
                ),
            )

            val existingById = dao.getAllSnapshot().associateBy { it.id }
            val summary = repo.applySnapshotToDatabase(
                existingById = existingById,
                gmpSnapshot = failedGmpSnapshot,
                subscriptionSnapshot = subSnapshot,
            )

            assertTrue(summary.isLiveFromNetwork)
            assertEquals(listOf("NSE India"), summary.activeSources)
            assertEquals(2, summary.subscriptionSourceStatuses.size)

            val savedRow = dao.getAllSnapshot().first { it.id == seedRow.id }
            assertEquals(4.10, savedRow.qibSubX, 0.001)
            assertEquals(8.40, savedRow.retailSubX, 0.001)
            assertEquals(6.95, savedRow.totalSubX, 0.001)
            assertEquals(fetchedAtMs, savedRow.subscriptionUpdatedAtMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun `b - older higher-priority quote does not override fresher quote and categories are never mixed`() {
        val repo = LiveSubscriptionRepository(enableNetworkFetch = false)
        val olderHigherPriority = LiveSubscriptionQuote(
            companyName = "Solaris Precision Ltd",
            normalizedKey = repo.normalizeCompanyKey("Solaris Precision Ltd"),
            boardType = BoardType.MAINBOARD,
            qibSubX = 1.20,
            bNiiSubX = 2.50,
            sNiiSubX = 1.80,
            niiTotalSubX = 2.27,
            retailSubX = 3.10,
            employeeSubX = 1.75,
            totalSubX = 2.40,
            sourceName = "NSE Live Bid",
            priorityRank = LiveSubscriptionRepository.PRIORITY_NSE_LIVE_BID,
            asOfLabel = "27 Nov, 11:00 AM",
            fetchedAtMs = 1_000_000L,
            dailyHistory = listOf(
                DaySubscription("Day 1", 0.50, 1.00, 1.50, 1.10, "25 Nov", 1.10, 0.80),
                DaySubscription("Day 2", 1.20, 2.27, 3.10, 2.40, "26 Nov", 2.50, 1.80),
            ),
            applicationCounts = listOf(
                LiveApplicationCount("retail", "Retail", 100_000L, 310_000L, 3.10),
            ),
        )
        val fresherLowerPriority = LiveSubscriptionQuote(
            companyName = "Solaris Precision IPO",
            normalizedKey = repo.normalizeCompanyKey("Solaris Precision IPO"),
            boardType = BoardType.MAINBOARD,
            qibSubX = 4.50,
            bNiiSubX = 8.00,
            sNiiSubX = 5.40,
            niiTotalSubX = 7.13,
            retailSubX = 6.90,
            employeeSubX = 0.0,
            totalSubX = 6.25,
            sourceName = "InvestorGain",
            priorityRank = LiveSubscriptionRepository.PRIORITY_INVESTORGAIN_SUMMARY,
            asOfLabel = "27 Nov, 02:30 PM",
            fetchedAtMs = 2_000_000L,
        )

        val merged = repo.mergeSubscriptionQuotes(listOf(olderHigherPriority, fresherLowerPriority))
        assertEquals(1, merged.size)
        val winner = merged.first()

        // Fresher quote wins completely for category & total numbers
        assertEquals("InvestorGain", winner.sourceName)
        assertEquals(4.50, winner.qibSubX, 0.001)
        assertEquals(8.00, winner.bNiiSubX, 0.001)
        assertEquals(5.40, winner.sNiiSubX, 0.001)
        assertEquals(6.90, winner.retailSubX, 0.001)
        assertEquals(6.25, winner.totalSubX, 0.001)
        // Category fields from the non-winning quote MUST NOT be mixed in
        assertEquals(0.0, winner.employeeSubX, 0.001)
        // Secondary quote MAY still fill dailyHistory and applicationCounts
        assertEquals(2, winner.dailyHistory.size)
        assertEquals(1, winner.applicationCounts.size)
        assertEquals(310_000L, winner.applicationCounts.first().appliedApplications)
    }

    @Test
    fun `c - sync label preserves last successful time and never stamps fake time when all sources fail`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val ipoRepo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveGmpRepository = LiveGmpRepository(enableNetworkFetch = false),
                liveSubscriptionRepository = LiveSubscriptionRepository(enableNetworkFetch = false),
            )
            val prefsRepo = UserPreferencesRepository(context)
            val vm = IpoBoardViewModel(
                ipoRepository = ipoRepo,
                preferencesRepository = prefsRepo,
            )

            vm.applyGmpSummary(
                gmpSummary = GmpSyncSummary(
                    syncedAtTime = "27 Nov, 10:30 AM",
                    updatedExistingCount = 2,
                    discoveredLiveCount = 0,
                    activeSources = listOf("NSE India"),
                    isLiveFromNetwork = true,
                ),
                manualTap = false,
            )
            assertEquals("27 Nov, 10:30 AM", vm.currentLastSyncedLabelForTest())

            vm.applyGmpSummary(
                gmpSummary = GmpSyncSummary(
                    syncedAtTime = "27 Nov, 11:15 AM",
                    updatedExistingCount = 0,
                    discoveredLiveCount = 0,
                    activeSources = emptyList(),
                    isLiveFromNetwork = false,
                ),
                manualTap = true,
            )
            val labelAfterFailure = vm.currentLastSyncedLabelForTest()
            assertEquals("Last updated 27 Nov, 10:30 AM (couldn't refresh now)", labelAfterFailure)
            assertFalse(labelAfterFailure.contains("11:15 AM"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `d - polling decisions use calendar dates and IST windows rather than stored status`() {
        val wed1130Ist = istTimeMs(2026, Calendar.NOVEMBER, 25, 11, 30)
        val fri1830Ist = istTimeMs(2026, Calendar.NOVEMBER, 27, 18, 30)
        val fri2130Ist = istTimeMs(2026, Calendar.NOVEMBER, 27, 21, 30)
        val sat1130Ist = istTimeMs(2026, Calendar.NOVEMBER, 28, 11, 30)
        val mon0930Ist = istTimeMs(2026, Calendar.NOVEMBER, 30, 9, 30)
        val mon0920Ist = istTimeMs(2026, Calendar.NOVEMBER, 30, 9, 20)

        // Open by dates (25-27 Nov 2026), even though stored status is stale PRE_APPLY
        val openByDatesStaleStatus = IpoSeedData.createInitialCatalog().first().copy(
            status = IpoStatus.PRE_APPLY,
            openDate = "25 Nov 2026",
            closeDate = "27 Nov 2026",
            listingDate = "02 Dec 2026",
        )
        assertEquals(
            LiveSubscriptionRepository.MARKET_POLL_INTERVAL_MS,
            LiveSubscriptionRepository.resolveDetailPollIntervalMs(
                openDate = openByDatesStaleStatus.openDate,
                closeDate = openByDatesStaleStatus.closeDate,
                listingDate = openByDatesStaleStatus.listingDate,
                status = openByDatesStaleStatus.status,
                nowMs = wed1130Ist,
            ),
        )
        assertTrue(LiveSubscriptionRepository.shouldPollBoard(listOf(openByDatesStaleStatus), wed1130Ist))

        // Closed by dates (10-12 Nov 2026), even though stored status is stale LIVE -> must NOT poll
        val closedByDatesStaleLive = openByDatesStaleStatus.copy(
            status = IpoStatus.LIVE,
            openDate = "10 Nov 2026",
            closeDate = "12 Nov 2026",
            listingDate = "17 Nov 2026",
        )
        assertNull(
            LiveSubscriptionRepository.resolveDetailPollIntervalMs(
                openDate = closedByDatesStaleLive.openDate,
                closeDate = closedByDatesStaleLive.closeDate,
                listingDate = closedByDatesStaleLive.listingDate,
                status = closedByDatesStaleLive.status,
                nowMs = wed1130Ist,
            ),
        )
        assertFalse(LiveSubscriptionRepository.shouldPollBoard(listOf(closedByDatesStaleLive), wed1130Ist))

        // Close day evening (6:30 PM IST) polls every 10 min until 9:00 PM IST, then stops
        assertEquals(
            LiveSubscriptionRepository.CLOSE_DAY_EVENING_POLL_INTERVAL_MS,
            LiveSubscriptionRepository.resolveDetailPollIntervalMs(
                openDate = openByDatesStaleStatus.openDate,
                closeDate = openByDatesStaleStatus.closeDate,
                listingDate = openByDatesStaleStatus.listingDate,
                status = openByDatesStaleStatus.status,
                nowMs = fri1830Ist,
            ),
        )
        assertNull(
            LiveSubscriptionRepository.resolveDetailPollIntervalMs(
                openDate = openByDatesStaleStatus.openDate,
                closeDate = openByDatesStaleStatus.closeDate,
                listingDate = openByDatesStaleStatus.listingDate,
                status = openByDatesStaleStatus.status,
                nowMs = fri2130Ist,
            ),
        )

        // Weekends never poll
        assertNull(
            LiveSubscriptionRepository.resolveDetailPollIntervalMs(
                openDate = openByDatesStaleStatus.openDate,
                closeDate = openByDatesStaleStatus.closeDate,
                listingDate = openByDatesStaleStatus.listingDate,
                status = IpoStatus.LIVE,
                nowMs = sat1130Ist,
            ),
        )
        assertFalse(LiveSubscriptionRepository.shouldPollBoard(listOf(openByDatesStaleStatus), sat1130Ist))

        // Next trading morning (~9:30 AM IST Monday after Friday close) runs once until updated that morning
        assertTrue(
            LiveSubscriptionRepository.shouldRunNextMorningCatchup(
                closeDate = "27 Nov 2026",
                subscriptionUpdatedAtMs = fri1830Ist,
                nowMs = mon0930Ist,
            ),
        )
        assertFalse(
            LiveSubscriptionRepository.shouldRunNextMorningCatchup(
                closeDate = "27 Nov 2026",
                subscriptionUpdatedAtMs = mon0920Ist,
                nowMs = mon0930Ist,
            ),
        )
    }

    @Test
    fun `e - regressive same-day totalSubX and negative or NaN quotes are rejected`() {
        val repo = LiveSubscriptionRepository(enableNetworkFetch = false)
        val wed1100Ist = istTimeMs(2026, Calendar.NOVEMBER, 25, 11, 0)
        val wed1400Ist = istTimeMs(2026, Calendar.NOVEMBER, 25, 14, 0)

        val existing = IpoSeedData.createInitialCatalog().first().copy(
            companyName = "Apex Robotics Ltd",
            qibSubX = 5.50,
            bNiiSubX = 9.00,
            sNiiSubX = 6.20,
            retailSubX = 11.40,
            totalSubX = 8.50,
            subscriptionUpdatedAtMs = wed1100Ist,
            subscriptionSourceLabel = "NSE Live Bid • 25 Nov, 11:00 AM",
        )

        // Lower totalSubX on the SAME bidding day must be rejected
        val lowerSameDayQuote = LiveSubscriptionQuote(
            companyName = "Apex Robotics Ltd",
            normalizedKey = repo.normalizeCompanyKey("Apex Robotics Ltd"),
            boardType = existing.boardType,
            qibSubX = 3.00,
            bNiiSubX = 4.00,
            sNiiSubX = 3.50,
            retailSubX = 6.10,
            totalSubX = 5.20,
            sourceName = "Chittorgarh",
            priorityRank = LiveSubscriptionRepository.PRIORITY_CHITTORGARH_SUMMARY,
            fetchedAtMs = wed1400Ist,
        )
        val afterLower = repo.applySubscriptionToEntity(existing, lowerSameDayQuote, "25 Nov, 02:00 PM", wed1400Ist)
        assertEquals(8.50, afterLower.totalSubX, 0.001)
        assertEquals(5.50, afterLower.qibSubX, 0.001)
        assertEquals(wed1100Ist, afterLower.subscriptionUpdatedAtMs)

        // Negative or NaN quotes must be rejected in both merge and apply
        val negativeQuote = lowerSameDayQuote.copy(retailSubX = -2.0, totalSubX = 12.0)
        val nanQuote = lowerSameDayQuote.copy(totalSubX = Double.NaN)
        assertTrue(repo.mergeSubscriptionQuotes(listOf(negativeQuote, nanQuote)).isEmpty())
        assertEquals(8.50, repo.applySubscriptionToEntity(existing, negativeQuote, "25 Nov, 02:00 PM", wed1400Ist).totalSubX, 0.001)
        assertEquals(8.50, repo.applySubscriptionToEntity(existing, nanQuote, "25 Nov, 02:00 PM", wed1400Ist).totalSubX, 0.001)

        // Higher totalSubX on the same bidding day is accepted and stamps subscriptionUpdatedAtMs
        val higherSameDayQuote = lowerSameDayQuote.copy(qibSubX = 7.20, retailSubX = 13.80, totalSubX = 10.60)
        val afterHigher = repo.applySubscriptionToEntity(existing, higherSameDayQuote, "25 Nov, 02:00 PM", wed1400Ist)
        assertEquals(10.60, afterHigher.totalSubX, 0.001)
        assertEquals(7.20, afterHigher.qibSubX, 0.001)
        assertEquals(wed1400Ist, afterHigher.subscriptionUpdatedAtMs)
    }

    @Test
    fun `source status formatter and MIGRATION_8_9 work cleanly`() {
        val formatted = LiveSubscriptionRepository.formatSourceStatuses(
            listOf(
                SubscriptionSourceStatus("NSE India", ok = true),
                SubscriptionSourceStatus("Chittorgarh", ok = false, reason = "HTTP 503"),
                SubscriptionSourceStatus("Chittorgarh SME", ok = false, reason = "Timeout"),
                SubscriptionSourceStatus("InvestorGain", ok = true),
                SubscriptionSourceStatus("IPOWatch", ok = false, reason = "No rows parsed"),
            ),
        )
        assertEquals("Sources: NSE ✓, Chittorgarh ✗, InvestorGain ✓, IPOWatch ✗", formatted)

        val context = RuntimeEnvironment.getApplication()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `ipos` (`id` TEXT NOT NULL PRIMARY KEY, `companyName` TEXT NOT NULL, `subscriptionSourceLabel` TEXT NOT NULL DEFAULT '')",
                            )
                            db.execSQL("INSERT INTO `ipos` (`id`, `companyName`) VALUES ('ipo_1', 'Test Co')")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val sqliteDb = helper.writableDatabase
            IpoDatabase.MIGRATION_8_9.migrate(sqliteDb)
            sqliteDb.query("SELECT `subscriptionUpdatedAtMs` FROM `ipos` WHERE `id` = 'ipo_1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        } finally {
            helper.close()
        }
    }
}
