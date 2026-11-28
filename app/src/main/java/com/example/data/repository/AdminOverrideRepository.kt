package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.firebase.AdminAuthManager
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.local.AdminOverrideDao
import com.example.data.model.AdminProfileDocument
import com.example.data.model.CachedAdminOverrideEntity
import com.example.data.model.IpoEntity
import com.example.data.model.IpoOverrideDocument
import com.example.data.model.IpoOverrideMerger
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class AdminOverrideRepository(
    private val db: FirebaseFirestore?,
    private val overrideDao: AdminOverrideDao,
    private val auth: FirebaseAuth? = runCatching { Firebase.auth }.getOrNull(),
) {
    // CRITICAL: Always resolve named database via R.string.firestore_database_id (never default)
    constructor(context: Context, overrideDao: AdminOverrideDao) : this(
        db = resolveNamedFirestore(context),
        overrideDao = overrideDao,
    )

    private fun requireAuthorizedAdminUid(): String {
        val user = auth?.currentUser
            ?: throw IllegalStateException("Please sign in with Google to use the Admin Dashboard.")
        if (!AdminAuthManager.isAuthorizedAdminEmail(user.email, user.isEmailVerified)) {
            throw SecurityException("Only the authorized owner account (${AdminAuthManager.AUTHORIZED_ADMIN_EMAIL}) can publish IPO overrides.")
        }
        return user.uid
    }

    /**
     * Fast local Room stream of cached admin overrides (keeps UI instant & offline-safe).
     */
    fun observeCachedOverrides(): Flow<List<IpoOverrideDocument>> =
        overrideDao.observeAllOverrides().map { rows -> rows.map { it.toDocument() } }

    suspend fun getCachedOverridesSnapshot(): List<IpoOverrideDocument> =
        overrideDao.getAllSnapshot().map { it.toDocument() }

    internal suspend fun applyCloudSnapshotToCache(
        isFromCache: Boolean,
        docs: List<IpoOverrideDocument>,
    ): List<IpoOverrideDocument> {
        if (isFromCache && docs.isEmpty()) {
            return getCachedOverridesSnapshot()
        }
        overrideDao.replaceAll(docs.map { CachedAdminOverrideEntity.fromDocument(it) })
        return docs
    }

    /**
     * Real-time Firestore listener for published public IPO overrides (`published == true`).
     * Mirrors `allow list: if resource.data.published == true || isAdmin();` in firestore.rules
     * and caches every snapshot in Room atomically so all devices immediately receive manual corrections
     * without wiping local cache on empty offline-cache emissions.
     */
    fun observePublishedCloudOverrides(): Flow<List<IpoOverrideDocument>> {
        val firestore = db ?: return emptyFlow()
        val path = COLLECTION_IPO_OVERRIDES
        return firestore.collection(path)
            .whereEqualTo("published", true)
            .snapshots()
            .map { snapshot ->
                val docs = snapshot.documents.mapNotNull { IpoOverrideDocument.fromDocumentSnapshot(it) }
                applyCloudSnapshotToCache(
                    isFromCache = snapshot.metadata.isFromCache,
                    docs = docs,
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    /**
     * One-shot fetch of published overrides used during manual/background live sync.
     */
    suspend fun fetchPublishedCloudOverridesOnce(): List<IpoOverrideDocument> {
        val firestore = db ?: return getCachedOverridesSnapshot()
        val path = COLLECTION_IPO_OVERRIDES
        return try {
            val snapshot = firestore.collection(path)
                .whereEqualTo("published", true)
                .get()
                .await()
            val docs = snapshot.documents.mapNotNull { IpoOverrideDocument.fromDocumentSnapshot(it) }
            applyCloudSnapshotToCache(
                isFromCache = snapshot.metadata.isFromCache,
                docs = docs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            getCachedOverridesSnapshot()
        }
    }

    /**
     * Syncs the private `/admin_profiles/{uid}` document for the verified Admin operator.
     */
    suspend fun syncAdminProfile(): Result<AdminProfileDocument> {
        val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not initialized."))
        var docPath = COLLECTION_ADMIN_PROFILES
        return try {
            val uid = requireAuthorizedAdminUid()
            val user = auth?.currentUser ?: error("Signed-in admin session missing.")
            val email = user.email.orEmpty().trim()
            val displayName = (user.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore("@"))
                .take(100)
                .ifBlank { "Admin" }
            val photoUrl = user.photoUrl?.toString()?.take(500)

            val docRef = firestore.collection(COLLECTION_ADMIN_PROFILES).document(uid)
            docPath = docRef.path
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updatePayload = mutableMapOf<String, Any>(
                    "displayName" to displayName,
                    "lastActiveAt" to FieldValue.serverTimestamp(),
                )
                if (!photoUrl.isNullOrBlank()) {
                    updatePayload["photoUrl"] = photoUrl
                }
                docRef.update(updatePayload).await()
            } else {
                val createPayload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to displayName,
                    "email" to email,
                    "role" to "admin",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "lastActiveAt" to FieldValue.serverTimestamp(),
                )
                if (!photoUrl.isNullOrBlank()) {
                    createPayload["photoUrl"] = photoUrl
                }
                docRef.set(createPayload).await()
            }

            val fresh = docRef.get().await()
                .toObject(AdminProfileDocument::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: AdminProfileDocument(
                    id = uid,
                    userId = uid,
                    displayName = displayName,
                    email = email,
                    photoUrl = photoUrl,
                    role = "admin",
                )
            Result.success(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Validates inputs against `firestore.rules` bounds, then publishes a manual override
     * or brand-new custom IPO to `/ipo_overrides/{overrideId}`, writes/deletes any private
     * admin note in `/ipo_admin_notes/{overrideId}`, and caches the public override locally.
     */
    suspend fun saveOverride(
        override: IpoOverrideDocument,
        syncAdminNote: Boolean = true,
    ): Result<Unit> {
        val stripped = override.stripSubscriptionOverrides()
        val validationError = validateOverrideAgainstRules(stripped)
        if (validationError != null) {
            return Result.failure(IllegalArgumentException(validationError))
        }

        val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not initialized."))
        val safeDocId = IpoOverrideMerger.sanitizeDocumentId(stripped.id.ifBlank { stripped.targetIpoId })
        val normalized = stripped.copy(
            id = safeDocId,
            targetIpoId = safeDocId,
            normalizedKey = stripped.normalizedKey.ifBlank {
                IpoOverrideMerger.normalizeCompanyKey(stripped.companyName)
            }.take(128),
            companyName = stripped.companyName.trim().take(120),
            published = true,
        )
        val docRef = firestore.collection(COLLECTION_IPO_OVERRIDES).document(safeDocId)
        val noteRef = firestore.collection(COLLECTION_IPO_ADMIN_NOTES).document(safeDocId)
        var currentPath = docRef.path

        return try {
            requireAuthorizedAdminUid()
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(normalized.toUpdateMap()).await()
            } else {
                docRef.set(normalized.toCreateMap()).await()
            }

            if (syncAdminNote) {
                currentPath = noteRef.path
                val trimmedNote = normalized.adminNote?.trim().orEmpty().take(400)
                if (trimmedNote.isNotEmpty()) {
                    val notePayload = mapOf<String, Any>(
                        "targetIpoId" to safeDocId,
                        "adminNote" to trimmedNote,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                    noteRef.set(notePayload).await()
                } else {
                    noteRef.delete().await()
                }
            }

            overrideDao.upsert(CachedAdminOverrideEntity.fromDocument(normalized))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, currentPath)
            Result.failure(e)
        }
    }

    /**
     * Fetches the private admin-only note from `/ipo_admin_notes/{overrideId}`.
     * Intended exclusively for the admin editor sheet (`AdminIpoEditorSheet`).
     */
    suspend fun fetchAdminNote(overrideId: String): String? {
        val firestore = db ?: return null
        val safeDocId = IpoOverrideMerger.sanitizeDocumentId(overrideId)
        if (safeDocId.isBlank()) return null
        val noteRef = firestore.collection(COLLECTION_IPO_ADMIN_NOTES).document(safeDocId)
        return try {
            requireAuthorizedAdminUid()
            val snapshot = noteRef.get().await()
            if (!snapshot.exists()) {
                null
            } else {
                snapshot.getString("adminNote")?.trim()?.take(400)?.takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, noteRef.path)
            null
        }
    }

    /**
     * Hides or unhides an IPO across all users via `/ipo_overrides/{overrideId}` without locking other live fields
     * or clearing any existing private admin note in `/ipo_admin_notes/{overrideId}`.
     */
    suspend fun toggleHideIpo(
        ipo: IpoEntity,
        hide: Boolean,
        existingOverride: IpoOverrideDocument?,
    ): Result<Unit> {
        val safeId = IpoOverrideMerger.sanitizeDocumentId(existingOverride?.id?.ifBlank { ipo.id } ?: ipo.id)
        val base = existingOverride?.stripSubscriptionOverrides() ?: IpoOverrideDocument(
            id = safeId,
            targetIpoId = safeId,
            normalizedKey = IpoOverrideMerger.normalizeCompanyKey(ipo.companyName),
            companyName = ipo.companyName,
            published = true,
            customIpo = false,
            hidden = hide,
        )
        return saveOverride(
            override = base.copy(hidden = hide, published = true),
            syncAdminNote = false,
        )
    }

    /**
     * Deletes the manual override and its private admin note from Firestore and local cache so automatic live feeds
     * seamlessly resume control for this IPO.
     */
    suspend fun resetOverrideToLiveSync(overrideId: String): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not initialized."))
        val safeDocId = IpoOverrideMerger.sanitizeDocumentId(overrideId)
        val docRef = firestore.collection(COLLECTION_IPO_OVERRIDES).document(safeDocId)
        val noteRef = firestore.collection(COLLECTION_IPO_ADMIN_NOTES).document(safeDocId)
        var currentPath = docRef.path

        return try {
            requireAuthorizedAdminUid()
            require(safeDocId.isNotBlank()) { "Override ID must not be blank." }
            docRef.delete().await()
            currentPath = noteRef.path
            noteRef.delete().await()
            overrideDao.deleteById(safeDocId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, currentPath)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "AdminOverrideRepo"
        const val COLLECTION_IPO_OVERRIDES = "ipo_overrides"
        const val COLLECTION_IPO_ADMIN_NOTES = "ipo_admin_notes"
        const val COLLECTION_ADMIN_PROFILES = "admin_profiles"
        private val VALID_ID_REGEX = Regex("^[a-zA-Z0-9_\\-]+$")
        private val VALID_URL_REGEX = Regex("^https?://.*")

        private fun resolveNamedFirestore(context: Context): FirebaseFirestore? {
            return runCatching {
                val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
                FirebaseFirestore.getInstance(databaseId)
            }.onFailure { error ->
                Log.w(TAG, "Firestore named database unavailable: ${error.message}")
            }.getOrNull()
        }

        fun validateOverrideAgainstRules(override: IpoOverrideDocument): String? {
            val name = override.companyName.trim()
            if (name.length < 2 || name.length > 120) {
                return "Company name must be between 2 and 120 characters."
            }
            val safeId = IpoOverrideMerger.sanitizeDocumentId(override.id.ifBlank { override.targetIpoId })
            if (safeId.isBlank() || safeId.length > 128 || !VALID_ID_REGEX.matches(safeId)) {
                return "IPO identifier must contain only letters, digits, hyphens, or underscores."
            }
            val low = override.priceLow ?: 0
            val high = override.priceHigh ?: 0
            if (low < 0 || low > 1_000_000 || high < 0 || high > 1_000_000) {
                return "Price band must be between ₹0 and ₹10,00,000."
            }
            if (high > 0 && high < low) {
                return "Upper price band (₹$high) cannot be lower than lower price band (₹$low)."
            }
            val lot = override.lotSize ?: 0
            if (lot < 0 || lot > 1_000_000) {
                return "Lot size must be between 0 and 10,00,000 shares."
            }
            val issueCr = override.issueSizeCr ?: 0.0
            if (issueCr < 0.0 || issueCr > 10_000_000.0) {
                return "Issue size must be 0 Cr or greater."
            }
            val gmp = override.gmpRupees ?: 0
            if (gmp < -100_000 || gmp > 1_000_000) {
                return "GMP must be within -₹1,00,000 to ₹10,00,000."
            }
            val regUrl = override.registrarUrl?.trim().orEmpty()
            if (regUrl.isNotEmpty() && (regUrl.length > 300 || !VALID_URL_REGEX.matches(regUrl))) {
                return "Registrar URL must start with https:// or http:// (max 300 chars)."
            }
            if ((override.overview?.length ?: 0) > 2000) {
                return "Overview cannot exceed 2000 characters."
            }
            if ((override.adminNote?.length ?: 0) > 400) {
                return "Admin note cannot exceed 400 characters."
            }
            return null
        }
    }
}
