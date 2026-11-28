package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.UsIpoEntity
import com.example.data.model.UsIpoStatus
import com.example.data.repository.UsIpoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsIpoUiState(
    val allUsIpos: List<UsIpoEntity> = emptyList(),
    val filteredUsIpos: List<UsIpoEntity> = emptyList(),
    val selectedStatus: UsIpoStatus = UsIpoStatus.UPCOMING,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val lastSyncedLabel: String = "",
    val activeSourcesLabel: String = "Nasdaq • SEC EDGAR",
    val errorMessage: String? = null,
    val upcomingCount: Int = 0,
    val pricedCount: Int = 0,
    val filedCount: Int = 0,
)

private data class UsIpoFilterInputs(
    val selectedStatus: UsIpoStatus = UsIpoStatus.UPCOMING,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
)

private data class UsIpoSyncStatus(
    val isRefreshing: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val lastSyncedLabel: String = "",
    val activeSourcesLabel: String = "Nasdaq • SEC EDGAR",
    val errorMessage: String? = null,
)

class UsIpoViewModel(
    private val repository: UsIpoRepository,
) : ViewModel() {

    private val filterInputs = MutableStateFlow(UsIpoFilterInputs())
    private val syncStatus = MutableStateFlow(UsIpoSyncStatus())
    private val _secLookupInProgressIds = MutableStateFlow<Set<String>>(emptySet())
    val secLookupInProgressIds: StateFlow<Set<String>> = _secLookupInProgressIds.asStateFlow()

    private var lastRefreshEpochMs: Long = 0L

    val uiState: StateFlow<UsIpoUiState> = combine(
        repository.observeAllUsIpos(),
        filterInputs,
        syncStatus,
    ) { allItems, filters, sync ->
        val upcomingCount = allItems.count { it.status == UsIpoStatus.UPCOMING }
        val pricedCount = allItems.count { it.status == UsIpoStatus.PRICED }
        val filedCount = allItems.count { it.status == UsIpoStatus.FILED }

        val query = filters.searchQuery.trim()
        val statusMatched = allItems.filter { it.status == filters.selectedStatus }
        val searchMatched = if (query.isBlank()) {
            statusMatched
        } else {
            statusMatched.filter { item ->
                item.companyName.contains(query, ignoreCase = true) ||
                    item.symbol.contains(query, ignoreCase = true) ||
                    item.exchange.contains(query, ignoreCase = true)
            }
        }

        val sorted = when (filters.selectedStatus) {
            UsIpoStatus.UPCOMING -> searchMatched.sortedWith(
                compareBy<UsIpoEntity> { if (it.eventDateEpochMs > 0L) 0 else 1 }
                    .thenBy { it.eventDateEpochMs }
                    .thenByDescending { it.dealSizeNumericUsd }
                    .thenBy { it.companyName },
            )
            UsIpoStatus.PRICED, UsIpoStatus.FILED -> searchMatched.sortedWith(
                compareByDescending<UsIpoEntity> { it.eventDateEpochMs }
                    .thenByDescending { it.dealSizeNumericUsd }
                    .thenBy { it.companyName },
            )
        }

        UsIpoUiState(
            allUsIpos = allItems,
            filteredUsIpos = sorted,
            selectedStatus = filters.selectedStatus,
            searchQuery = filters.searchQuery,
            isSearchActive = filters.isSearchActive,
            isRefreshing = sync.isRefreshing,
            hasLoadedOnce = sync.hasLoadedOnce || allItems.isNotEmpty(),
            lastSyncedLabel = sync.lastSyncedLabel,
            activeSourcesLabel = sync.activeSourcesLabel,
            errorMessage = sync.errorMessage,
            upcomingCount = upcomingCount,
            pricedCount = pricedCount,
            filedCount = filedCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UsIpoUiState(),
    )

    fun ensureLoaded() {
        val now = System.currentTimeMillis()
        val isStale = lastRefreshEpochMs == 0L || (now - lastRefreshEpochMs) > STALE_WINDOW_MS
        if (isStale && !syncStatus.value.isRefreshing) {
            refresh(force = false)
        }
    }

    fun refresh(force: Boolean = true) {
        if (syncStatus.value.isRefreshing) return
        viewModelScope.launch {
            syncStatus.update { it.copy(isRefreshing = true, errorMessage = null) }
            val result = repository.refreshUsIpos()
            lastRefreshEpochMs = System.currentTimeMillis()
            val sourceSummary = result.activeSources
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" • ")
                ?: "Nasdaq • SEC EDGAR"

            syncStatus.update {
                it.copy(
                    isRefreshing = false,
                    hasLoadedOnce = true,
                    lastSyncedLabel = result.syncedAtLabel,
                    activeSourcesLabel = sourceSummary,
                    errorMessage = if (!result.isSuccess) result.errorMessage else null,
                )
            }
        }
    }

    fun selectStatus(status: UsIpoStatus) {
        filterInputs.update { it.copy(selectedStatus = status) }
    }

    fun setSearchQuery(query: String) {
        filterInputs.update { it.copy(searchQuery = query) }
    }

    fun setSearchActive(active: Boolean) {
        filterInputs.update {
            it.copy(
                isSearchActive = active,
                searchQuery = if (active) it.searchQuery else "",
            )
        }
    }

    fun observeDetail(dealId: String): Flow<UsIpoEntity?> = repository.observeUsIpoById(dealId)

    fun ensureSecFilingResolved(dealId: String) {
        if (dealId in _secLookupInProgressIds.value) return
        viewModelScope.launch {
            _secLookupInProgressIds.update { it + dealId }
            try {
                repository.resolveDirectSecFilingIfNeeded(dealId)
            } finally {
                _secLookupInProgressIds.update { it - dealId }
            }
        }
    }

    class Factory(
        private val repository: UsIpoRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return UsIpoViewModel(repository = repository) as T
        }
    }

    companion object {
        private const val STALE_WINDOW_MS = 15L * 60L * 1000L
    }
}
