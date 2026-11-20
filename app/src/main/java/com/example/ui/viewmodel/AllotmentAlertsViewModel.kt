package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.AllotmentWatchStatus
import com.example.data.model.IpoEntity
import com.example.data.model.PrivateUserProfileEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AllotmentRepository
import com.example.notifications.IpoNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PortfolioPnlSummary(
    val blockedUpiCapital: Long = 0L,
    val allottedCostCapital: Long = 0L,
    val estimatedListingValue: Long = 0L,
    val netProfitLossRupees: Long = 0L,
    val netProfitLossPercent: Double = 0.0,
    val activeBlockedCount: Int = 0,
    val allottedCount: Int = 0,
    val notAllottedCount: Int = 0,
) {
    val settledCount: Int
        get() = allottedCount + notAllottedCount

    val winRatePercent: Int?
        get() = if (settledCount > 0) ((allottedCount * 100.0) / settledCount).toInt() else null
}

object PortfolioPnlCalculator {
    fun compute(
        watches: List<AllotmentWatchEntity>,
        iposById: Map<String, IpoEntity>,
    ): PortfolioPnlSummary {
        var blockedCapital = 0L
        var allottedCost = 0L
        var listingValue = 0L
        var activeBlockedCount = 0
        var allottedCount = 0
        var notAllottedCount = 0

        watches.forEach { watch ->
            val ipo = iposById[watch.ipoId]
            val issuePrice = ipo?.priceHigh?.coerceAtLeast(0) ?: 0
            val estListingPrice = ipo?.estimatedListingPrice?.coerceAtLeast(0) ?: issuePrice
            val appliedShares = watch.totalSharesApplied.toLong()

            when (watch.status) {
                AllotmentWatchStatus.PENDING,
                AllotmentWatchStatus.RESULT_LIVE,
                -> {
                    activeBlockedCount += 1
                    blockedCapital += appliedShares * issuePrice.toLong()
                }

                AllotmentWatchStatus.ALLOTTED -> {
                    allottedCount += 1
                    val shares = if (watch.sharesAllotted > 0) watch.sharesAllotted.toLong() else appliedShares
                    allottedCost += shares * issuePrice.toLong()
                    listingValue += shares * estListingPrice.toLong()
                }

                AllotmentWatchStatus.NOT_ALLOTTED -> {
                    notAllottedCount += 1
                }
            }
        }

        val netPnl = listingValue - allottedCost
        val pnlPct = if (allottedCost > 0L) {
            (netPnl.toDouble() / allottedCost.toDouble()) * 100.0
        } else {
            0.0
        }

        return PortfolioPnlSummary(
            blockedUpiCapital = blockedCapital,
            allottedCostCapital = allottedCost,
            estimatedListingValue = listingValue,
            netProfitLossRupees = netPnl,
            netProfitLossPercent = pnlPct,
            activeBlockedCount = activeBlockedCount,
            allottedCount = allottedCount,
            notAllottedCount = notAllottedCount,
        )
    }
}

data class AllotmentAlertsUiState(
    val profiles: List<PrivateUserProfileEntity> = emptyList(),
    val allWatches: List<AllotmentWatchEntity> = emptyList(),
    val filteredWatches: List<AllotmentWatchEntity> = emptyList(),
    val selectedProfileFilterId: String? = null,
    val notifyAllotmentEnabled: Boolean = true,
    val isCheckingAll: Boolean = false,
    val checkingWatchId: String? = null,
    val feedbackMessage: String? = null,
    val errorMessage: String? = null,
) {
    val pendingCount: Int
        get() = allWatches.count {
            it.status == AllotmentWatchStatus.PENDING || it.status == AllotmentWatchStatus.RESULT_LIVE
        }

    val allottedCount: Int
        get() = allWatches.count { it.status == AllotmentWatchStatus.ALLOTTED }
}

