package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.AdminProfileDocument
import com.example.data.model.IpoEntity
import com.example.data.model.IpoOverrideDocument
import com.example.data.model.IpoOverrideMerger
import com.example.data.repository.AdminOverrideRepository
import com.example.data.repository.IpoRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdminFilterTab(val label: String) {
    ALL("All"),
    OVERRIDDEN("Overridden"),
    CUSTOM("Custom"),
    HIDDEN("Hidden"),
}

data class AdminIpoRowItem(
    val id: String,
    val effectiveIpo: IpoEntity,
    val baseLiveIpo: IpoEntity?,
    val overrideDoc: IpoOverrideDocument?,
) {
    val isOverridden: Boolean get() = overrideDoc != null
    val isCustom: Boolean get() = overrideDoc?.customIpo == true || baseLiveIpo == null
    val isHidden: Boolean get() = overrideDoc?.hidden == true
    val manualFieldCount: Int get() = overrideDoc?.overriddenFieldCount(baseLiveIpo) ?: 0
    val hasManualGmp: Boolean get() = overrideDoc?.gmpRupees != null
}

data class AdminDashboardUiState(
    val rows: List<AdminIpoRowItem> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: AdminFilterTab = AdminFilterTab.ALL,
    val totalCount: Int = 0,
    val overriddenCount: Int = 0,
    val customCount: Int = 0,
    val hiddenCount: Int = 0,
    val adminProfile: AdminProfileDocument? = null,
    val isSaving: Boolean = false,
    val isSyncingCloud: Boolean = false,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
)

private data class AdminTransientState(
    val adminProfile: AdminProfileDocument? = null,
    val isSaving: Boolean = false,
    val isSyncingCloud: Boolean = false,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
)

