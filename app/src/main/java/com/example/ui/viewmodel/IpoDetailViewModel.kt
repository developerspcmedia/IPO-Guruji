package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.IpoSeedData
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveSubscriptionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DetailTab(val label: String) {
    ALL("All"),
    OVERVIEW("Overview"),
    SUBSCRIPTION("Subscription"),
    FINANCIALS("Financials"),
}

data class IpoDetailLoadState(
    val ipo: IpoEntity? = null,
    val isLoading: Boolean = true,
)

data class SubscriptionSyncUiState(
    val isRefreshing: Boolean = false,
    val lastSyncedLabel: String? = null,
    val sourceLabel: String? = null,
    val statusMessage: String? = null,
    val isAutoPollingActive: Boolean = false,
)

class IpoDetailViewModel(
    private val ipoId: String,
    private val ipoRepository: IpoRepository,
) : ViewModel() {

    private val seedFallback: IpoEntity? = IpoSeedData.createInitialCatalog()
        .firstOrNull { it.id == ipoId }

    val loadState: StateFlow<IpoDetailLoadState> = ipoRepository.observeIpoById(ipoId)
        .map { dbEntity ->
            IpoDetailLoadState(
                ipo = dbEntity ?: seedFallback,
                isLoading = false,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = IpoDetailLoadState(
                ipo = seedFallback,
                isLoading = seedFallback == null,
            ),
        )

    val ipo: StateFlow<IpoEntity?> = loadState
        .map { it.ipo }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = seedFallback,
        )

    private val _selectedTab = MutableStateFlow(DetailTab.ALL)
    val selectedTab: StateFlow<DetailTab> = _selectedTab.asStateFlow()

    private val _subscriptionSyncState = MutableStateFlow(SubscriptionSyncUiState())
    val subscriptionSyncState: StateFlow<SubscriptionSyncUiState> = _subscriptionSyncState.asStateFlow()

    private var hasAutoRefreshedOnOpen = false
    private var marketHoursPollJob: Job? = null

    fun selectTab(tab: DetailTab) {
        _selectedTab.value = tab
    }

    fun updateLots(newLots: Int) {
        viewModelScope.launch {
            ipoRepository.updateUserLots(ipoId, newLots)
        }
    }

    /**
     * Hybrid Live Subscription trigger on IPO Detail screen entry:
     * 1. Immediately refreshes live category-wise subscription if cache is stale (> 2 min) or missing breakdown.
     * 2. If the IPO is currently LIVE during NSE/BSE bidding hours (10:00 AM – 5:15 PM IST), starts a
     *    lightweight 75-second auto-poll loop that cleans up automatically when leaving the screen.
     */
    fun onDetailScreenVisible(currentIpo: IpoEntity) {
        if (!hasAutoRefreshedOnOpen) {
            hasAutoRefreshedOnOpen = true
            val missingBreakdown = currentIpo.status == IpoStatus.LIVE &&
                currentIpo.qibSubX <= 0.0 && currentIpo.retailSubX <= 0.0
            val shouldAutoFetch = currentIpo.status == IpoStatus.LIVE ||
                currentIpo.status == IpoStatus.PRE_APPLY ||
                missingBreakdown
            if (shouldAutoFetch && (ipoRepository.isSubscriptionCacheStale() || missingBreakdown)) {
                refreshLiveSubscription(force = false, silentStatusOnNoChange = true)
            }
        }

        if (currentIpo.status == IpoStatus.LIVE && LiveSubscriptionRepository.isMarketHoursIst()) {
            startMarketHoursAutoPoll()
        } else {
            stopMarketHoursAutoPoll()
        }
    }

    fun refreshLiveSubscription(
        force: Boolean = true,
        silentStatusOnNoChange: Boolean = false,
    ) {
        if (_subscriptionSyncState.value.isRefreshing) return
        viewModelScope.launch {
            _subscriptionSyncState.update { it.copy(isRefreshing = true) }
            runCatching {
                ipoRepository.refreshLiveSubscriptionForIpo(ipoId = ipoId, force = force)
            }.onSuccess { result ->
                _subscriptionSyncState.update { current ->
                    current.copy(
                        isRefreshing = false,
                        lastSyncedLabel = result.syncedAtLabel,
                        sourceLabel = result.sourceLabel,
                        statusMessage = if (silentStatusOnNoChange && !result.updated) {
                            current.statusMessage
                        } else {
                            result.statusMessage
                        },
                    )
                }
            }.onFailure {
                _subscriptionSyncState.update { current ->
                    current.copy(
                        isRefreshing = false,
                        statusMessage = if (silentStatusOnNoChange) {
                            current.statusMessage
                        } else {
                            "Unable to reach live exchange feeds right now. Tap Verify on NSE/BSE."
                        },
                    )
                }
            }
        }
    }

    private fun startMarketHoursAutoPoll() {
        if (marketHoursPollJob?.isActive == true) return
        _subscriptionSyncState.update { it.copy(isAutoPollingActive = true) }
        marketHoursPollJob = viewModelScope.launch {
            while (isActive) {
                delay(LiveSubscriptionRepository.MARKET_POLL_INTERVAL_MS)
                if (!LiveSubscriptionRepository.isMarketHoursIst()) {
                    _subscriptionSyncState.update { it.copy(isAutoPollingActive = false) }
                    break
                }
                refreshLiveSubscription(force = true, silentStatusOnNoChange = true)
            }
        }
    }

    private fun stopMarketHoursAutoPoll() {
        marketHoursPollJob?.cancel()
        marketHoursPollJob = null
        if (_subscriptionSyncState.value.isAutoPollingActive) {
            _subscriptionSyncState.update { it.copy(isAutoPollingActive = false) }
        }
    }

    override fun onCleared() {
        stopMarketHoursAutoPoll()
        super.onCleared()
    }

    class Factory(
        private val ipoId: String,
        private val ipoRepository: IpoRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return IpoDetailViewModel(ipoId, ipoRepository) as T
        }
    }
}