class AllotmentAlertsViewModel(
    private val allotmentRepository: AllotmentRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val appContext: Context? = null,
) : ViewModel() {

    private val selectedProfileFilterId = MutableStateFlow<String?>(null)
    private val isCheckingAll = MutableStateFlow(false)
    private val checkingWatchId = MutableStateFlow<String?>(null)
    private val feedbackMessage = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val statusUiFlow = combine(
        isCheckingAll,
        checkingWatchId,
        feedbackMessage,
        errorMessage,
    ) { checkingAll, checkingId, feedback, error ->
        StatusFlags(checkingAll, checkingId, feedback, error)
    }

    val uiState: StateFlow<AllotmentAlertsUiState> = combine(
        allotmentRepository.observeProfiles(),
        allotmentRepository.observeWatches(),
        selectedProfileFilterId,
        preferencesRepository.notifyAllotmentEnabled,
        statusUiFlow,
    ) { profiles, watches, filterProfileId, notifyEnabled, flags ->
        val validFilterId = filterProfileId?.takeIf { id -> profiles.any { it.id == id } }
        val filtered = if (validFilterId == null) {
            watches
        } else {
            watches.filter { it.profileId == validFilterId }
        }
        AllotmentAlertsUiState(
            profiles = profiles,
            allWatches = watches,
            filteredWatches = filtered,
            selectedProfileFilterId = validFilterId,
            notifyAllotmentEnabled = notifyEnabled,
            isCheckingAll = flags.isCheckingAll,
            checkingWatchId = flags.checkingWatchId,
            feedbackMessage = flags.feedbackMessage,
            errorMessage = flags.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AllotmentAlertsUiState(),
    )

    fun selectProfileFilter(profileId: String?) {
        selectedProfileFilterId.value = profileId
    }

    fun clearMessages() {
        feedbackMessage.value = null
        errorMessage.value = null
    }

    fun setNotifyAllotmentEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyAllotmentEnabled(enabled)
            syncBackgroundMonitorSchedule(enabledOverride = enabled)
            feedbackMessage.value = if (enabled) {
                "Auto allotment monitoring enabled (checks every 30 mins while pending)."
            } else {
                "Auto allotment monitoring paused."
            }
        }
    }

    fun saveProfile(
        existingId: String? = null,
        displayName: String,
        panNumber: String,
        dematId: String = "",
        upiHandle: String = "",
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            clearMessages()
            val result = allotmentRepository.saveProfile(
                existingId = existingId,
                displayName = displayName,
                panNumber = panNumber,
                dematId = dematId,
                upiHandle = upiHandle,
            )
            result.onSuccess { saved ->
                feedbackMessage.value = "Saved private profile \"${saved.displayName}\" (${saved.maskedPan}) on this device."
                onSuccess()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Could not save profile."
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            clearMessages()
            allotmentRepository.deleteProfile(profileId)
            if (selectedProfileFilterId.value == profileId) {
                selectedProfileFilterId.value = null
            }
            syncBackgroundMonitorSchedule()
            feedbackMessage.value = "Private profile and its tracked alerts deleted from this device."
        }
    }

    fun trackIpoForProfiles(
        ipo: IpoEntity,
        profileIds: List<String>,
        lotsApplied: Int,
        applicationNumber: String = "",
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            clearMessages()
            val result = allotmentRepository.trackIpoForProfiles(
                ipo = ipo,
                profileIds = profileIds,
                lotsApplied = lotsApplied,
                applicationNumber = applicationNumber,
            )
            result.onSuccess { created ->
                syncBackgroundMonitorSchedule()
                val label = if (created.size == 1) "1 profile" else "${created.size} profiles"
                feedbackMessage.value = "Allotment alert active for ${ipo.companyName} ($label)."
                onSuccess()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Could not add IPO allotment alert."
            }
        }
    }

    fun deleteWatch(watchId: String) {
        viewModelScope.launch {
            clearMessages()
            allotmentRepository.deleteWatch(watchId)
            syncBackgroundMonitorSchedule()
            feedbackMessage.value = "Removed IPO allotment tracker."
        }
    }

    fun checkSingleWatchNow(watchId: String) {
        if (checkingWatchId.value != null) return
        viewModelScope.launch {
            clearMessages()
            checkingWatchId.value = watchId
            try {
                val event = allotmentRepository.checkSingleWatch(watchId)
                if (event != null) {
                    val context = appContext
                    if (context != null && uiState.value.notifyAllotmentEnabled) {
                        IpoNotificationManager.postAllotmentEventNotification(context, event)
                    }
                    feedbackMessage.value = "${event.watch.ipoName} (${event.watch.profileName}): ${event.watch.lastCheckSummary}"
                }
                syncBackgroundMonitorSchedule()
            } finally {
                checkingWatchId.value = null
            }
        }
    }

    fun checkAllPendingNow(silentIfEmpty: Boolean = false) {
        if (isCheckingAll.value) return
        viewModelScope.launch {
            if (!silentIfEmpty) clearMessages()
            isCheckingAll.value = true
            try {
                val result = allotmentRepository.checkAllPendingWatches()
                val context = appContext
                if (context != null && uiState.value.notifyAllotmentEnabled) {
                    result.events.forEach { event ->
                        IpoNotificationManager.postAllotmentEventNotification(context, event)
                    }
                }
                if (!silentIfEmpty || result.checkedCount > 0) {
                    feedbackMessage.value = result.summaryMessage
                }
                syncBackgroundMonitorSchedule()
            } finally {
                isCheckingAll.value = false
            }
        }
    }

    fun confirmManualResult(
        watchId: String,
        status: AllotmentWatchStatus,
        sharesAllotted: Int,
        sendInstantNotification: Boolean = true,
    ) {
        viewModelScope.launch {
            clearMessages()
            val event = allotmentRepository.confirmManualStatus(
                watchId = watchId,
                status = status,
                sharesAllotted = sharesAllotted,
            )
            if (event != null) {
                val context = appContext
                if (context != null && sendInstantNotification && uiState.value.notifyAllotmentEnabled) {
                    IpoNotificationManager.postAllotmentEventNotification(context, event)
                }
                feedbackMessage.value = "${event.watch.ipoName} (${event.watch.profileName}): ${event.watch.lastCheckSummary}"
            }
            syncBackgroundMonitorSchedule()
        }
    }

    fun sendTestAllotmentNotification() {
        val context = appContext ?: return
        val firstWatch = uiState.value.allWatches.firstOrNull()
        val firstProfile = uiState.value.profiles.firstOrNull()
        IpoNotificationManager.postSampleAllotmentNotification(
            context = context,
            profileName = firstWatch?.profileName ?: firstProfile?.displayName ?: "Private Profile",
            maskedPan = firstWatch?.maskedPan ?: firstProfile?.maskedPan ?: "ABCD••••EF",
            ipoName = firstWatch?.ipoName ?: "Tracked IPO",
        )
        feedbackMessage.value = "Sent a private test allotment alert to your notification tray."
    }

    fun wipeAllPrivateData() {
        viewModelScope.launch {
            clearMessages()
            allotmentRepository.wipeAllPrivateData()
            selectedProfileFilterId.value = null
            appContext?.let { IpoNotificationManager.scheduleAllotmentMonitor(it, enabled = false) }
            feedbackMessage.value = "All private profiles and allotment trackers wiped from this device."
        }
    }

    private suspend fun syncBackgroundMonitorSchedule(enabledOverride: Boolean? = null) {
        val context = appContext ?: return
        val enabled = enabledOverride ?: preferencesRepository.getNotificationSnapshot().notifyAllotmentEnabled
        val hasPending = allotmentRepository.hasPendingWatches()
        IpoNotificationManager.scheduleAllotmentMonitor(context, enabled = enabled && hasPending)
    }

    private data class StatusFlags(
        val isCheckingAll: Boolean,
        val checkingWatchId: String?,
        val feedbackMessage: String?,
        val errorMessage: String?,
    )

    class Factory(
        private val allotmentRepository: AllotmentRepository,
        private val preferencesRepository: UserPreferencesRepository,
        private val appContext: Context? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AllotmentAlertsViewModel(
                allotmentRepository = allotmentRepository,
                preferencesRepository = preferencesRepository,
                appContext = appContext,
            ) as T
        }
    }
}
