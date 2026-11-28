package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.InvestorCategory
import com.example.data.model.IpoEntity
import com.example.data.model.UserProfileDocument
import com.example.data.preferences.CloudSyncedPrefsSnapshot
import com.example.data.preferences.ThemePreference
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.IpoRepository
import com.example.data.repository.UserProfileRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserProfileUiState(
    val displayName: String = "",
    val email: String = "",
    val investorCategory: InvestorCategory = InvestorCategory.RETAIL,
    val bio: String = "",
    val bookmarkedIpos: List<IpoEntity> = emptyList(),
    val bookmarkedIds: Set<String> = emptySet(),
    val preferences: CloudSyncedPrefsSnapshot = CloudSyncedPrefsSnapshot(),
    val isSignedIn: Boolean = false,
    val isCloudSynced: Boolean = false,
    val isSaving: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
)

private data class UserProfileTransientState(
    val isSaving: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
)

class UserProfileViewModel(
    private val userId: String,
    private val fallbackDisplayName: String,
    private val fallbackEmail: String,
    private val ipoRepository: IpoRepository,
    private val userProfileRepository: UserProfileRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val transientStateFlow = MutableStateFlow(UserProfileTransientState())

    private val localPrefsFlow: Flow<CloudSyncedPrefsSnapshot> = combine(
        combine(
            preferencesRepository.themePreference,
            preferencesRepository.compactCardsEnabled,
            preferencesRepository.notifyNewIpoEnabled,
        ) { theme, compact, newIpo -> Triple(theme, compact, newIpo) },
        combine(
            preferencesRepository.notifyOpenIpoEnabled,
            preferencesRepository.notifyAllotmentEnabled,
            preferencesRepository.notifyNewsAlertsEnabled,
        ) { openIpo, allotment, newsAlerts -> Triple(openIpo, allotment, newsAlerts) },
    ) { (theme, compact, newIpo), (openIpo, allotment, newsAlerts) ->
        CloudSyncedPrefsSnapshot(
            themePreference = theme,
            compactCards = compact,
            notifyNewIpo = newIpo,
            notifyOpenIpo = openIpo,
            notifyAllotment = allotment,
            notifyNewsAlerts = newsAlerts,
        )
    }

    private val cloudProfileFlow: Flow<UserProfileDocument?> = if (userId.isNotBlank()) {
        userProfileRepository.observeUserProfile(userId)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                transientStateFlow.update {
                    it.copy(
                        errorMessage = firestoreError?.localizedMessage
                            ?: error.localizedMessage
                            ?: "Unable to reach cloud profile right now.",
                    )
                }
                emit(null)
            }
    } else {
        flowOf(null)
    }

    val uiState: StateFlow<UserProfileUiState> = combine(
        cloudProfileFlow,
        ipoRepository.observeAllIpos(),
        preferencesRepository.bookmarkedIpoIds,
        localPrefsFlow,
        transientStateFlow,
    ) { cloudProfile, allIpos, localBookmarks, localPrefs, transient ->
        val effectiveBookmarks = if (cloudProfile != null && userId.isNotBlank()) {
            (localBookmarks + cloudProfile.bookmarkedIpoIds).toSet()
        } else {
            localBookmarks
        }
        val bookmarkedList = allIpos.filter { it.id in effectiveBookmarks }
        UserProfileUiState(
            displayName = cloudProfile?.displayName?.ifBlank { fallbackDisplayName } ?: fallbackDisplayName,
            email = cloudProfile?.email?.ifBlank { fallbackEmail } ?: fallbackEmail,
            investorCategory = cloudProfile?.categoryEnum ?: InvestorCategory.RETAIL,
            bio = cloudProfile?.bio.orEmpty(),
            bookmarkedIpos = bookmarkedList,
            bookmarkedIds = effectiveBookmarks,
            preferences = localPrefs,
            isSignedIn = userId.isNotBlank(),
            isCloudSynced = userId.isNotBlank() && cloudProfile != null,
            isSaving = transient.isSaving,
            isDeletingAccount = transient.isDeletingAccount,
            errorMessage = transient.errorMessage,
            snackbarMessage = transient.snackbarMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserProfileUiState(
            displayName = fallbackDisplayName,
            email = fallbackEmail,
            isSignedIn = userId.isNotBlank(),
        ),
    )

    init {
        if (userId.isNotBlank()) {
            hydrateCloudProfile()
        }
    }

    private fun hydrateCloudProfile() {
        viewModelScope.launch {
            userProfileRepository.ensureAndHydrateOnSignIn(preferencesRepository)
                .onFailure { err ->
                    transientStateFlow.update {
                        it.copy(
                            errorMessage = err.localizedMessage
                                ?: "Signed in, but cloud profile sync will retry shortly.",
                        )
                    }
                }
        }
    }

    fun saveInvestorDetails(
        displayName: String,
        category: InvestorCategory,
        bio: String,
    ) {
        if (userId.isBlank()) {
            transientStateFlow.update {
                it.copy(errorMessage = "Sign in with Google to save your investor profile.")
            }
            return
        }
        viewModelScope.launch {
            transientStateFlow.update { it.copy(isSaving = true, errorMessage = null) }
            val bookmarks = preferencesRepository.getBookmarkedIpoIdsSnapshot()
            val prefs = preferencesRepository.getCurrentThemeAndAlertSnapshot()
            val result = userProfileRepository.saveInvestorDetails(
                displayName = displayName,
                category = category,
                bio = bio,
                currentBookmarks = bookmarks,
                currentPrefs = prefs,
            )
            result.onSuccess {
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        snackbarMessage = "Profile updated and synced to cloud",
                    )
                }
            }.onFailure { err ->
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = err.localizedMessage ?: "Unable to save profile right now.",
                    )
                }
            }
        }
    }

    fun removeBookmark(ipoId: String) {
        viewModelScope.launch {
            preferencesRepository.toggleBookmarkIpo(ipoId)
            if (userId.isNotBlank()) {
                val updatedBookmarks = preferencesRepository.getBookmarkedIpoIdsSnapshot()
                val currentPrefs = preferencesRepository.getCurrentThemeAndAlertSnapshot()
                userProfileRepository.syncBookmarksToCloud(updatedBookmarks, currentPrefs)
            }
        }
    }

    fun updateThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            preferencesRepository.setThemePreference(theme)
            syncCurrentPreferencesIfSignedIn()
        }
    }

    fun updateCompactCards(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setCompactCardsEnabled(enabled)
            syncCurrentPreferencesIfSignedIn()
        }
    }

    fun updateNotifyNewIpo(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyNewIpoEnabled(enabled)
            syncCurrentPreferencesIfSignedIn()
        }
    }

    fun updateNotifyOpenIpo(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyOpenIpoEnabled(enabled)
            syncCurrentPreferencesIfSignedIn()
        }
    }

    fun updateNotifyAllotment(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyAllotmentEnabled(enabled)
            syncCurrentPreferencesIfSignedIn()
        }
    }

    fun updateNotifyNewsAlerts(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotifyNewsAlertsEnabled(enabled)
            syncCurrentPreferencesIfSignedIn()
        }
    }

    private suspend fun syncCurrentPreferencesIfSignedIn() {
        if (userId.isBlank()) return
        val prefs = preferencesRepository.getCurrentThemeAndAlertSnapshot()
        val bookmarks = preferencesRepository.getBookmarkedIpoIdsSnapshot()
        userProfileRepository.syncPreferencesToCloud(prefs, bookmarks)
    }

    fun deleteAccountAndCloudData(onAccountDeleted: (String) -> Unit) {
        if (userId.isBlank()) return
        viewModelScope.launch {
            transientStateFlow.update {
                it.copy(isDeletingAccount = true, errorMessage = null, snackbarMessage = null)
            }
            val result = userProfileRepository.deleteCloudProfileAndAccount()
            result.onSuccess { authDeleted ->
                val message = if (authDeleted) {
                    "Account and cloud profile permanently deleted."
                } else {
                    "Cloud profile deleted and signed out. Re-sign-in or email support to remove auth login."
                }
                transientStateFlow.update {
                    it.copy(
                        isDeletingAccount = false,
                        snackbarMessage = message,
                    )
                }
                onAccountDeleted(message)
            }.onFailure { err ->
                transientStateFlow.update {
                    it.copy(
                        isDeletingAccount = false,
                        errorMessage = err.localizedMessage ?: "Unable to delete cloud profile right now.",
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        transientStateFlow.update { it.copy(errorMessage = null, snackbarMessage = null) }
    }

    class Factory(
        private val userId: String,
        private val fallbackDisplayName: String,
        private val fallbackEmail: String,
        private val ipoRepository: IpoRepository,
        private val userProfileRepository: UserProfileRepository,
        private val preferencesRepository: UserPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return UserProfileViewModel(
                userId = userId,
                fallbackDisplayName = fallbackDisplayName,
                fallbackEmail = fallbackEmail,
                ipoRepository = ipoRepository,
                userProfileRepository = userProfileRepository,
                preferencesRepository = preferencesRepository,
            ) as T
        }
    }
}
