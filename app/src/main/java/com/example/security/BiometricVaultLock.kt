package com.example.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricCapabilityStatus(val statusMessage: String) {
    AVAILABLE("Fingerprint, Face Unlock, or Device PIN ready"),
    NONE_ENROLLED("Set up a screen lock or fingerprint in Android Settings to enable vault lock"),
    UNAVAILABLE("Device authentication is unavailable on this device"),
}

/**
 * Native AndroidX BiometricPrompt helper supporting Biometric (Fingerprint/Face) AND Device PIN/Pattern/Password.
 * Strictly on-device — zero credentials or biometric templates ever leave the Android OS keystore.
 */
object BiometricVaultLock {
    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun checkCapability(context: Context): BiometricCapabilityStatus {
        return runCatching {
            val manager = BiometricManager.from(context)
            when (manager.canAuthenticate(ALLOWED_AUTHENTICATORS)) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricCapabilityStatus.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricCapabilityStatus.NONE_ENROLLED
                else -> BiometricCapabilityStatus.UNAVAILABLE
            }
        }.getOrDefault(BiometricCapabilityStatus.UNAVAILABLE)
    }

    fun canAuthenticate(context: Context): Boolean =
        checkCapability(context) == BiometricCapabilityStatus.AVAILABLE

    fun authenticate(
        context: Context,
        title: String = "Unlock Private Allotment Vault",
        subtitle: String = "Verify with Fingerprint, Face, or Device PIN to view PAN profiles & P&L",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            onError("Unable to open device lock prompt in this window.")
            return
        }

        val capability = checkCapability(context)
        if (capability != BiometricCapabilityStatus.AVAILABLE) {
            onError(capability.statusMessage)
            return
        }

        runCatching {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    onError("Not recognized. Try again or use Device PIN.")
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()
            prompt.authenticate(promptInfo)
        }.onFailure { err ->
            onError(err.message ?: "Authentication prompt unavailable.")
        }
    }

    private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
}
