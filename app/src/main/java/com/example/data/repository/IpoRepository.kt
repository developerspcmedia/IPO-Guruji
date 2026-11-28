package com.example.data.repository

import com.example.data.local.IpoDao
import com.example.data.local.IpoSeedData
import com.example.data.model.BoardType
import com.example.data.model.GmpPoint
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoOverrideMerger
import com.example.data.model.IpoStatus
import com.example.data.model.ValuationMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    val subscriptionSourceStatuses: List<SubscriptionSourceStatus> = emptyList(),
)

data class SubscriptionRefreshResult(
    val updated: Boolean,
    val syncedAtLabel: String,
    val sourceLabel: String,
    val statusMessage: String,
    val matchedQuotesCount: Int = 0,
    val hasLiveSourceData: Boolean = false,
    val sourceStatuses: List<SubscriptionSourceStatus> = emptyList(),
)

class IpoRepository(
    private val ipoDao: IpoDao,
    private val liveGmpRepository: LiveGmpRepository = LiveGmpRepository(),
    private val liveSubscriptionRepository: LiveSubscriptionRepository = LiveSubscriptionRepository(
        enableNetworkFetch = liveGmpRepository.enableNetworkFetch,
    ),
    private val adminOverrideRepository: AdminOverrideRepository? = null,
) {
    private val customIpoLiveSubscriptions =
        MutableStateFlow<Map<String, Pair<LiveSubscriptionQuote, String>>>(emptyMap())

    fun observeAllIpos(): Flow<List<IpoEntity>> {
        val overrideRepo = adminOverrideRepository ?: return ipoDao.observeAllIpos()
        return combine(
            ipoDao.observeAllIpos(),
            overrideRepo.observeCachedOverrides(),
            customIpoLiveSubscriptions,
        ) { baseCatalog, overrides, customSubs ->
            val merged = IpoOverrideMerger.applyOverridesToCatalog(baseCatalog, overrides)
            if (customSubs.isEmpty()) {
                merged
            } else {
                val baseIds = baseCatalog.mapTo(HashSet()) { it.id }
                merged.map { entity ->
                    val liveMatch = if (entity.id !in baseIds) customSubs[entity.id] else null
                    if (liveMatch != null) {
                        liveSubscriptionRepository.applySubscriptionToEntity(
                            entity = entity,
                            quote = liveMatch.first,
                            syncTime = liveMatch.second,
                        )
                    } else {
                        entity
                    }
                }
            }
        }
    }

    fun observeIpoById(id: String): Flow<IpoEntity?> {
        if (adminOverrideRepository == null) return ipoDao.observeIpoById(id)
        return observeAllIpos().map { catalog -> catalog.firstOrNull { it.id == id } }
    }

    fun observeRawCatalogForAdmin(): Flow<List<IpoEntity>> = ipoDao.observeAllIpos()

    fun isSubscriptionCacheStale(
        maxAgeMs: Long = LiveSubscriptionRepository.DEFAULT_STALE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = liveSubscriptionRepository.isSnapshotStale(maxAgeMs = maxAgeMs, nowMs = nowMs)

    suspend fun ensureSeeded() {
        syncCatalogOnAppOpen()
    }

    suspend fun syncCatalogOnAppOpen(): String = syncWithLiveGmp().syncedAtTime

    suspend fun syncWithLiveGmp(): GmpSyncSummary = withContext(Dispatchers.IO) {
        val (gmpSnapshot, subSnapshot) = coroutineScope {
            val gmpDeferred = async { liveGmpRepository.fetchLatestGmps() }
            val subDeferred = async { liveSubscriptionRepository.fetchLatestSubscriptions(forceRefresh = true) }
            val overridesDeferred = async { adminOverrideRepository?.fetchPublishedCloudOverridesOnce() }
            overridesDeferred.await()
            gmpDeferred.await() to subDeferred.await()
        }
        // Save fetched subscription snapshot BEFORE applySnapshotToDatabase() so an empty GMP
        // response never discards valid live subscription numbers.
        if (subSnapshot.allQuotes.isNotEmpty()) {
            applySubscriptionSnapshotToDatabase(subSnapshot)
        }
        // Re-read Room AFTER network fetches and subscription save so updates aren't overwritten.
        val freshExistingById = ipoDao.getAllSnapshot().associateBy { it.id }
        applySnapshotToDatabase(
            existingById = freshExistingById,
            gmpSnapshot = gmpSnapshot,
            subscriptionSnapshot = subSnapshot,
        )
    }

    suspend fun refreshLiveSubscriptionsOnly(
        force: Boolean = true,
    ): SubscriptionRefreshResult = withContext(Dispatchers.IO) {
        val snapshot = liveSubscriptionRepository.fetchLatestSubscriptions(forceRefresh = force)
        val matchedCount = applySubscriptionSnapshotToDatabase(snapshot)
        val hasLiveData = snapshot.activeSources.isNotEmpty()
        val sourceLabel = snapshot.activeSources
            .take(3)
            .joinToString(" • ")
            .ifBlank { "NSE / Chittorgarh / InvestorGain" }
        SubscriptionRefreshResult(
            updated = matchedCount > 0,
            syncedAtLabel = if (hasLiveData) snapshot.syncedAtTime else "",
            sourceLabel = sourceLabel,
            statusMessage = if (hasLiveData) {
                "Live subscription synced • ${snapshot.syncedAtTime}"
            } else {
                "Couldn't refresh live subscription right now"
            },
            matchedQuotesCount = matchedCount,
            hasLiveSourceData = hasLiveData,
            sourceStatuses = snapshot.sourceStatuses,
        )
    }

    suspend fun refreshLiveSubscriptionForIpo(
        ipoId: String,
        force: Boolean = true,
    ): SubscriptionRefreshResult = withContext(Dispatchers.IO) {
        val allExisting = ipoDao.getAllSnapshot()
        val cachedOverrides = adminOverrideRepository?.getCachedOverridesSnapshot().orEmpty()
        val targetFromBase = allExisting.firstOrNull { it.id == ipoId }
        val targetFromOverride = if (targetFromBase == null) {
            cachedOverrides.firstOrNull { it.targetIpoId == ipoId || it.id == ipoId }
                ?.let { IpoOverrideMerger.buildEntityFromOverride(it) }
        } else {
            null
        }
        val target = targetFromBase ?: targetFromOverride
        val snapshot = liveSubscriptionRepository.fetchLatestSubscriptions(
            forceRefresh = force,
            targetSymbol = target?.shortCode,
            targetCompanyName = target?.companyName,
        )
        val matchedCount = applySubscriptionSnapshotToDatabase(snapshot)
        val updatedTarget = ipoDao.getAllSnapshot().firstOrNull { it.id == ipoId } ?: targetFromOverride
        val matchedQuote = target?.let { liveSubscriptionRepository.findMatchingQuote(it, snapshot.allQuotes) }
        val targetMatched = matchedQuote != null
        val hasLiveData = snapshot.activeSources.isNotEmpty()
        val sourceLabel = matchedQuote?.sourceName?.ifBlank { null }
            ?: snapshot.activeSources
                .take(3)
                .joinToString(" • ")
                .ifBlank { "NSE / Chittorgarh / InvestorGain" }

        val message = when {
            matchedQuote != null -> "Live synced from ${matchedQuote.sourceName} • ${snapshot.syncedAtTime}"
            matchedCount > 0 -> "Synced $matchedCount active IPOs • No live update published for this issue yet"
            updatedTarget != null && updatedTarget.status == IpoStatus.UPCOMING ->
                "Bidding opens on ${updatedTarget.openDate} • Live updates start at 10:00 AM IST"
            hasLiveData ->
                "Checked $sourceLabel • Awaiting latest exchange update"
            else -> "Exchange feeds busy • Couldn't refresh now"
        }

        SubscriptionRefreshResult(
            updated = targetMatched || matchedCount > 0,
            syncedAtLabel = if (hasLiveData) snapshot.syncedAtTime else "",
            sourceLabel = sourceLabel,
            statusMessage = message,
            matchedQuotesCount = matchedCount,
            hasLiveSourceData = hasLiveData,
            sourceStatuses = snapshot.sourceStatuses,
        )
    }

    internal suspend fun applySubscriptionSnapshotToDatabase(
        snapshot: LiveSubscriptionSnapshot,
    ): Int {
        if (snapshot.allQuotes.isEmpty()) return 0
        val existing = ipoDao.getAllSnapshot()
        val updatedEntities = mutableListOf<IpoEntity>()
        val existingIds = HashSet<String>(existing.size)
        for (entity in existing) {
            existingIds.add(entity.id)
            val match = liveSubscriptionRepository.findMatchingQuote(entity, snapshot.allQuotes)
            if (match != null) {
                updatedEntities.add(
                    liveSubscriptionRepository.applySubscriptionToEntity(
                        entity = entity,
                        quote = match,
                        syncTime = snapshot.syncedAtTime,
                        nowMs = snapshot.syncedAtMs,
                    ),
                )
            }
        }
        if (updatedEntities.isNotEmpty()) {
            ipoDao.insertAll(updatedEntities)
        }
        val customMatchedCount = applySubscriptionSnapshotToCustomOverrides(snapshot, existingIds)
        return updatedEntities.size + customMatchedCount
    }

    private suspend fun applySubscriptionSnapshotToCustomOverrides(
        snapshot: LiveSubscriptionSnapshot,
        existingBaseIds: Set<String>,
    ): Int {
        if (snapshot.allQuotes.isEmpty()) return 0
        val overrides = adminOverrideRepository?.getCachedOverridesSnapshot().orEmpty()
            .filter { it.published && !it.hidden }
        if (overrides.isEmpty()) return 0

        val matchedForCustom = mutableMapOf<String, Pair<LiveSubscriptionQuote, String>>()
        for (override in overrides) {
            val id = override.targetIpoId.ifBlank { override.id }
            if (id in existingBaseIds) continue
            val synthetic = IpoOverrideMerger.buildEntityFromOverride(override)
            val quote = liveSubscriptionRepository.findMatchingQuote(synthetic, snapshot.allQuotes)
            if (quote != null) {
                matchedForCustom[id] = quote to snapshot.syncedAtTime
            }
        }
        if (matchedForCustom.isNotEmpty()) {
            customIpoLiveSubscriptions.update { it + matchedForCustom }
        }
        return matchedForCustom.size
    }

    internal suspend fun applySnapshotToDatabase(
        existingById: Map<String, IpoEntity>,
        gmpSnapshot: LiveGmpSnapshot,
        subscriptionSnapshot: LiveSubscriptionSnapshot? = null,
    ): GmpSyncSummary {
        val syncTime = gmpSnapshot.syncedAtTime
        val todayShortLabel = SimpleDateFormat("dd MMM", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date())
        val seedCatalog = IpoSeedData.createInitialCatalog()
            .filter { IpoFilingVerifier.isVerifiedFiledEntity(it) }

        // If no live GMP source succeeded (offline or all sources failed), never replace existing DB rows
        // with seed data and never delete cached rows. Only seed on first launch when DB is completely empty.
        // Still apply any live subscription snapshot so subscription updates survive GMP outages.
        if (gmpSnapshot.activeSources.isEmpty()) {
            val subSources = subscriptionSnapshot?.activeSources.orEmpty()
            val subStatuses = subscriptionSnapshot?.sourceStatuses.orEmpty()
            if (existingById.isNotEmpty()) {
                val subUpdatedCount = if (subscriptionSnapshot != null && subscriptionSnapshot.allQuotes.isNotEmpty()) {
                    applySubscriptionSnapshotToDatabase(subscriptionSnapshot)
                } else {
                    0
                }
                val preserved = if (subUpdatedCount > 0) {
                    ipoDao.getAllSnapshot()
                } else {
                    existingById.values.toList()
                }
                return GmpSyncSummary(
                    syncedAtTime = syncTime,
                    updatedExistingCount = subUpdatedCount,
                    discoveredLiveCount = 0,
                    activeSources = subSources,
                    isLiveFromNetwork = subSources.isNotEmpty(),
                    previousIpos = existingById.values.toList(),
                    updatedIpos = preserved,
                    excludedUnconfirmedCount = 0,
                    verifiedProviderCount = 0,
                    subscriptionSourceStatuses = subStatuses,
                )
            }
            val initialSeed = deduplicateCatalog(seedCatalog)
                .filter { IpoFilingVerifier.isDisplayableEntity(it) }
            if (initialSeed.isNotEmpty()) {
                ipoDao.replaceCatalog(initialSeed, initialSeed.map { it.id })
            }
            val subUpdatedCount = if (subscriptionSnapshot != null && subscriptionSnapshot.allQuotes.isNotEmpty()) {
                applySubscriptionSnapshotToDatabase(subscriptionSnapshot)
            } else {
                0
            }
            val afterSeed = if (subUpdatedCount > 0) ipoDao.getAllSnapshot() else initialSeed
            return GmpSyncSummary(
                syncedAtTime = syncTime,
                updatedExistingCount = subUpdatedCount,
                discoveredLiveCount = 0,
                activeSources = subSources,
                isLiveFromNetwork = subSources.isNotEmpty(),
                previousIpos = emptyList(),
                updatedIpos = afterSeed,
                excludedUnconfirmedCount = 0,
                verifiedProviderCount = 0,
                subscriptionSourceStatuses = subStatuses,
            )
        }

        // Merge all structured live quotes across independent providers (excludes bare RSS-only snippets)
        val mergedLiveQuotes = liveGmpRepository.mergeQuotesWithoutDuplicates(
            gmpSnapshot.allQuotes.filter { quote -> quote.priorityRank <= 12 },
        )

        // Cross-verify Current & Listed IPOs across >= 2 independent sources with confirmed filing details.
        val verifiedLiveQuotes = mergedLiveQuotes.filter { quote ->
            IpoFilingVerifier.verifyQuote(quote).isVerified
        }

        // Collect ALL fetched & found Upcoming IPOs (including DRHP/RHP entries with TBA dates, price band,
        // lot size, or issue size, and single-source discoveries) so the Upcoming tab shows every discovered IPO.
        val allFetchedUpcomingQuotes = mergedLiveQuotes
            .filter { quote -> IpoFilingVerifier.isDisplayableUpcomingQuote(quote) }
            .sortedWith(
                compareBy<LiveGmpQuote> { quote ->
                    IpoDateUtils.parseFlexibleDateMs(quote.openDate) ?: Long.MAX_VALUE
                }.thenByDescending { it.distinctProviderCount }
                    .thenByDescending { it.issueSizeCr }
                    .thenBy { it.priorityRank }
                    .thenBy { it.companyName.lowercase(Locale.US) },
            )

        val acceptedKeys = (verifiedLiveQuotes.map { it.normalizedKey } + allFetchedUpcomingQuotes.map { it.normalizedKey }).toSet()
        val excludedUnconfirmedCount = (mergedLiveQuotes.size - acceptedKeys.size).coerceAtLeast(0)

        var updatedCount = 0
        var discoveredCount = 0
        val rawCatalog: List<IpoEntity>

        if (verifiedLiveQuotes.size >= 3) {
            // Authoritative live mode: verified slots for Current & Listed + ALL fetched Upcoming IPOs
            val groupedVerified = verifiedLiveQuotes.groupBy { quote ->
                IpoDateUtils.classifyTimelineTab(
                    openDate = quote.openDate,
                    closeDate = quote.closeDate,
                    listingDate = quote.listingDate,
                    status = quote.status ?: IpoStatus.PRE_APPLY,
                )
            }

            val sortedCurrent = groupedVerified[com.example.data.model.IpoTimelineBucket.CURRENT].orEmpty().sortedWith(
                compareBy<LiveGmpQuote> { quote ->
                    val effStatus = quote.status ?: IpoStatus.PRE_APPLY
                    val isOpenNow = IpoDateUtils.isBiddingOpenToday(
                        openDate = quote.openDate,
                        closeDate = quote.closeDate,
                        listingDate = quote.listingDate,
                        status = effStatus,
                    )
                    val inAllotment = IpoDateUtils.isInAllotmentPhase(
                        closeDate = quote.closeDate,
                        listingDate = quote.listingDate,
                        status = effStatus,
                    )
                    when {
                        isOpenNow -> 0
                        inAllotment -> 1
                        else -> 2
                    }
                }.thenBy { quote ->
                    IpoDateUtils.parseFlexibleDateMs(quote.closeDate)
                        ?: IpoDateUtils.parseFlexibleDateMs(quote.listingDate)
                        ?: Long.MAX_VALUE
                }.thenByDescending { it.distinctProviderCount }
                    .thenBy { it.priorityRank },
            ).take(20)

            val sortedListed = groupedVerified[com.example.data.model.IpoTimelineBucket.LISTED].orEmpty().sortedWith(
                compareByDescending<LiveGmpQuote> { quote ->
                    IpoDateUtils.parseFlexibleDateMs(quote.listingDate)
                        ?: IpoDateUtils.parseFlexibleDateMs(quote.closeDate)
                        ?: 0L
                }.thenByDescending { it.distinctProviderCount }
                    .thenBy { it.priorityRank },
            ).take(25)

            val sortedLiveQuotes = sortedCurrent + allFetchedUpcomingQuotes + sortedListed

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
            // Offline / partial fallback: merge verified active/listed quotes + ALL fetched Upcoming IPOs into catalog
            val eligibleLiveQuotes = liveGmpRepository.mergeQuotesWithoutDuplicates(
                verifiedLiveQuotes + allFetchedUpcomingQuotes,
            )
            val eligibleByKey = eligibleLiveQuotes.associateBy { it.normalizedKey }
            val matchedKeys = HashSet<String>()
            val updatedSeedCatalog = seedCatalog.map { item ->
                val saved = existingById[item.id]
                val itemKey = liveGmpRepository.normalizeCompanyKey(item.companyName)
                val shortKey = item.shortCode.lowercase(Locale.US)
                val liveQuote = eligibleByKey[itemKey]
                    ?: eligibleLiveQuotes.firstOrNull { quote ->
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
                    item.copy(
                        qibSubX = saved?.qibSubX?.takeIf { it > 0.0 } ?: item.qibSubX,
                        bNiiSubX = saved?.bNiiSubX?.takeIf { it > 0.0 } ?: item.bNiiSubX,
                        sNiiSubX = saved?.sNiiSubX?.takeIf { it > 0.0 } ?: item.sNiiSubX,
                        retailSubX = saved?.retailSubX?.takeIf { it > 0.0 } ?: item.retailSubX,
                        employeeSubX = saved?.employeeSubX?.takeIf { it > 0.0 } ?: item.employeeSubX,
                        totalSubX = saved?.totalSubX?.takeIf { it > 0.0 } ?: item.totalSubX,
                        dailySubscriptions = saved?.dailySubscriptions?.takeIf { it.isNotEmpty() } ?: item.dailySubscriptions,
                        liveApplicationCounts = saved?.liveApplicationCounts?.takeIf { it.isNotEmpty() } ?: item.liveApplicationCounts,
                        subscriptionSourceLabel = saved?.subscriptionSourceLabel?.takeIf { it.isNotBlank() } ?: item.subscriptionSourceLabel,
                        subscriptionUpdatedAtMs = saved?.subscriptionUpdatedAtMs?.takeIf { it > 0L } ?: item.subscriptionUpdatedAtMs,
                        userLots = saved?.userLots ?: item.userLots,
                        updatedAgoLabel = saved?.updatedAgoLabel ?: item.updatedAgoLabel,
                    )
                }
            }

            val discoveredVerifiedActive = verifiedLiveQuotes
                .filter { quote ->
                    quote.normalizedKey !in matchedKeys &&
                        !IpoFilingVerifier.isDisplayableUpcomingQuote(quote) &&
                        seedCatalog.none { seed ->
                            liveGmpRepository.areSameCompanyNames(seed.companyName, quote.companyName, seed.boardType, quote.boardType)
                        }
                }
                .take(10)

            val discoveredUpcoming = allFetchedUpcomingQuotes
                .filter { quote ->
                    quote.normalizedKey !in matchedKeys &&
                        seedCatalog.none { seed ->
                            liveGmpRepository.areSameCompanyNames(seed.companyName, quote.companyName, seed.boardType, quote.boardType)
                        }
                }

            val discoveredQuotes = discoveredVerifiedActive + discoveredUpcoming

            val discoveredEntities = discoveredQuotes.mapIndexed { idx, quote ->
                discoveredCount++
                val idSlug = "live_${quote.normalizedKey.take(36)}"
                buildLiveEntity(
                    idSlug = idSlug,
                    quote = quote,
                    saved = existingById[idSlug],
                    syncTime = syncTime,
                    todayShortLabel = todayShortLabel,
                    sortOrder = -100 + idx,
                )
            }
            rawCatalog = discoveredEntities + updatedSeedCatalog
        }

        // Final gate: deduplicate, overlay live category-wise subscription data, and retain ALL valid Upcoming IPOs + verified Current/Listed IPOs
        val subscriptionQuotes = subscriptionSnapshot?.allQuotes.orEmpty()
        val finalCatalog = deduplicateCatalog(rawCatalog)
            .filter { IpoFilingVerifier.isDisplayableEntity(it) }
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
                        nowMs = subscriptionSnapshot?.syncedAtMs ?: System.currentTimeMillis(),
                    )
                } else {
                    entity
                }
            }
        val effectiveUpdatedIpos: List<IpoEntity>
        if (finalCatalog.isNotEmpty()) {
            val activeIds = finalCatalog.map { it.id }
            ipoDao.replaceCatalog(finalCatalog, activeIds)
            if (subscriptionSnapshot != null) {
                applySubscriptionSnapshotToCustomOverrides(subscriptionSnapshot, activeIds.toSet())
            }
            effectiveUpdatedIpos = finalCatalog
        } else {
            // Never call deleteObsoleteIds with empty activeIds; preserve existing DB untouched.
            effectiveUpdatedIpos = existingById.values.toList()
        }

        val combinedSources = (gmpSnapshot.activeSources + subscriptionSnapshot?.activeSources.orEmpty()).distinct()
        return GmpSyncSummary(
            syncedAtTime = syncTime,
            updatedExistingCount = updatedCount,
            discoveredLiveCount = discoveredCount,
            activeSources = combinedSources,
            isLiveFromNetwork = combinedSources.isNotEmpty(),
            previousIpos = existingById.values.toList(),
            updatedIpos = effectiveUpdatedIpos,
            excludedUnconfirmedCount = excludedUnconfirmedCount,
            verifiedProviderCount = gmpSnapshot.verifiedProviders.size,
            subscriptionSourceStatuses = subscriptionSnapshot?.sourceStatuses.orEmpty(),
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
        val resolvedOpenDate = IpoFilingVerifier.sanitizeConfirmedDate(quote.openDate).ifBlank { seed.openDate }
        val resolvedCloseDate = IpoFilingVerifier.sanitizeConfirmedDate(quote.closeDate).ifBlank { seed.closeDate }
        val resolvedListingDate = IpoFilingVerifier.sanitizeConfirmedDate(quote.listingDate).ifBlank { seed.listingDate }
        val resolvedStatus = liveGmpRepository.inferStatus(
            statusHint = "",
            openDate = resolvedOpenDate,
            closeDate = resolvedCloseDate,
            priceHigh = resolvedHigh,
            listingDate = resolvedListingDate,
        )
        val resolvedSubX = quote.totalSubX.takeIf { it > 0.0 } ?: seed.totalSubX
        val basePoints = if (seed.gmpHistory.size >= 2) seed.gmpHistory.dropLast(1) else seed.gmpHistory
        val resolvedHistory = basePoints + GmpPoint(dayLabel = todayShortLabel, gmpRupees = resolvedGmp)

        val sourcesLabel = IpoFilingVerifier.formatVerifiedSourcesLabel(quote.verifiedProviders)

        return seed.copy(
            status = resolvedStatus,
            openDate = resolvedOpenDate,
            closeDate = resolvedCloseDate,
            listingDate = resolvedListingDate,
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
            totalSubX = quote.totalSubX.takeIf { it > 0.0 } ?: saved?.totalSubX?.takeIf { it > 0.0 } ?: resolvedSubX,
            dailySubscriptions = saved?.dailySubscriptions?.takeIf { it.isNotEmpty() } ?: seed.dailySubscriptions,
            gmpHistory = resolvedHistory,
            userLots = saved?.userLots ?: seed.userLots,
            updatedAgoLabel = "$sourcesLabel • $syncTime",
            gmpSourceLabel = "Verified • $sourcesLabel",
            sortOrder = sortOrder,
            liveApplicationCounts = saved?.liveApplicationCounts ?: seed.liveApplicationCounts,
            subscriptionSourceLabel = saved?.subscriptionSourceLabel ?: seed.subscriptionSourceLabel,
            subscriptionUpdatedAtMs = saved?.subscriptionUpdatedAtMs ?: seed.subscriptionUpdatedAtMs,
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

        val resolvedOpenDate = IpoFilingVerifier.sanitizeConfirmedDate(quote.openDate).ifBlank { "TBA" }
        val resolvedCloseDate = IpoFilingVerifier.sanitizeConfirmedDate(quote.closeDate).ifBlank { "TBA" }
        val resolvedListingDate = IpoFilingVerifier.sanitizeConfirmedDate(quote.listingDate).ifBlank { "TBA" }
        val resolvedStatus = quote.status ?: liveGmpRepository.inferStatus(
            statusHint = "",
            openDate = resolvedOpenDate,
            closeDate = resolvedCloseDate,
            priceHigh = quote.priceHigh,
            listingDate = resolvedListingDate,
        )
        val isMultiSource = quote.verifiedProviders.size >= IpoFilingVerifier.MIN_VERIFIED_SOURCES
        val overviewText = if (quote.priceHigh > 0 && quote.lotSize > 0) {
            "${quote.companyName} filing, lot size, issue size, and GMP tracked across $sourcesLabel."
        } else {
            "${quote.companyName} upcoming IPO fetched from $sourcesLabel. Official dates, price band, and lot size will update once announced."
        }

        return IpoEntity(
            id = idSlug,
            companyName = quote.companyName,
            shortCode = code,
            sector = if (quote.boardType == BoardType.SME) "SME IPO" else "Mainboard IPO",
            boardType = quote.boardType,
            exchange = if (quote.boardType == BoardType.SME) "NSE SME / BSE SME" else "NSE • BSE",
            status = resolvedStatus,
            openDate = resolvedOpenDate,
            closeDate = resolvedCloseDate,
            allotmentDate = if (resolvedStatus == IpoStatus.CLOSED) "Completed" else "TBA",
            listingDate = resolvedListingDate,
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
            totalSubX = quote.totalSubX.takeIf { it > 0.0 } ?: saved?.totalSubX ?: 0.0,
            registrarName = "Official Exchange & Registrar",
            registrarUrl = "https://www.chittorgarh.com/report/ipo-in-india-list-main-board-sme/82/",
            leadManagers = listOf("Refer DRHP / RHP Prospectus"),
            overview = overviewText,
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
            gmpSourceLabel = if (isMultiSource) "Verified • $sourcesLabel" else "Fetched • $sourcesLabel",
            sortOrder = sortOrder,
            logoUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange_logo.svg",
            bannerImageUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/Bombay_Stock_Exchange.jpg?width=900",
            liveApplicationCounts = saved?.liveApplicationCounts ?: emptyList(),
            subscriptionSourceLabel = saved?.subscriptionSourceLabel ?: "",
            subscriptionUpdatedAtMs = saved?.subscriptionUpdatedAtMs ?: 0L,
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