class AdminDashboardViewModel(
    private val ipoRepository: IpoRepository,
    private val adminOverrideRepository: AdminOverrideRepository,
    private val currentUserId: String,
    private val isAuthorizedAdmin: Boolean,
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val selectedFilterFlow = MutableStateFlow(AdminFilterTab.ALL)
    private val transientStateFlow = MutableStateFlow(AdminTransientState())

    val uiState: StateFlow<AdminDashboardUiState> = combine(
        ipoRepository.observeRawCatalogForAdmin(),
        adminOverrideRepository.observeCachedOverrides(),
        searchQueryFlow,
        selectedFilterFlow,
        transientStateFlow,
    ) { rawCatalog, overrides, query, filter, transient ->
        val allRows = buildAdminRows(rawCatalog, overrides)
        val filteredRows = allRows.filter { row ->
            val matchesFilter = when (filter) {
                AdminFilterTab.ALL -> true
                AdminFilterTab.OVERRIDDEN -> row.isOverridden
                AdminFilterTab.CUSTOM -> row.isCustom
                AdminFilterTab.HIDDEN -> row.isHidden
            }
            val q = query.trim()
            val matchesQuery = q.isEmpty() ||
                row.effectiveIpo.companyName.contains(q, ignoreCase = true) ||
                row.effectiveIpo.shortCode.contains(q, ignoreCase = true) ||
                row.effectiveIpo.sector.contains(q, ignoreCase = true) ||
                row.effectiveIpo.registrarName.contains(q, ignoreCase = true)
            matchesFilter && matchesQuery
        }

        AdminDashboardUiState(
            rows = filteredRows,
            searchQuery = query,
            selectedFilter = filter,
            totalCount = allRows.size,
            overriddenCount = allRows.count { it.isOverridden },
            customCount = allRows.count { it.isCustom },
            hiddenCount = allRows.count { it.isHidden },
            adminProfile = transient.adminProfile,
            isSaving = transient.isSaving,
            isSyncingCloud = transient.isSyncingCloud,
            errorMessage = transient.errorMessage,
            snackbarMessage = transient.snackbarMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AdminDashboardUiState(),
    )

    init {
        if (isAuthorizedAdmin && currentUserId.isNotBlank()) {
            syncAdminProfile()
            observeCloudOverridesLive()
        }
    }

    private fun observeCloudOverridesLive() {
        viewModelScope.launch {
            adminOverrideRepository.observePublishedCloudOverrides()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    transientStateFlow.update {
                        it.copy(
                            errorMessage = firestoreError?.localizedMessage
                                ?: error.localizedMessage
                                ?: "Unable to sync cloud overrides right now.",
                        )
                    }
                }
                .collect {
                    // Cache is automatically refreshed by observePublishedCloudOverrides().onEach
                }
        }
    }

    private fun syncAdminProfile() {
        viewModelScope.launch {
            val result = adminOverrideRepository.syncAdminProfile()
            result.onSuccess { profile ->
                transientStateFlow.update { it.copy(adminProfile = profile) }
            }.onFailure { err ->
                transientStateFlow.update {
                    it.copy(
                        errorMessage = err.localizedMessage
                            ?: "Signed in, but could not refresh Admin profile record.",
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun setFilter(filter: AdminFilterTab) {
        selectedFilterFlow.value = filter
    }

    fun refreshCloudOverrides() {
        viewModelScope.launch {
            transientStateFlow.update { it.copy(isSyncingCloud = true, errorMessage = null) }
            try {
                adminOverrideRepository.fetchPublishedCloudOverridesOnce()
                transientStateFlow.update {
                    it.copy(
                        isSyncingCloud = false,
                        snackbarMessage = "Synced latest cloud overrides",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transientStateFlow.update {
                    it.copy(
                        isSyncingCloud = false,
                        errorMessage = e.localizedMessage ?: "Failed to sync cloud overrides.",
                    )
                }
            }
        }
    }

    fun saveManualOverride(
        overrideDoc: IpoOverrideDocument,
        onSaved: () -> Unit = {},
    ) {
        val cleanDoc = overrideDoc.stripSubscriptionOverrides()
        val matchedBaseLive = uiState.value.rows
            .firstOrNull { it.id == cleanDoc.id || it.id == cleanDoc.targetIpoId }
            ?.baseLiveIpo
        val editedFields = cleanDoc.overriddenFieldCount(matchedBaseLive)

        viewModelScope.launch {
            transientStateFlow.update { it.copy(isSaving = true, errorMessage = null) }
            val result = adminOverrideRepository.saveOverride(cleanDoc)
            result.onSuccess {
                val message = when {
                    cleanDoc.customIpo ->
                        "Published custom IPO: ${cleanDoc.companyName} • Subscription stays live"
                    editedFields > 0 ->
                        "Saved $editedFields manual ${if (editedFields == 1) "edit" else "edits"} for ${cleanDoc.companyName} • Subscription & rest stay live"
                    else ->
                        "Updated settings for ${cleanDoc.companyName} • Subscription & fields stay live"
                }
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        snackbarMessage = message,
                    )
                }
                onSaved()
            }.onFailure { err ->
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = err.localizedMessage ?: "Failed to publish IPO override.",
                    )
                }
            }
        }
    }

    fun toggleHideIpo(row: AdminIpoRowItem) {
        val nextHidden = !row.isHidden
        viewModelScope.launch {
            transientStateFlow.update { it.copy(isSaving = true, errorMessage = null) }
            val result = adminOverrideRepository.toggleHideIpo(
                ipo = row.effectiveIpo,
                hide = nextHidden,
                existingOverride = row.overrideDoc,
            )
            result.onSuccess {
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        snackbarMessage = if (nextHidden) {
                            "Hidden ${row.effectiveIpo.companyName} from public list"
                        } else {
                            "Restored ${row.effectiveIpo.companyName} to public list"
                        },
                    )
                }
            }.onFailure { err ->
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = err.localizedMessage ?: "Unable to update visibility.",
                    )
                }
            }
        }
    }

    fun resetToLiveSync(
        overrideId: String,
        companyName: String,
        onReset: () -> Unit = {},
    ) {
        viewModelScope.launch {
            transientStateFlow.update { it.copy(isSaving = true, errorMessage = null) }
            val result = adminOverrideRepository.resetOverrideToLiveSync(overrideId)
            result.onSuccess {
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        snackbarMessage = "Reset $companyName back to automatic live sync",
                    )
                }
                onReset()
            }.onFailure { err ->
                transientStateFlow.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = err.localizedMessage ?: "Unable to reset override.",
                    )
                }
            }
        }
    }

    suspend fun fetchAdminNote(overrideId: String): String? {
        if (!isAuthorizedAdmin || currentUserId.isBlank()) return null
        return adminOverrideRepository.fetchAdminNote(overrideId)
    }

    fun clearFeedback() {
        transientStateFlow.update { it.copy(errorMessage = null, snackbarMessage = null) }
    }

    companion object {
        internal fun buildAdminRows(
            rawCatalog: List<IpoEntity>,
            overrides: List<IpoOverrideDocument>,
        ): List<AdminIpoRowItem> {
            val byTargetId = overrides.associateBy { it.targetIpoId.ifBlank { it.id } }
            val byNormKey = overrides
                .filter { it.normalizedKey.isNotBlank() }
                .associateBy { it.normalizedKey }

            val consumedOverrideKeys = HashSet<String>()
            val rows = mutableListOf<AdminIpoRowItem>()

            for (base in rawCatalog) {
                val normKey = IpoOverrideMerger.normalizeCompanyKey(base.companyName)
                val matched = byTargetId[base.id] ?: byNormKey[normKey]
                if (matched != null) {
                    val overrideKey = matched.targetIpoId.ifBlank { matched.id }
                    consumedOverrideKeys.add(overrideKey)
                    rows.add(
                        AdminIpoRowItem(
                            id = overrideKey,
                            effectiveIpo = IpoOverrideMerger.overlayEntity(base, matched),
                            baseLiveIpo = base,
                            overrideDoc = matched,
                        ),
                    )
                } else {
                    rows.add(
                        AdminIpoRowItem(
                            id = base.id,
                            effectiveIpo = base,
                            baseLiveIpo = base,
                            overrideDoc = null,
                        ),
                    )
                }
            }

            // Include custom IPOs or overrides whose live feed entry is absent
            overrides.forEachIndexed { idx, doc ->
                val key = doc.targetIpoId.ifBlank { doc.id }
                if (key !in consumedOverrideKeys) {
                    rows.add(
                        0,
                        AdminIpoRowItem(
                            id = key,
                            effectiveIpo = IpoOverrideMerger.buildEntityFromOverride(doc, sortOrder = -500 + idx),
                            baseLiveIpo = null,
                            overrideDoc = doc,
                        ),
                    )
                }
            }

            return rows.sortedWith(
                compareByDescending<AdminIpoRowItem> { it.isOverridden }
                    .thenBy { it.isHidden }
                    .thenBy { it.effectiveIpo.sortOrder }
                    .thenBy { it.effectiveIpo.companyName.lowercase() },
            )
        }
    }

    class Factory(
        private val ipoRepository: IpoRepository,
        private val adminOverrideRepository: AdminOverrideRepository,
        private val currentUserId: String,
        private val isAuthorizedAdmin: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AdminDashboardViewModel(
                ipoRepository = ipoRepository,
                adminOverrideRepository = adminOverrideRepository,
                currentUserId = currentUserId,
                isAuthorizedAdmin = isAuthorizedAdmin,
            ) as T
        }
    }
}
