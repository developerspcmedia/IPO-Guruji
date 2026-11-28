package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import com.example.data.local.IpoDatabase
import com.example.data.local.IpoSeedData
import com.example.data.model.BoardType
import com.example.data.model.IpoOverrideDocument
import com.example.data.model.IpoOverrideMerger
import com.example.data.model.IpoStatus
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.CloudIpoBundleSnapshot
import com.example.data.repository.GmpSyncSummary
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveGmpRepository
import com.example.data.repository.LiveIpoCloudRepository
import com.example.ui.screens.IpoBoardScreen
import com.example.ui.theme.IpoPulseTheme
import com.example.ui.viewmodel.IpoBoardUiState
import com.example.ui.viewmodel.IpoBoardViewModel
import com.google.firebase.Timestamp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveIpoCloudSyncTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `document mapping maps ipo_live_bundle fields manually and builds updatedAgoLabel`() {
        val lastSuccessMs = 1_764_225_000_000L // 2025-11-27T06:30:00Z
        val nowMs = lastSuccessMs + 15L * 60L * 1000L // 15 minutes later

        val rawDoc: Map<String, Any?> = mapOf(
            "version" to 1,
            "lastSuccessAt" to Timestamp(Date(lastSuccessMs)),
            "sourcesOk" to listOf("chittorgarh", "investorgain", "nse"),
            "sourcesFailed" to listOf("ipowatch"),
            "ipos" to listOf(
                mapOf(
                    "id" to "orion-semiconductors-mainboard",
                    "companyName" to "Orion Semiconductors Ltd",
                    "normalizedKey" to "orionsemiconductors",
                    "boardType" to "MAINBOARD",
                    "status" to "LIVE",
                    "openDate" to "25 Nov 2026",
                    "closeDate" to "27 Nov 2026",
                    "allotmentDate" to "28 Nov 2026",
                    "listingDate" to "02 Dec 2026",
                    "priceLow" to 240L,
                    "priceHigh" to 255L,
                    "lotSize" to 58L,
                    "issueSizeCr" to 1250.5,
                    "gmpRupees" to 68L,
                    "gmpUpdatedAtMs" to lastSuccessMs,
                    "qibSubX" to 14.2,
                    "bNiiSubX" to 22.8,
                    "sNiiSubX" to 16.4,
                    "retailSubX" to 9.75,
                    "employeeSubX" to 3.1,
                    "totalSubX" to 13.45,
                    "subUpdatedAtMs" to lastSuccessMs,
                    "registrarName" to "KFin Technologies Ltd",
                    "registrarUrl" to "https://ris.kfintech.com/ipostatus/",
                    "sources" to listOf("nse", "chittorgarh", "investorgain"),
                ),
                mapOf(
                    "id" to "nova-precision-sme",
                    "companyName" to "Nova Precision Tools Ltd",
                    "normalizedKey" to "novaprecisiontools",
                    "boardType" to "SME",
                    "status" to "UPCOMING",
                    "openDate" to "04 Dec 2026",
                    "closeDate" to "06 Dec 2026",
                    "allotmentDate" to "",
                    "listingDate" to "11 Dec 2026",
                    "priceLow" to 110,
                    "priceHigh" to 116,
                    "lotSize" to 1200,
                    "issueSizeCr" to 42.0,
                    "gmpRupees" to -5,
                    "qibSubX" to 0.0,
                    "bNiiSubX" to 0.0,
                    "sNiiSubX" to 0.0,
                    "retailSubX" to 0.0,
                    "employeeSubX" to 0.0,
                    "totalSubX" to 0.0,
                    "subUpdatedAtMs" to 0L,
                    "registrarName" to "Bigshare Services",
                    "registrarUrl" to "https://ipo.bigshareonline.com/IPO_Status.html",
                    "sources" to listOf("chittorgarh"),
                ),
            ),
        )

        val snapshot = LiveIpoCloudRepository.mapDocumentData(
            data = rawDoc,
            isFromCache = false,
            nowMs = nowMs,
        )

        assertTrue(snapshot.documentExists)
        assertFalse(snapshot.isFromCache)
        assertEquals(lastSuccessMs, snapshot.lastSuccessAtMs)
        assertEquals(listOf("chittorgarh", "investorgain", "nse"), snapshot.sourcesOk)
        assertEquals(listOf("ipowatch"), snapshot.sourcesFailed)
        assertEquals(2, snapshot.ipos.size)

        val mainboard = snapshot.ipos[0]
        assertEquals("orion-semiconductors-mainboard", mainboard.id)
        assertEquals("Orion Semiconductors Ltd", mainboard.companyName)
        assertEquals(BoardType.MAINBOARD, mainboard.boardType)
        assertEquals(IpoStatus.LIVE, mainboard.status)
        assertEquals("25 Nov 2026", mainboard.openDate)
        assertEquals("27 Nov 2026", mainboard.closeDate)
        assertEquals("28 Nov 2026", mainboard.allotmentDate)
        assertEquals("02 Dec 2026", mainboard.listingDate)
        assertEquals(240, mainboard.priceLow)
        assertEquals(255, mainboard.priceHigh)
        assertEquals(58, mainboard.lotSize)
        assertEquals(1250.5, mainboard.issueSizeCr, 0.001)
        assertEquals(68, mainboard.gmpRupees)
        assertEquals(14.2, mainboard.qibSubX, 0.001)
        assertEquals(22.8, mainboard.bNiiSubX, 0.001)
        assertEquals(16.4, mainboard.sNiiSubX, 0.001)
        assertEquals(9.75, mainboard.retailSubX, 0.001)
        assertEquals(3.1, mainboard.employeeSubX, 0.001)
        assertEquals(13.45, mainboard.totalSubX, 0.001)
        assertEquals("KFin Technologies Ltd", mainboard.registrarName)
        assertEquals("https://ris.kfintech.com/ipostatus/", mainboard.registrarUrl)
        assertEquals("Data updated 15 min ago", mainboard.updatedAgoLabel)

        val sme = snapshot.ipos[1]
        assertEquals(BoardType.SME, sme.boardType)
        assertEquals(IpoStatus.UPCOMING, sme.status)
        assertEquals("TBA", sme.allotmentDate)
        assertEquals(-5, sme.gmpRupees)
        assertEquals("Data updated 15 min ago", sme.updatedAgoLabel)

        // Direct device scraping must be disabled by default
        assertFalse(LiveGmpRepository().enableNetworkFetch)
    }

    @Test
    fun `empty snapshot and empty cache snapshot do not wipe Room and userLots and watch state are preserved`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.ipoDao()
            val existingRow = IpoSeedData.createInitialCatalog().first().copy(
                id = "orion-semiconductors-mainboard",
                companyName = "Orion Semiconductors Ltd",
                boardType = BoardType.MAINBOARD,
                gmpRupees = 20,
                userLots = 7,
                isWatchlisted = true,
            )
            dao.replaceCatalog(listOf(existingRow), listOf(existingRow.id))

            val repo = IpoRepository(
                ipoDao = dao,
                liveIpoCloudRepository = LiveIpoCloudRepository(db = null),
            )

            // 1. Empty snapshot (not from cache) must NOT wipe Room
            repo.applyCloudBundleToDatabase(
                snapshot = CloudIpoBundleSnapshot(
                    ipos = emptyList(),
                    lastSuccessAtMs = 0L,
                    sourcesOk = emptyList(),
                    sourcesFailed = emptyList(),
                    isFromCache = false,
                    documentExists = true,
                ),
                nowMs = 1_764_225_000_000L,
            )
            assertEquals(1, dao.getAllSnapshot().size)
            assertEquals(7, dao.getAllSnapshot().first().userLots)
            assertTrue(dao.getAllSnapshot().first().isWatchlisted)

            // 2. Empty fromCache snapshot must NOT wipe Room
            repo.applyCloudBundleToDatabase(
                snapshot = CloudIpoBundleSnapshot(
                    ipos = emptyList(),
                    lastSuccessAtMs = 0L,
                    sourcesOk = emptyList(),
                    sourcesFailed = emptyList(),
                    isFromCache = true,
                    documentExists = false,
                ),
                nowMs = 1_764_225_600_000L,
            )
            assertEquals(1, dao.getAllSnapshot().size)

            // 3. Valid cloud snapshot updates fields while preserving userLots and isWatchlisted
            val bundleTime = 1_764_225_000_000L
            val incomingEntity = existingRow.copy(
                gmpRupees = 95,
                totalSubX = 18.4,
                userLots = 1, // default from cloud
                isWatchlisted = false, // default from cloud
            )
            repo.applyCloudBundleToDatabase(
                snapshot = CloudIpoBundleSnapshot(
                    ipos = listOf(incomingEntity),
                    lastSuccessAtMs = bundleTime,
                    sourcesOk = listOf("nse", "investorgain"),
                    sourcesFailed = emptyList(),
                    isFromCache = false,
                    documentExists = true,
                ),
                nowMs = bundleTime + 5L * 60L * 1000L,
            )

            val saved = dao.getAllSnapshot().single()
            assertEquals(95, saved.gmpRupees)
            assertEquals(18.4, saved.totalSubX, 0.001)
            assertEquals(7, saved.userLots)
            assertTrue(saved.isWatchlisted)
            assertEquals("Data updated 5 min ago", saved.updatedAgoLabel)

            // 4. Admin overrides still win over cloud values
            val override = IpoOverrideDocument(
                id = saved.id,
                targetIpoId = saved.id,
                companyName = saved.companyName,
                gmpRupees = 140,
                published = true,
            )
            val merged = IpoOverrideMerger.applyOverridesToCatalog(listOf(saved), listOf(override))
            assertEquals(140, merged.single().gmpRupees)
        } finally {
            db.close()
        }
    }

    @Test
    fun `10-minute app-open refresh guard and 1-minute manual throttle limit Firestore reads`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            var nowMs = 1_764_225_000_000L
            val fetchCount = AtomicInteger(0)

            val sampleEntity = IpoSeedData.createInitialCatalog().first().copy(
                id = "alpha-cloud-mainboard",
                companyName = "Alpha Cloud Tech Ltd",
                boardType = BoardType.MAINBOARD,
            )
            val fakeCloudRepo = LiveIpoCloudRepository(
                db = null,
                clockMs = { nowMs },
                fetchOverride = {
                    val count = fetchCount.incrementAndGet()
                    CloudIpoBundleSnapshot(
                        ipos = listOf(sampleEntity.copy(gmpRupees = 50 + count)),
                        lastSuccessAtMs = nowMs - 2L * 60L * 1000L,
                        sourcesOk = listOf("chittorgarh"),
                        sourcesFailed = emptyList(),
                        isFromCache = false,
                        documentExists = true,
                    )
                },
            )

            val repo = IpoRepository(
                ipoDao = db.ipoDao(),
                liveIpoCloudRepository = fakeCloudRepo,
                clockMs = { nowMs },
            )

            // First app-open sync fetches from Firestore
            val first = repo.syncWithLiveGmp(forceManual = false)
            assertFalse(first.skippedByRefreshGuard)
            assertEquals(1, fetchCount.get())

            // App-open sync 5 minutes later MUST be skipped (within 10-minute guard)
            nowMs += 5L * 60L * 1000L
            val withinGuard = repo.syncWithLiveGmp(forceManual = false)
            assertTrue(withinGuard.skippedByRefreshGuard)
            assertEquals(1, fetchCount.get())

            // App-open sync after >10 minutes (at +11 min total) fetches again
            nowMs += 6L * 60L * 1000L
            val afterTenMin = repo.syncWithLiveGmp(forceManual = false)
            assertFalse(afterTenMin.skippedByRefreshGuard)
            assertEquals(2, fetchCount.get())

            // Manual refresh works immediately (first manual pull)
            val firstManual = repo.syncWithLiveGmp(forceManual = true)
            assertFalse(firstManual.skippedByRefreshGuard)
            assertEquals(3, fetchCount.get())

            // Second manual refresh 30 seconds later MUST be throttled (1-minute manual limit)
            nowMs += 30_000L
            val throttledManual = repo.syncWithLiveGmp(forceManual = true)
            assertTrue(throttledManual.skippedByRefreshGuard)
            assertEquals(3, fetchCount.get())

            // Manual refresh after >1 minute succeeds
            nowMs += 35_000L
            val allowedManual = repo.syncWithLiveGmp(forceManual = true)
            assertFalse(allowedManual.skippedByRefreshGuard)
            assertEquals(4, fetchCount.get())
        } finally {
            db.close()
        }
    }

    @Test
    fun `delayed data notice appears when lastSuccessAt is older than 3 hours and stays hidden when fresh`() {
        val baseNowMs = 1_764_240_000_000L
        val fifteenMinAgo = baseNowMs - 15L * 60L * 1000L
        val twoHoursFiftyMinAgo = baseNowMs - (2L * 60L + 50L) * 60L * 1000L
        val fourHoursAgo = baseNowMs - 4L * 60L * 60L * 1000L

        assertEquals("Data updated 15 min ago", LiveIpoCloudRepository.formatUpdatedAgoLabel(fifteenMinAgo, baseNowMs))
        assertFalse(LiveIpoCloudRepository.isDataDelayed(fifteenMinAgo, baseNowMs))
        assertFalse(LiveIpoCloudRepository.isDataDelayed(twoHoursFiftyMinAgo, baseNowMs))
        assertTrue(LiveIpoCloudRepository.isDataDelayed(fourHoursAgo, baseNowMs))
        assertEquals("Data updated 4 hr ago", LiveIpoCloudRepository.formatUpdatedAgoLabel(fourHoursAgo, baseNowMs))

        // Verify ViewModel sets delayed flag properly from GmpSyncSummary
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, IpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vmStore = androidx.lifecycle.ViewModelStore()
        try {
            val vm = androidx.lifecycle.ViewModelProvider(
                vmStore,
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return IpoBoardViewModel(
                            ipoRepository = IpoRepository(ipoDao = db.ipoDao()),
                            preferencesRepository = UserPreferencesRepository(context),
                            clockMs = { baseNowMs },
                        ) as T
                    }
                },
            )[IpoBoardViewModel::class.java]
            vm.applyGmpSummary(
                gmpSummary = GmpSyncSummary(
                    syncedAtTime = "Data updated 15 min ago",
                    updatedExistingCount = 3,
                    discoveredLiveCount = 0,
                    activeSources = listOf("chittorgarh"),
                    isLiveFromNetwork = true,
                    dataBundleLastSuccessAtMs = fifteenMinAgo,
                ),
                manualTap = false,
            )
            assertEquals("Data updated 15 min ago", vm.currentLastSyncedLabelForTest())
            assertFalse(vm.currentIsDataDelayedForTest())

            vm.applyGmpSummary(
                gmpSummary = GmpSyncSummary(
                    syncedAtTime = "Data updated 4 hr ago",
                    updatedExistingCount = 3,
                    discoveredLiveCount = 0,
                    activeSources = listOf("chittorgarh"),
                    isLiveFromNetwork = true,
                    dataBundleLastSuccessAtMs = fourHoursAgo,
                ),
                manualTap = false,
            )
            assertEquals("Data updated 4 hr ago", vm.currentLastSyncedLabelForTest())
            assertTrue(vm.currentIsDataDelayedForTest())
        } finally {
            vmStore.clear()
            db.close()
        }
    }

    @Test
    fun `IpoBoardScreen renders delayed data notice only when isDataDelayed is true`() {
        val sampleIpos = IpoSeedData.createInitialCatalog().take(2)
        val delayedState = androidx.compose.runtime.mutableStateOf(
            IpoBoardUiState(
                allIpos = sampleIpos,
                filteredIpos = sampleIpos,
                lastSyncedLabel = "Data updated 12 min ago",
                isDataDelayed = false,
                isLoading = false,
            ),
        )

        composeRule.setContent {
            IpoPulseTheme {
                IpoBoardScreen(
                    uiState = delayedState.value,
                    onSelectBoardFilter = {},
                    onSelectTimelineTab = {},
                    onSelectStatusFilter = {},
                    onSearchQueryChange = {},
                    onToggleSearch = {},
                    onRefresh = {},
                    onRefreshGmp = {},
                    onOpenRadar = {},
                    onOpenSettings = {},
                    onIpoClick = {},
                    onAdImpression = {},
                )
            }
        }

        composeRule.onNodeWithText("Data updated 12 min ago").assertIsDisplayed()
        composeRule.onNodeWithTag("delayed_data_notice").assertDoesNotExist()

        composeRule.runOnUiThread {
            delayedState.value = delayedState.value.copy(
                lastSyncedLabel = "Data updated 4 hr ago",
                isDataDelayed = true,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Data updated 4 hr ago").assertIsDisplayed()
        composeRule.onNodeWithTag("delayed_data_notice").assertIsDisplayed()
        composeRule.onNodeWithText("Data may be delayed").assertIsDisplayed()
    }
}
