package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.InvestorCategory
import com.example.data.model.UserProfileDocument
import com.example.data.preferences.CloudSyncedPrefsSnapshot
import com.example.data.preferences.UserPreferencesRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
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

class UserProfileRepository(
    private val db: FirebaseFirestore?,
    private val auth: FirebaseAuth? = runCatching { Firebase.auth }.getOrNull(),
) {
    // CRITICAL: Always resolve named database via R.string.firestore_database_id (never default)
    constructor(context: Context) : this(
        db = resolveNamedFirestore(context),
    )

    private fun requireSignedInUser(): Pair<String, String> {
        val user = auth?.currentUser
            ?: throw IllegalStateException("Please sign in with Google to save your cloud profile.")
        val uid = user.uid
        val email = user.email.orEmpty().trim()
        require(uid.isNotBlank() && email.isNotBlank()) { "Authenticated Google account is missing email." }
        return uid to email
    }

    /**
     * Real-time listener strictly scoped to the authenticated user's own `/user_profiles/{uid}` document.
     */
    fun observeUserProfile(userId: String): Flow<UserProfileDocument?> {
        val firestore = db ?: return emptyFlow()
        if (userId.isBlank()) return emptyFlow()
        val docRef = firestore.collection(COLLECTION_USER_PROFILES).document(userId)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) UserProfileDocument.fromDocumentSnapshot(snapshot) else null
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
                }
                throw error
            }
    }

    /**
     * Ensures `/user_profiles/{uid}` exists on sign-in and performs bidirectional hydration:
     * - If no cloud profile exists yet, initializes it from the user's current local bookmarks & preferences.
     * - If a cloud profile exists, merges any guest bookmarks into the cloud set and hydrates local preferences.
     */
    suspend fun ensureAndHydrateOnSignIn(
        preferencesRepository: UserPreferencesRepository,
    ): Result<UserProfileDocument> {
        val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not initialized."))
        var docPath = COLLECTION_USER_PROFILES
        return try {
            val (uid, email) = requireSignedInUser()
            val user = auth?.currentUser ?: error("Signed-in user missing.")
            val defaultName = (user.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore("@"))
                .trim()
                .take(80)
                .ifBlank { "Investor" }
            val photoUrl = user.photoUrl?.toString()?.take(500)

            val docRef = firestore.collection(COLLECTION_USER_PROFILES).document(uid)
            docPath = docRef.path

            val localBookmarks = preferencesRepository.getBookmarkedIpoIdsSnapshot()
            val localPrefs = preferencesRepository.getCurrentThemeAndAlertSnapshot()

            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                val initial = UserProfileDocument(
                    id = uid,
                    userId = uid,
                    displayName = defaultName,
                    email = email,
                    photoUrl = photoUrl,
                    investorCategory = InvestorCategory.RETAIL.name,
                    bio = "",
                    bookmarkedIpoIds = localBookmarks.toList().take(200),
                    preferences = localPrefs,
                )
                docRef.set(initial.toCreateMap()).await()
                Result.success(initial)
            } else {
                val cloudDoc = UserProfileDocument.fromDocumentSnapshot(existingSnap)
                    ?: UserProfileDocument(
                        id = uid,
                        userId = uid,
                        displayName = defaultName,
                        email = email,
                        photoUrl = photoUrl,
                        investorCategory = InvestorCategory.RETAIL.name,
                        bookmarkedIpoIds = localBookmarks.toList().take(200),
                        preferences = localPrefs,
                    )

                // Merge guest bookmarks with cloud bookmarks without dropping either
                val mergedBookmarks = (cloudDoc.bookmarkedIpoIds + localBookmarks).distinct().take(200)
                preferencesRepository.setBookmarkedIpoIds(mergedBookmarks.toSet())
                preferencesRepository.applyCloudPreferences(cloudDoc.preferences)

                if (mergedBookmarks != cloudDoc.bookmarkedIpoIds) {
                    docRef.update(
                        mapOf(
                            "bookmarkedIpoIds" to mergedBookmarks,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    ).await()
                }
                Result.success(cloudDoc.copy(bookmarkedIpoIds = mergedBookmarks))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Validates and saves editable investor details (display name, investor category, bio).
     */
    suspend fun saveInvestorDetails(
        displayName: String,
        category: InvestorCategory,
        bio: String,
        currentBookmarks: Set<String>,
        currentPrefs: CloudSyncedPrefsSnapshot,
    ): Result<Unit> {
        val validationError = validateInvestorDetails(displayName = displayName, bio = bio)
        if (validationError != null) {
            return Result.failure(IllegalArgumentException(validationError))
        }
        val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not initialized."))
        var docPath = COLLECTION_USER_PROFILES
        return try {
            val (uid, email) = requireSignedInUser()
            val photoUrl = auth?.currentUser?.photoUrl?.toString()?.take(500)
            val docRef = firestore.collection(COLLECTION_USER_PROFILES).document(uid)
            docPath = docRef.path

            val docModel = UserProfileDocument(
                id = uid,
                userId = uid,
                displayName = displayName.trim().take(80),
                email = email,
                photoUrl = photoUrl,
                investorCategory = category.name,
                bio = bio.trim().take(240),
                bookmarkedIpoIds = currentBookmarks.toList().take(200),
                preferences = currentPrefs,
            )

            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(docModel.toDetailsUpdateMap()).await()
            } else {
                docRef.set(docModel.toCreateMap()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Syncs the user's current bookmarked IPO watchlist to `/user_profiles/{uid}`.
     */
    suspend fun syncBookmarksToCloud(
        bookmarkedIpoIds: Set<String>,
        currentPrefs: CloudSyncedPrefsSnapshot,
    ): Result<Unit> {
        val firestore = db ?: return Result.success(Unit)
        val user = auth?.currentUser ?: return Result.success(Unit)
        val uid = user.uid
        val email = user.email.orEmpty().trim()
        if (uid.isBlank() || email.isBlank()) return Result.success(Unit)

        val docRef = firestore.collection(COLLECTION_USER_PROFILES).document(uid)
        return try {
            val cleaned = bookmarkedIpoIds.filter { it.isNotBlank() }.distinct().take(200)
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "bookmarkedIpoIds" to cleaned,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                ).await()
            } else {
                val initial = UserProfileDocument(
                    id = uid,
                    userId = uid,
                    displayName = (user.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore("@"))
                        .trim().take(80).ifBlank { "Investor" },
                    email = email,
                    photoUrl = user.photoUrl?.toString()?.take(500),
                    investorCategory = InvestorCategory.RETAIL.name,
                    bookmarkedIpoIds = cleaned,
                    preferences = currentPrefs,
                )
                docRef.set(initial.toCreateMap()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Syncs the user's theme & alert preferences map to `/user_profiles/{uid}`.
     */
    suspend fun syncPreferencesToCloud(
        preferences: CloudSyncedPrefsSnapshot,
        currentBookmarks: Set<String>,
    ): Result<Unit> {
        val firestore = db ?: return Result.success(Unit)
        val user = auth?.currentUser ?: return Result.success(Unit)
        val uid = user.uid
        val email = user.email.orEmpty().trim()
        if (uid.isBlank() || email.isBlank()) return Result.success(Unit)

        val docRef = firestore.collection(COLLECTION_USER_PROFILES).document(uid)
        return try {
            val prefsMap = mapOf(
                "themePreference" to preferences.themePreference.name,
                "compactCards" to preferences.compactCards,
                "notifyNewIpo" to preferences.notifyNewIpo,
                "notifyOpenIpo" to preferences.notifyOpenIpo,
                "notifyAllotment" to preferences.notifyAllotment,
                "notifyNewsAlerts" to preferences.notifyNewsAlerts,
            )
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "preferences" to prefsMap,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                ).await()
            } else {
                val initial = UserProfileDocument(
                    id = uid,
                    userId = uid,
                    displayName = (user.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore("@"))
                        .trim().take(80).ifBlank { "Investor" },
                    email = email,
                    photoUrl = user.photoUrl?.toString()?.take(500),
                    investorCategory = InvestorCategory.RETAIL.name,
                    bookmarkedIpoIds = currentBookmarks.toList().take(200),
                    preferences = preferences,
                )
                docRef.set(initial.toCreateMap()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Permanently deletes the signed-in user's `/user_profiles/{uid}` cloud document and
     * attempts to delete their Firebase Authentication account (required by Google Play
     * Account Deletion policy). Local PAN profiles on-device are never touched.
     */
    suspend fun deleteCloudProfileAndAccount(): Result<Boolean> {
        val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not initialized."))
        val user = auth?.currentUser ?: return Result.failure(IllegalStateException("Not signed in."))
        val uid = user.uid
        if (uid.isBlank()) return Result.failure(IllegalStateException("Not signed in."))

        val docRef = firestore.collection(COLLECTION_USER_PROFILES).document(uid)
        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.delete().await()
            }
            val authAccountDeleted = runCatching {
                user.delete().await()
                true
            }.getOrElse { error ->
                Log.w(TAG, "Cloud profile deleted; Firebase Auth delete requires recent sign-in: ${error.message}")
                false
            }
            Result.success(authAccountDeleted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "UserProfileRepo"
        const val COLLECTION_USER_PROFILES = "user_profiles"

        private fun resolveNamedFirestore(context: Context): FirebaseFirestore? {
            return runCatching {
                val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
                FirebaseFirestore.getInstance(databaseId)
            }.onFailure { error ->
                Log.w(TAG, "Firestore named database unavailable: ${error.message}")
            }.getOrNull()
        }

        fun validateInvestorDetails(displayName: String, bio: String): String? {
            val trimmedName = displayName.trim()
            if (trimmedName.isEmpty() || trimmedName.length > 80) {
                return "Display name must be between 1 and 80 characters."
            }
            if (bio.trim().length > 240) {
                return "Investor note cannot exceed 240 characters."
            }
            return null
        }
    }
}
