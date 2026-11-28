package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ai.IpoAiRepository
import com.example.data.local.IpoSeedData
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveSubscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeout

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
    val sourceStatusesLabel: String? = null,
    val isAutoPollingActive: Boolean = false,
)

sealed interface IpoAiUiState {
    data object Idle : IpoAiUiState
    data object Loading : IpoAiUiState
    data class Streaming(val text: String, val activePrompt: String) : IpoAiUiState
    data class Success(val text: String, val activePrompt: String) : IpoAiUiState
    data class Error(val message: String, val activePrompt: String) : IpoAiUiState
}

class IpoDetailViewModel(
    private val ipoId: String,
    private val ipoRepository: IpoRepository,
    private val ipoAiRepository: IpoAiRepository = IpoAiRepository(),
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

    private val _aiState = MutableStateFlow<IpoAiUiState>(IpoAiUiState.Idle)
    val aiState: StateFlow<IpoAiUiState> = _aiState.asStateFlow()

    private var hasAutoRefreshedOnOpen = false
    private var lastSuccessfulSyncedLabel: String? = null
    private var latestObservedIpo: IpoEntity? = null
    private var currentPollIntervalMs: Long? = null
    private var marketHoursPollJob: Job? = null
    private var aiGenerationJob: Job? = null

    fun selectTab(tab: DetailTab) {
        _selectedTab.value = tab
    }

    fun updateLots(newLots: Int) {
        viewModelScope.launch {
            ipoRepository.updateUserLots(ipoId, newLots)
        }
    }

    /**
     * Records the observed IPO freshness label on Detail screen entry.
     * Continuous polling is disabled to protect Firestore's free read quota; users can still
     * tap manual refresh (rate-limited to once per minute in IpoRepository).
     */
    fun onDetailScreenVisible(
        currentIpo: IpoEntity,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        latestObservedIpo = currentIpo
        if (lastSuccessfulSyncedLabel == null && currentIpo.subscriptionUpdatedAtMs > 0L) {
            lastSuccessfulSyncedLabel = LiveSubscriptionRepository.formatTimeLabel(currentIpo.subscriptionUpdatedAtMs)
        }
        stopMarketHoursAutoPoll()
    }

    fun onDetailScreenHidden() {
        stopMarketHoursAutoPoll()
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
                val sourcesLine = LiveSubscriptionRepository.formatSourceStatuses(result.sourceStatuses)
                if (result.hasLiveSourceData && result.syncedAtLabel.isNotBlank()) {
                    lastSuccessfulSyncedLabel = result.syncedAtLabel
                    _subscriptionSyncState.update { current ->
                        current.copy(
                            isRefreshing = false,
                            lastSyncedLabel = result.syncedAtLabel,
                            sourceLabel = result.sourceLabel,
                            sourceStatusesLabel = sourcesLine ?: current.sourceStatusesLabel,
                            statusMessage = if (silentStatusOnNoChange && !result.updated) {
                                current.statusMessage
                            } else {
                                result.statusMessage
                            },
                        )
                    }
                } else {
                    val failureMessage = lastSuccessfulSyncedLabel?.let {
                        "Last updated $it (couldn't refresh now)"
                    } ?: "Couldn't refresh now"
                    _subscriptionSyncState.update { current ->
                        current.copy(
                            isRefreshing = false,
                            lastSyncedLabel = lastSuccessfulSyncedLabel ?: current.lastSyncedLabel,
                            sourceStatusesLabel = sourcesLine ?: current.sourceStatusesLabel,
                            statusMessage = if (silentStatusOnNoChange) {
                                current.statusMessage
                            } else {
                                failureMessage
                            },
                        )
                    }
                }
            }.onFailure {
                val failureMessage = lastSuccessfulSyncedLabel?.let {
                    "Last updated $it (couldn't refresh now)"
                } ?: "Couldn't refresh now"
                _subscriptionSyncState.update { current ->
                    current.copy(
                        isRefreshing = false,
                        lastSyncedLabel = lastSuccessfulSyncedLabel ?: current.lastSyncedLabel,
                        statusMessage = if (silentStatusOnNoChange) {
                            current.statusMessage
                        } else {
                            failureMessage
                        },
                    )
                }
            }
        }
    }

    private fun startMarketHoursAutoPoll(intervalMs: Long) {
        if (marketHoursPollJob?.isActive == true && currentPollIntervalMs == intervalMs) return
        marketHoursPollJob?.cancel()
        currentPollIntervalMs = intervalMs
        _subscriptionSyncState.update { it.copy(isAutoPollingActive = true) }
        marketHoursPollJob = viewModelScope.launch {
            var activeIntervalMs = intervalMs
            while (isActive) {
                delay(activeIntervalMs)
                val targetIpo = latestObservedIpo ?: ipo.value
                val nextIntervalMs = targetIpo?.let {
                    LiveSubscriptionRepository.resolveDetailPollIntervalMs(
                        openDate = it.openDate,
                        closeDate = it.closeDate,
                        listingDate = it.listingDate,
                        status = it.status,
                    )
                }
                if (nextIntervalMs == null) {
                    currentPollIntervalMs = null
                    _subscriptionSyncState.update { it.copy(isAutoPollingActive = false) }
                    break
                }
                activeIntervalMs = nextIntervalMs
                currentPollIntervalMs = nextIntervalMs
                refreshLiveSubscription(force = true, silentStatusOnNoChange = true)
            }
        }
    }

    private fun stopMarketHoursAutoPoll() {
        marketHoursPollJob?.cancel()
        marketHoursPollJob = null
        currentPollIntervalMs = null
        if (_subscriptionSyncState.value.isAutoPollingActive) {
            _subscriptionSyncState.update { it.copy(isAutoPollingActive = false) }
        }
    }

    fun askIpoAi(
        currentIpo: IpoEntity,
        question: String? = null,
    ) {
        val activePrompt = question?.trim().orEmpty()
        aiGenerationJob?.cancel()
        aiGenerationJob = viewModelScope.launch {
            _aiState.value = IpoAiUiState.Loading
            val accumulatedText = StringBuilder()
            try {
                withTimeout(AI_GENERATION_TIMEOUT_MS) {
                    ipoAiRepository.streamIpoAnalysis(
                        ipo = currentIpo,
                        userQuestion = activePrompt.ifBlank { null },
                    ).collect { chunkText ->
                        accumulatedText.append(chunkText)
                        _aiState.value = IpoAiUiState.Streaming(
                            text = accumulatedText.toString(),
                            activePrompt = activePrompt,
                        )
                    }
                }
                if (accumulatedText.isNotBlank()) {
                    _aiState.value = IpoAiUiState.Success(
                        text = accumulatedText.toString().trim(),
                        activePrompt = activePrompt,
                    )
                } else {
                    _aiState.value = IpoAiUiState.Error(
                        message = "Model returned an empty response. Please try again.",
                        activePrompt = activePrompt,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(AI_TAG, "Gemini IPO analysis timed out", e)
                _aiState.value = IpoAiUiState.Error(
                    message = "Analysis timed out after 60 seconds. Please try again.",
                    activePrompt = activePrompt,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AI_TAG, "Firebase AI generation failed for ${currentIpo.companyName}", e)
                _aiState.value = IpoAiUiState.Error(
                    message = e.localizedMessage ?: "Unable to generate AI insight right now.",
                    activePrompt = activePrompt,
                )
            }
        }
    }

    fun clearAiResponse() {
        aiGenerationJob?.cancel()
        aiGenerationJob = null
        _aiState.value = IpoAiUiState.Idle
    }

    override fun onCleared() {
        stopMarketHoursAutoPoll()
        aiGenerationJob?.cancel()
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

    companion object {
        private const val AI_TAG = "IpoDetailAiVM"
        private const val AI_GENERATION_TIMEOUT_MS = 60_000L
    }
}
