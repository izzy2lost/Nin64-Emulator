package com.izzy2lost.nin64

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Wraps the Google User Messaging Platform SDK. AdMob requires a certified consent platform
 * before ads may be requested for users in the EEA, the UK and regulated US states, so no ad
 * request is made until [canRequestAds] returns true.
 */
object ConsentManager {
    private const val TAG = "Nin64Consent"

    /**
     * Hashed device IDs used to force the consent form to appear while testing. Copy the id that
     * the UMP SDK prints to logcat on a debug build ("Use new ConsentDebugSettings.Builder()...")
     * and add it here; it only has an effect on debuggable builds.
     */
    private val TEST_DEVICE_HASHED_IDS = emptyList<String>()

    private val lock = Any()
    private var consentInformation: ConsentInformation? = null
    private var gatheringInProgress = false

    /** True once UMP allows ad requests; this is already true in regions without a form. */
    fun canRequestAds(context: Context): Boolean {
        return try {
            consentInformation(context).canRequestAds()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the consent state.", t)
            false
        }
    }

    /** True when the user must be able to reopen the consent form from the app's settings. */
    fun isPrivacyOptionsRequired(context: Context): Boolean {
        return try {
            consentInformation(context).privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the privacy options requirement.", t)
            false
        }
    }

    /**
     * Refreshes the consent state and shows the consent form when one is required, reporting
     * whether ads may be requested afterwards. Safe to call on every launch; the form is only
     * shown when UMP asks for it.
     */
    fun gatherConsent(activity: Activity, onConsentResolved: (canRequestAds: Boolean) -> Unit = {}) {
        if (AdsController.areAdsRemoved(activity)) {
            onConsentResolved(false)
            return
        }

        synchronized(lock) {
            if (gatheringInProgress) {
                return
            }
            gatheringInProgress = true
        }

        val parameters = ConsentRequestParameters.Builder()
            .setAdMobAppId(AdsController.ADMOB_APP_ID)
            .setTagForUnderAgeOfConsent(false)
            .apply { debugSettings(activity)?.let { setConsentDebugSettings(it) } }
            .build()

        try {
            consentInformation(activity).requestConsentInfoUpdate(
                activity,
                parameters,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        if (formError != null) {
                            Log.w(TAG, "Consent form dismissed with an error: ${formError.describe()}")
                        }
                        finishGathering(activity, onConsentResolved)
                    }
                },
                { requestError ->
                    Log.w(TAG, "Consent info update failed: ${requestError.describe()}")
                    finishGathering(activity, onConsentResolved)
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Consent info update could not be started.", t)
            finishGathering(activity, onConsentResolved)
        }
    }

    /** Reopens the consent form so the user can change the choices they already made. */
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: (FormError?) -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "Privacy options form failed: ${formError.describe()}")
            }
            onDismissed(formError)
        }
    }

    private fun consentInformation(context: Context): ConsentInformation {
        synchronized(lock) {
            return consentInformation
                ?: UserMessagingPlatform.getConsentInformation(context.applicationContext)
                    .also { consentInformation = it }
        }
    }

    private fun finishGathering(context: Context, onConsentResolved: (Boolean) -> Unit) {
        synchronized(lock) {
            gatheringInProgress = false
        }
        onConsentResolved(canRequestAds(context))
    }

    private fun debugSettings(context: Context): ConsentDebugSettings? {
        if (TEST_DEVICE_HASHED_IDS.isEmpty() || !context.isDebuggable()) {
            return null
        }

        return ConsentDebugSettings.Builder(context)
            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            .apply { TEST_DEVICE_HASHED_IDS.forEach { addTestDeviceHashedId(it) } }
            .build()
    }

    private fun Context.isDebuggable(): Boolean {
        return applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private fun FormError.describe(): String = "[$errorCode] $message"
}
