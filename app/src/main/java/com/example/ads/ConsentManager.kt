package com.example.ads

import android.app.Activity
import android.content.Context
import com.example.BuildConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates Google's User Messaging Platform (UMP) consent flow so MobileAds is only initialized
 * and ads are only requested once [canRequestAds] is true.
 */
object ConsentManager {
    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _isPrivacyOptionsRequired = MutableStateFlow(false)
    val isPrivacyOptionsRequired: StateFlow<Boolean> = _isPrivacyOptionsRequired.asStateFlow()

    /**
     * Runs on MainActivity startup:
     * 1. Immediately reads cached [ConsentInformation.canRequestAds] so returning consented users
     *    can initialize ads without waiting for a network round-trip.
     * 2. Requests updated consent info (using DEBUG_GEOGRAPHY_EEA in debug builds only).
     * 3. Loads and shows the UMP consent form if required, then refreshes state flows and invokes
     *    [onCanRequestAds] whenever ads may be requested.
     */
    fun gatherConsent(
        activity: Activity,
        onCanRequestAds: () -> Unit = {},
    ) {
        val appContext = activity.applicationContext
        val consentInformation = runCatching {
            UserMessagingPlatform.getConsentInformation(appContext)
        }.getOrNull() ?: return

        refreshFromConsentInfo(consentInformation)
        if (_canRequestAds.value) {
            onCanRequestAds()
        }

        val params = buildConsentRequestParameters(activity)

        runCatching {
            consentInformation.requestConsentInfoUpdate(
                activity,
                params,
                {
                    refreshFromConsentInfo(consentInformation)
                    if (activity.isFinishing || activity.isDestroyed) {
                        if (_canRequestAds.value) onCanRequestAds()
                        return@requestConsentInfoUpdate
                    }
                    runCatching {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                            refreshFromConsentInfo(consentInformation)
                            if (_canRequestAds.value) {
                                onCanRequestAds()
                            }
                        }
                    }.onFailure {
                        refreshFromConsentInfo(consentInformation)
                        if (_canRequestAds.value) {
                            onCanRequestAds()
                        }
                    }
                },
                {
                    refreshFromConsentInfo(consentInformation)
                    if (_canRequestAds.value) {
                        onCanRequestAds()
                    }
                },
            )
        }.onFailure {
            refreshFromConsentInfo(consentInformation)
            if (_canRequestAds.value) {
                onCanRequestAds()
            }
        }
    }

    /**
     * Opens the UMP privacy options form so EEA/UK/Switzerland users can review or change their
     * ad consent choices from Settings.
     */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissed: (errorMessage: String?) -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onDismissed(null)
            return
        }
        val appContext = activity.applicationContext
        runCatching {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                refreshConsentState(appContext)
                if (_canRequestAds.value) {
                    AdMobManager.initialize(appContext)
                }
                onDismissed(formError?.message)
            }
        }.onFailure { error ->
            refreshConsentState(appContext)
            onDismissed(error.message)
        }
    }

    fun refreshConsentState(context: Context) {
        runCatching {
            val consentInformation = UserMessagingPlatform.getConsentInformation(context.applicationContext)
            refreshFromConsentInfo(consentInformation)
        }
    }

    private fun refreshFromConsentInfo(consentInformation: ConsentInformation) {
        _canRequestAds.value = consentInformation.canRequestAds()
        _isPrivacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    internal fun buildConsentRequestParameters(
        context: Context,
        isDebug: Boolean = BuildConfig.DEBUG,
    ): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        if (isDebug) {
            val debugSettings = ConsentDebugSettings.Builder(context)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .build()
            builder.setConsentDebugSettings(debugSettings)
        }
        return builder.build()
    }

    internal fun setConsentStateForTest(
        canRequestAds: Boolean,
        isPrivacyOptionsRequired: Boolean = false,
    ) {
        _canRequestAds.value = canRequestAds
        _isPrivacyOptionsRequired.value = isPrivacyOptionsRequired
    }
}
