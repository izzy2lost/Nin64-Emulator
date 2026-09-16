package com.izzy2lost.nin64

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig

object AdsController {
    const val ADMOB_APP_ID = "ca-app-pub-9721315985864233~1151488653"
    const val NATIVE_AD_UNIT_ID = "ca-app-pub-9721315985864233/6818748306"
    const val APP_OPEN_AD_UNIT_ID = "ca-app-pub-9721315985864233/8058754347"

    private const val TAG = "Nin64Ads"
    private const val PREFS_NAME = "nin64_ads"
    private const val PREF_REMOVE_ADS = "remove_ads"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pendingCallbacks = mutableListOf<() -> Unit>()
    private var initializationStarted = false
    private var initialized = false

    fun areAdsEnabled(context: Context): Boolean {
        return !context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_REMOVE_ADS, false)
    }

    fun areAdsRemoved(context: Context): Boolean {
        return !areAdsEnabled(context)
    }

    fun setAdsRemoved(context: Context, removed: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_REMOVE_ADS, removed)
            .apply()

        if (removed) {
            AppOpenAdManager.clearAd()
        }
    }

    fun setAdsRemovedForTesting(context: Context, removed: Boolean) {
        setAdsRemoved(context, removed)
    }

    /**
     * Ads may only be requested once the user still has ads enabled and the consent state
     * gathered by [ConsentManager] allows it.
     */
    fun canRequestAds(context: Context): Boolean {
        return areAdsEnabled(context) && ConsentManager.canRequestAds(context)
    }

    fun initialize(context: Context, onInitialized: () -> Unit) {
        if (!areAdsEnabled(context)) {
            return
        }

        if (!ConsentManager.canRequestAds(context)) {
            Log.d(TAG, "Waiting for user consent before initializing the ads SDK.")
            return
        }

        synchronized(lock) {
            if (initialized) {
                mainHandler.post(onInitialized)
                return
            }

            pendingCallbacks += onInitialized
            if (initializationStarted) {
                return
            }
            initializationStarted = true
        }

        val appContext = context.applicationContext
        Thread {
            try {
                MobileAds.initialize(
                    appContext,
                    InitializationConfig.Builder(ADMOB_APP_ID).build(),
                ) {
                    Log.d(TAG, "GMA Next-Gen SDK initialized.")
                    completeInitialization()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GMA Next-Gen SDK initialization failed.", t)
                resetInitializationAfterFailure()
            }
        }.start()
    }

    private fun completeInitialization() {
        val callbacks = synchronized(lock) {
            initialized = true
            pendingCallbacks.toList().also {
                pendingCallbacks.clear()
            }
        }

        mainHandler.post {
            callbacks.forEach { callback -> callback() }
        }
    }

    private fun resetInitializationAfterFailure() {
        synchronized(lock) {
            initializationStarted = false
            pendingCallbacks.clear()
        }
    }
}
