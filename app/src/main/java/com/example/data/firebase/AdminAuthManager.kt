package com.example.data.firebase

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class AdminAuthSession(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val isEmailVerified: Boolean = false,
    val isSignedIn: Boolean = false,
    val isAuthorizedAdmin: Boolean = false,
)

object AdminAuthManager {
    private const val TAG = "Auth"
    const val AUTHORIZED_ADMIN_EMAIL = "developers.pcmedia@gmail.com"

    fun isAuthorizedAdminEmail(email: String?, isEmailVerified: Boolean): Boolean {
        return isEmailVerified &&
            !email.isNullOrBlank() &&
            email.trim().equals(AUTHORIZED_ADMIN_EMAIL, ignoreCase = true)
    }

    fun FirebaseUser?.toAdminSession(): AdminAuthSession {
        if (this == null) return AdminAuthSession()
        val emailVal = email.orEmpty()
        val verified = isEmailVerified
        return AdminAuthSession(
            uid = uid,
            displayName = displayName?.takeIf { it.isNotBlank() }
                ?: emailVal.substringBefore("@").ifBlank { "Investor" },
            email = emailVal,
            photoUrl = photoUrl?.toString().orEmpty(),
            isEmailVerified = verified,
            isSignedIn = true,
            isAuthorizedAdmin = isAuthorizedAdminEmail(emailVal, verified),
        )
    }

    fun authStateFlow(): Flow<AdminAuthSession> = callbackFlow {
        val firebaseAuth = runCatching { Firebase.auth }.getOrNull()
        if (firebaseAuth == null) {
            trySend(AdminAuthSession())
            close()
            return@callbackFlow
        }
        trySend(firebaseAuth.currentUser.toAdminSession())
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser.toAdminSession())
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: (AdminAuthSession) -> Unit = {},
        onUnauthenticated: () -> Unit = {},
    ) {
        val firebaseAuth = runCatching { Firebase.auth }.getOrNull() ?: run {
            onUnauthenticated()
            return
        }
        val existingUser = firebaseAuth.currentUser
        if (existingUser != null) {
            onAuthSuccess(existingUser.toAdminSession())
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }
        if (clientId.isBlank()) {
            onUnauthenticated()
            return
        }

        // Single credential option per GetCredentialRequest for API 36 sheet responsiveness
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val activity = context.findActivity() ?: run {
                    onUnauthenticated()
                    return@launch
                }
                val cred = credentialManager.getCredential(activity, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val result = firebaseAuth
                        .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .await()
                    onAuthSuccess(result.user.toAdminSession())
                } else {
                    onUnauthenticated()
                }
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: (AdminAuthSession) -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: () -> Unit = {},
    ) {
        val firebaseAuth = runCatching { Firebase.auth }.getOrNull() ?: run {
            onAuthError("Firebase Authentication is not initialized yet.")
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found.")
            return
        }
        val activity = context.findActivity() ?: run {
            onAuthError("Unable to launch Google Sign-In from current screen.")
            return
        }

        // Interactive sign-in uses GetSignInWithGoogleOption exclusively (one option per request)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(activity, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val result = firebaseAuth
                        .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .await()
                    onAuthSuccess(result.user.toAdminSession())
                } else {
                    onAuthError("Unexpected credential type returned by Google Sign-In.")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Google Sign-In failed. Please try again.")
            }
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onBeforeSignOut: suspend () -> Unit = {},
        onSignOutComplete: () -> Unit = {},
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    onBeforeSignOut()
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-sign-out cleanup warning: ${e.message}")
                }
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    runCatching { Firebase.auth.signOut() }
                    onSignOutComplete()
                }
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
