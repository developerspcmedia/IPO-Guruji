package com.example.data.repository

import com.example.data.local.UsIpoDao
import com.example.data.model.UsIpoEntity
import com.example.data.model.UsIpoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UsIpoSyncResult(
    val syncedCount: Int,
    val syncedAtLabel: String,
    val activeSources: List<String>,
    val isSuccess: Boolean,
    val errorMessage: String? = null,
)

class UsIpoRepository(
    private val usIpoDao: UsIpoDao,
    private val remoteDataSource: UsIpoRemoteDataSource = UsIpoRemoteDataSource(),
) {
    fun observeAllUsIpos(): Flow<List<UsIpoEntity>> = usIpoDao.observeAllUsIpos()

    fun observeUsIpoById(dealId: String): Flow<UsIpoEntity?> = usIpoDao.observeById(dealId)

    suspend fun getCachedCount(): Int = withContext(Dispatchers.IO) {
        usIpoDao.getAllSnapshot().size
    }

    suspend fun refreshUsIpos(nowMs: Long = System.currentTimeMillis()): UsIpoSyncResult =
        withContext(Dispatchers.IO) {
            val snapshot = remoteDataSource.fetchLiveSnapshot(nowMs = nowMs)
            val merged = mergeNasdaqAndSecItems(
                nasdaqItems = snapshot.nasdaqItems,
                secItems = snapshot.secItems,
            )

            val timeLabel = SimpleDateFormat("hh:mm a", Locale.US).format(Date(nowMs))

            if (merged.isNotEmpty()) {
                usIpoDao.replaceAll(merged)
                UsIpoSyncResult(
                    syncedCount = merged.size,
                    syncedAtLabel = timeLabel,
                    activeSources = snapshot.activeSources,
                    isSuccess = true,
                    errorMessage = null,
                )
            } else {
                UsIpoSyncResult(
                    syncedCount = usIpoDao.getAllSnapshot().size,
                    syncedAtLabel = timeLabel,
                    activeSources = snapshot.activeSources,
                    isSuccess = snapshot.errorSummary == null,
                    errorMessage = snapshot.errorSummary,
                )
            }
        }

    suspend fun resolveDirectSecFilingIfNeeded(dealId: String): Boolean = withContext(Dispatchers.IO) {
        val current = usIpoDao.getById(dealId) ?: return@withContext false
        if (current.hasDirectSecArchiveUrl) return@withContext true

        val match = remoteDataSource.lookupDirectSecFiling(
            companyName = current.companyName,
            symbol = current.symbol,
        ) ?: return@withContext false

        val mergedSource = if (current.sourceLabel.contains("SEC", ignoreCase = true)) {
            current.sourceLabel
        } else {
            "${current.sourceLabel} • SEC EDGAR"
        }
        usIpoDao.updateSecFiling(
            dealId = current.dealId,
            formType = match.formType,
            filingUrl = match.filingUrl,
            sourceLabel = mergedSource,
        )
        true
    }

    internal fun mergeNasdaqAndSecItems(
        nasdaqItems: List<UsIpoEntity>,
        secItems: List<UsIpoEntity>,
    ): List<UsIpoEntity> {
        val byKey = LinkedHashMap<String, UsIpoEntity>()

        // Prioritize status order: PRICED > UPCOMING > FILED when the same deal appears across adjacent months
        fun statusPriority(status: UsIpoStatus): Int = when (status) {
            UsIpoStatus.PRICED -> 3
            UsIpoStatus.UPCOMING -> 2
            UsIpoStatus.FILED -> 1
        }

        val secByCompany = secItems.associateBy { UsIpoRemoteDataSource.normalizeCompanyKey(it.companyName) }

        nasdaqItems.forEach { item ->
            val key = UsIpoRemoteDataSource.normalizeCompanyKey(item.companyName)
                .ifBlank { item.dealId }
            val secMatch = secByCompany[key]
            val enriched = if (secMatch != null) {
                item.copy(
                    secFormType = secMatch.secFormType.ifBlank { item.secFormType },
                    secFilingUrl = if (secMatch.hasDirectSecArchiveUrl) secMatch.secFilingUrl else item.secFilingUrl,
                    sourceLabel = "Nasdaq • SEC EDGAR",
                )
            } else {
                item
            }

            val existing = byKey[key]
            if (existing == null ||
                statusPriority(enriched.status) > statusPriority(existing.status) ||
                (statusPriority(enriched.status) == statusPriority(existing.status) &&
                    enriched.dealSizeNumericUsd > existing.dealSizeNumericUsd)
            ) {
                byKey[key] = enriched
            }
        }

        // Add recent SEC S-1 / F-1 filings not yet listed in Nasdaq's monthly calendar
        secItems.forEach { secItem ->
            val key = UsIpoRemoteDataSource.normalizeCompanyKey(secItem.companyName)
                .ifBlank { secItem.dealId }
            if (key !in byKey) {
                byKey[key] = secItem
            }
        }

        return byKey.values.toList()
    }
}
