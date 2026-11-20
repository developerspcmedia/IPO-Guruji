package com.example.data.repository

import com.example.data.local.IpoDao
import com.example.data.local.IpoSeedData
import com.example.data.model.BoardType
import com.example.data.model.GmpPoint
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.data.model.ValuationMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GmpSyncSummary(
    val syncedAtTime: String,
    val updatedExistingCount: Int,
    val discoveredLiveCount: Int,
    val activeSources: List<String>,
    val isLiveFromNetwork: Boolean,
    val previousIpos: List<IpoEntity> = emptyList(),
    val updatedIpos: List<IpoEntity> = emptyList(),
    val excludedUnconfirmedCount: Int = 0,
    val verifiedProviderCount: Int = activeSources
        .mapNotNull { IpoFilingVerifier.providerFamily(it).takeIf { f -> f.isNotBlank() } }
        .distinct()
        .size,
)

data class SubscriptionRefreshResult(
    val updated: Boolean,
    val syncedAtLabel: String,
    val sourceLabel: String,
    val statusMessage: String,
    val matchedQuotesCount: Int = 0,
)

class IpoRepository(
    private val ipoDao: IpoDao,
    private val liveGmpRepository: LiveGmpRepository = LiveGmpRepository(),
    private val liveSubscriptionRepository: LiveSubscriptionRepository = LiveSubscriptionRepository(
        enableNetworkFetch = liveGmpRepository.enableNetworkFetch,
    ),
) {
    fun observeAllIpos(): Flow<List<IpoEntity>> = ipoDao.observeAllIpos()

    fun observeIpoById(id: String): Flow<IpoEntity?> = ipoDao.observeIpoById(id)

    fun isSubscriptionCacheStale(
        maxAgeMs: Long = LiveSubscriptionRepository.DEFAULT_STALE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = liveSubscriptionRepository.isSnapshotStale(maxAgeMs = maxAgeMs, nowMs = nowMs)

    suspend fun ensureSeeded() {
        syncCatalogOnAppOpen()
    }

    suspend fun syncCatalogOnAppOpen(): String = syncWithLiveGmp().syncedAtTime

    suspend fun syncWithLiveGmp(): GmpSyncSummary = withContext(Dispatchers.IO) {
        val existingById = ipoDao.getAllSnapshot().associateBy { it.id }
        val (gmpSnapshot, subSnapshot) = coroutineScope {
            val gmpDeferred = async { liveGmpRepository.fetchLatestGmps() }
            val subDeferred = async { liveSubscriptionRepository.fetchLatestSubscriptions(forceRefresh = true) }
            gmpDeferred.await() to subDeferred.await()
        }
        applySnapshotToDatabase(
            existingById = existingById,
            gmpSnapshot = gmpSnapshot,
            subscriptionSnapshot = subSnapshot,
        )
    }

    suspend fun refreshLiveSubscriptionForIpo(
        ipoId: String,
        force: Boolean = true,
    ): SubscriptionRefreshResult = withContext(Dispatchers.IO) {
        val allExisting = ipoDao.getAllSnapshot()
        val target = allExisting.firstOrNull { it.id == ipoId }
        val snapshot = liveSubscriptionRepository.fetchLatestSubscriptions(
            forceRefresh = force,
            targetSymbol = target?.shortCode,
            targetCompanyName = target?.companyName,
        )
        val matchedCount = applySubscriptionSnapshotToDatabase(snapshot)
        val updatedTarget = ipoDao.getAllSnapshot().firstOrNull { it.id == ipoId }
        val targetMatched = target != null && liveSubscriptionRepository.findMatchingQuote(target, snapshot.allQuotes) != null
        val sourceLabel = snapshot.activeSources
            .take(3)
            .joinToString(" • ")
            .ifBlank { "NSE / Chittorgarh / InvestorGain" }

        val message = when {
            targetMatched -> "Live bidding updated • ${snapshot.syncedAtTime}"
            matchedCount > 0 -> "Synced $matchedCount active IPOs • No new bid change for this issue yet"
            updatedTarget != null && updatedTarget.status == IpoStatus.UPCOMING ->
                "Bidding opens on ${updatedTarget.openDate} • Live updates start at 10:00 AM IST"
            snapshot.activeSources.isNotEmpty() ->
                "Checked $sourceLabel • Awaiting latest exchange update"
            else -> "Exchange feeds busy • Tap Verify on NSE/BSE or retry shortly"
        }

        SubscriptionRefreshResult(
            updated = targetMatched || matchedCount > 0,
            syncedAtLabel = snapshot.syncedAtTime,
            sourceLabel = sourceLabel,
            statusMessage = message,
            matchedQuotesCount = matchedCount,
        )
    }

    internal suspend fun applySubscriptionSnapshotToDatabase(
        snapshot: LiveSubscriptionSnapshot,
    ): Int {
        if (snapshot.allQuotes.isEmpty()) return 0
        val existing = ipoDao.getAllSnapshot()
        if (existing.isEmpty()) return 0
        val updatedEntities = mutableListOf<IpoEntity>()
        for (entity in existing) {
            val match = liveSubscriptionRepository.findMatchingQuote(entity, snapshot.allQuotes)
            if (match != null) {
                updatedEntities.add(
                    liveSubscriptionRepository.applySubscriptionToEntity(
                        entity = entity,
                        quote = match,
                        syncTime = snapshot.syncedAtTime,
                    ),
                )
            }
        }
        if (updatedEntities.isNotEmpty()) {
            ipoDao.insertAll(updatedEntities)
        }
        return updatedEntities.size
    }

    internal suspend fun applySnapshotToDatabase(
        existingById: Map<String, IpoEntity>,
        gmpSnapshot: LiveGmpSnapshot,
        subscriptionSnapshot: LiveSubscriptionSnapshot? = null,
    ): GmpSyncSummary {
        val syncTime = gmpSnapshot.syncedAtTime
        val todayShortLabel = SimpleDateFormat("dd MMM", Locale.US).format(Date())
        val seedCatalog = IpoSeedData.createInitialCatalog()
            .filter { IpoFilingVerifier.isVerifiedFiledEntity(it) }

        // Merge all structured live quotes across independent providers (excludes bare RSS-only snippets)
        val mergedLiveQuotes = liveGmpRepository.mergeQuotesWithoutDuplicates(
            gmpSnapshot.allQuotes.filter { quote -> quote.priorityRank <= 12 },
        )

        // Cross-verify every IPO across >= 2 independent sources and require confirmed filing details
        // (confirmed lot size, issue size, offer price band, and official filing schedule dates).
        val verifiedLiveQuotes = mergedLiveQuotes.filter { quote ->
            IpoFilingVerifier.verifyQuote(quote).isVerified
        }
        val excludedUnconfirmedCount = (mergedLiveQuotes.size - verifiedLiveQuotes.size).coerceAtLeast(0)

        var updatedCount = 0
        var discoveredCount = 0
        val rawCatalog: List<IpoEntity>

        if (verifiedLiveQuotes.size >= 3) {
            // Authoritative multi-source verified live mode: reserve dedicated slots for BOTH Current/Upcoming and Listed IPOs
            val (listedQuotes, activeQuotes) = verifiedLiveQuotes.partition { quote ->
                IpoDateUtils.isCloseDatePassed(
                    closeDate = quote.closeDate,
                    status = quote.status ?: IpoStatus.PRE_APPLY,
                    listingDate = quote.listingDate,
                )
            }
            val sortedActive = activeQuotes.sortedWith(
                compareBy<LiveGmpQuote> { statusRank(it.status ?: IpoStatus.PRE_APPLY) }
                    .thenByDescending { it.distinctProviderCount }
                    .thenBy { it.priorityRank },
            ).take(25)

            val sortedListed = listedQuotes.sortedWith(
                compareByDescending<LiveGmpQuote> { quote ->
                    IpoDateUtils.parseFlexibleDateMs(quote.listingDate)
                        ?: IpoDateUtils.parseFlexibleDateMs(quote.closeDate)
                        ?: 0L
                }.thenByDescending { it.distinctProviderCount }
                    .thenBy { it.priorityRank },
            ).take(25)

            val sortedLiveQuotes = sortedActive + sortedListed

            rawCatalog = sortedLiveQuotes.mapIndexed { idx, quote ->
                val matchedSeed = findMatchingSeedIpo(quote, seedCatalog)
                if (matchedSeed != null) {
                    updatedCount++
                    val saved = existingById[matchedSeed.id] ?: existingById["live_${quote.normalizedKey.take(36)}"]
                    enrichSeedWithLiveQuote(
                        seed = matchedSeed,
                        quote = quote,
                        saved = saved,
                        syncTime = syncTime,
                        todayShortLabel = todayShortLabel,
                        sortOrder = idx + 1,
                    )
                } else {
                    discoveredCount++
                    val idSlug = "live_${quote.normalizedKey.take(36)}"
                    buildLiveEntity(
                        idSlug = idSlug,
                        quote = quote,
                        saved = existingById[idSlug],
                        syncTime = syncTime,
                        todayShortLabel = todayShortLabel,
                        sortOrder = idx + 1,
                    )
                }
            }
        } else {
            // Offline / partial fallback: merge ONLY multi-source verified filed quotes into verified seed catalog
            val verifiedByKey = verifiedLiveQuotes.associateBy { it.normalizedKey }
            val matchedKeys = HashSet<String>()
            val updatedSeedCatalog = seedCatalog.map { item ->
                val saved = existingById[item.id]
                val itemKey = liveGmpRepository.normalizeCompanyKey(item.companyName)
                val shortKey = item.shortCode.lowercase(Locale.US)
                val liveQuote = verifiedByKey[itemKey]
                    ?: verifiedLiveQuotes.firstOrNull { quote ->
                        liveGmpRepository.areSameCompanyNames(item.companyName, quote.companyName, item.boardType, quote.boardType) ||
                            quote.normalizedKey.contains(shortKey)
                    }

                if (liveQuote != null) {
                    updatedCount++
                    matchedKeys.add(liveQuote.normalizedKey)
                    enrichSeedWithLiveQuote(
                        seed = item,
                        quote = liveQuote,
                        saved = saved,
                        syncTime = syncTime,
                        todayShortLabel = todayShortLabel,
                        sortOrder = item.sortOrder,
                    )
                } else {
                    val syncedLabel = when (item.status) {
                        IpoStatus.LIVE -> "Open • Synced $syncTime"
                        IpoStatus.PRE_APPLY -> "Pre-Apply • Synced $syncTime"
                        IpoStatus.UPCOMING -> "${item.openDate.removeSuffix(" 2026")} • Synced $syncTime"
                        IpoStatus.CLOSED -> "${item.updatedAgoLabel.substringBefore("•").trim()} • Synced $syncTime"
                    }
                    item.copy(
                        userLots = saved?.userLots ?: item.userLots,
                        updatedAgoLabel = syncedLabel,
                    )
                }
            }

            val discoveredQuotes = verifiedLiveQuotes
                .filter { quote ->
                    quote.normalizedKey !in matchedKeys &&
                        seedCatalog.none { seed ->
                            liveGmpRepository.areSameCompanyNames(seed.companyName, quote.companyName, seed.boardType, quote.boardType)
                        }
                }
                .take(10)

            val discoveredEntities = discoveredQuotes.mapIndexed { idx, quote ->
                discoveredCount++
                val idSlug = "live_${quote.normalizedKey.take(36)}"
                buildLiveEntity(
                    idSlug = idSlug,
                    quote = quote,
                    saved = existingById[idSlug],
                    syncTime = syncTime,
                    todayShortLabel = todayShortLabel,
                    sortOrder = -20 + idx,
                )
            }
            rawCatalog = discoveredEntities + updatedSeedCatalog
        }

        // Final hard gate: deduplicate, overlay live category-wise subscription data, and verify filing details
        val subscriptionQuotes = subscriptionSnapshot?.allQuotes.orEmpty()
        val finalCatalog = deduplicateCatalog(rawCatalog)
            .filter { IpoFilingVerifier.isVerifiedFiledEntity(it) }
            .map { entity ->
                val subMatch = if (subscriptionQuotes.isNotEmpty()) {
                    liveSubscriptionRepository.findMatchingQuote(entity, subscriptionQuotes)
                } else {
                    null
                }
                if (subMatch != null) {
                    liveSubscriptionRepository.applySubscriptionToEntity(
                        entity = entity,
                        quote = subMatch,
                        syncTime = subscriptionSnapshot?.syncedAtTime ?: syncTime,
                    )
                } else {
                    entity
                }
            }
        val activeIds = finalCatalog.map { it.id }
        ipoDao.insertAll(finalCatalog)
        if (existingById.keys.any { it !in activeIds }) {
            ipoDao.deleteObsoleteIds(activeIds)
        }

        val combinedSources = (gmpSnapshot.activeSources + subscriptionSnapshot?.activeSources.orEmpty()).distinct()
        return GmpSyncSummary(
            syncedAtTime = syncTime,
            updatedExistingCount = updatedCount,
            discoveredLiveCount = discoveredCount,
            activeSources = combinedSources,
            isLiveFromNetwork = combinedSources.isNotEmpty(),
            previousIpos = existingById.values.toList(),
            updatedIpos = finalCatalog,
            excludedUnconfirmedCount = excludedUnconfirmedCount,
            verifiedProviderCount = gmpSnapshot.verifiedProviders.size,
        )
    }

    private fun findMatchingSeedIpo(
        quote: LiveGmpQuote,
        seedCatalog: List<IpoEntity>,
    ): IpoEntity? {
        return seedCatalog.firstOrNull { seed ->
            liveGmpRepository.areSameCompanyNames(
                nameA = seed.companyName,
                nameB = quote.companyName,
                boardA = seed.boardType,
                boardB = quote.boardType,
            )
        }
    }

    private fun enrichSeedWithLiveQuote(
        seed: IpoEntity,
        quote: LiveGmpQuote,
        saved: IpoEntity?,
        syncTime: String,
        todayShortLabel: String,
        sortOrder: Int,
    ): IpoEntity {
        val resolvedGmp = if (quote.hasExplicitGmp) quote.gmpRupees else seed.gmpRupees
        val resolvedLow = quote.priceLow.takeIf { it > 0 } ?: seed.priceLow
        val resolvedHigh = quote.priceHigh.takeIf { it > 0 && it >= resolvedLow } ?: seed.priceHigh
        val resolvedLot = quote.lotSize.takeIf { it > 0 } ?: seed.lotSize
        val resolvedSize = quote.issueSizeCr.takeIf { it > 0.0 } ?: seed.issueSizeCr
        val resolvedStatus = quote.status ?: seed.status
        val resolvedSubX = quote.totalSubX.takeIf { it > 0.0 } ?: seed.totalSubX
        val basePoints = if (seed.gmpHistory.size >= 2) seed.gmpHistory.dropLast(1) else seed.gmpHistory
        val resolvedHistory = basePoints + GmpPoint(dayLabel = todayShortLabel, gmpRupees = resolvedGmp)

        val sourcesLabel = IpoFilingVerifier.formatVerifiedSourcesLabel(quote.verifiedProviders)

        return seed.copy(
            status = resolvedStatus,
            openDate = quote.openDate.ifBlank { seed.openDate },
            closeDate = quote.closeDate.ifBlank { seed.closeDate },
            listingDate = quote.listingDate.ifBlank { seed.listingDate },
            priceLow = resolvedLow,
            priceHigh = resolvedHigh,
            lotSize = resolvedLot,
            issueSizeCr = resolvedSize,
            gmpRupees = resolvedGmp,
            qibSubX = saved?.qibSubX?.takeIf { it > 0.0 } ?: seed.qibSubX,
            bNiiSubX = saved?.bNiiSubX?.takeIf { it > 0.0 } ?: seed.bNiiSubX,
            sNiiSubX = saved?.sNiiSubX?.takeIf { it > 0.0 } ?: seed.sNiiSubX,
            retailSubX = saved?.retailSubX?.takeIf { it > 0.0 } ?: seed.retailSubX,
            employeeSubX = saved?.employeeSubX?.takeIf { it > 0.0 } ?: seed.employeeSubX,
            totalSubX = maxOf(resolvedSubX, saved?.totalSubX ?: 0.0),
            dailySubscriptions = saved?.dailySubscriptions?.takeIf { it.isNotEmpty() } ?: seed.dailySubscriptions,
            gmpHistory = resolvedHistory,
            userLots = saved?.userLots ?: seed.userLots,
            updatedAgoLabel = "$sourcesLabel • $syncTime",
            gmpSourceLabel = "Verified • $sourcesLabel",
            sortOrder = sortOrder,
        )
    }

    private fun buildLiveEntity(
        idSlug: String,
        quote: LiveGmpQuote,
        saved: IpoEntity?,
        syncTime: String,
        todayShortLabel: String,
        sortOrder: Int,
    ): IpoEntity {
        val sourcesLabel = IpoFilingVerifier.formatVerifiedSourcesLabel(quote.verifiedProviders)
        val code = quote.companyName
            .replace(Regex("[^A-Za-z0-9]"), "")
            .uppercase(Locale.US)
            .take(10)
            .ifBlank { "IPO" }

        return IpoEntity(
            id = idSlug,
            companyName = quote.companyName,
            shortCode = code,
            sector = if (quote.boardType == BoardType.SME) "SME IPO" else "Mainboard IPO",
            boardType = quote.boardType,
            exchange = if (quote.boardType == BoardType.SME) "NSE SME / BSE SME" else "NSE • BSE",
            status = quote.status ?: IpoStatus.PRE_APPLY,
            openDate = quote.openDate.ifBlank { quote.listingDate },
            closeDate = quote.closeDate.ifBlank { quote.listingDate },
            allotmentDate = if (quote.status == IpoStatus.CLOSED) "Completed" else "TBA",
            listingDate = quote.listingDate.ifBlank { "TBA" },
            priceLow = quote.priceLow,
            priceHigh = quote.priceHigh,
            faceValue = 10,
            lotSize = quote.lotSize,
            issueSizeCr = quote.issueSizeCr,
            freshIssueCr = quote.issueSizeCr,
            ofsCr = 0.0,
            gmpRupees = quote.gmpRupees,
            kostakRupees = 0,
            qibSubX = saved?.qibSubX ?: 0.0,
            bNiiSubX = saved?.bNiiSubX ?: 0.0,
            sNiiSubX = saved?.sNiiSubX ?: 0.0,
            retailSubX = saved?.retailSubX ?: 0.0,
            employeeSubX = saved?.employeeSubX ?: 0.0,
            totalSubX = maxOf(quote.totalSubX, saved?.totalSubX ?: 0.0),
            registrarName = "Official Exchange & Registrar",
            registrarUrl = "https://www.chittorgarh.com/report/ipo-in-india-list-main-board-sme/82/",
            leadManagers = listOf("Refer RHP / Prospectus"),
            overview = "${quote.companyName} filing, lot size, issue size, and GMP cross-verified across $sourcesLabel.",
            objectsOfIssue = emptyList(),
            strengths = emptyList(),
            risks = emptyList(),
            financialYears = emptyList(),
            gmpHistory = listOf(
                GmpPoint("Prev", quote.gmpRupees),
                GmpPoint(todayShortLabel, quote.gmpRupees),
            ),
            dailySubscriptions = saved?.dailySubscriptions ?: emptyList(),
            valuation = ValuationMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            userLots = saved?.userLots ?: 1,
            updatedAgoLabel = "$sourcesLabel • $syncTime",
            gmpSourceLabel = "Verified • $sourcesLabel",
            sortOrder = sortOrder,
            logoUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange_logo.svg",
            bannerImageUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange.jpg?width=900",
        )
    }

    private fun deduplicateCatalog(items: List<IpoEntity>): List<IpoEntity> {
        val result = mutableListOf<IpoEntity>()
        val seenIds = HashSet<String>()
        for (item in items) {
            if (item.id in seenIds) continue
            val isDuplicateCompany = result.any { existing ->
                liveGmpRepository.areSameCompanyNames(
                    nameA = existing.companyName,
                    nameB = item.companyName,
                    boardA = existing.boardType,
                    boardB = item.boardType,
                )
            }
            if (!isDuplicateCompany) {
                seenIds.add(item.id)
                result.add(item)
            }
        }
        return result
    }

    private fun statusRank(status: IpoStatus): Int = when (status) {
        IpoStatus.LIVE -> 0
        IpoStatus.PRE_APPLY -> 1
        IpoStatus.UPCOMING -> 2
        IpoStatus.CLOSED -> 3
    }

    suspend fun refreshMarketPulse(): String = syncWithLiveGmp().syncedAtTime

    suspend fun updateUserLots(id: String, lots: Int) {
        ipoDao.updateUserLots(id = id, lots = lots.coerceIn(1, 200))
    }
}
