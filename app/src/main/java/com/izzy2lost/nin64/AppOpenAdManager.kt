package com.izzy2lost.nin64

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError

object AppOpenAdManager {
    private const val TAG = "Nin64AppOpenAd"
    private const val AD_MAX_AGE_MS = 4L * 60L * 60L * 1000L

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var loadTimeMs = 0L

    fun loadAd(context: Context) {
        if (!AdsController.canRequestAds(context) || isLoadingAd || isAdAvailable()) {
            return
        }

        isLoadingAd = true
        val appContext = context.applicationContext
        AdsController.initialize(appContext) {
            AppOpenAd.load(
                AdRequest.Builder(AdsController.APP_OPEN_AD_UNIT_ID).build(),
                object : AdLoadCallback<AppOpenAd> {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        if (!AdsController.areAdsEnabled(appContext)) {
                            ad.destroy()
                            isLoadingAd = false
                            return
                        }

                        appOpenAd = ad
                        loadTimeMs = SystemClock.elapsedRealtime()
                        isLoadingAd = false
                        Log.d(TAG, "App open ad loaded.")
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        isLoadingAd = false
                        Log.w(TAG, "App open ad failed to load: $adError")
                    }
                },
            )
        }
    }

    fun showAdIfAvailable(activity: Activity, onShowAdComplete: () -> Unit = {}) {
        if (!AdsController.areAdsEnabled(activity)) {
            onShowAdComplete()
            return
        }

        if (isShowingAd) {
            onShowAdComplete()
            return
        }

        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            onShowAdComplete()
            loadAd(activity)
            return
        }

        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "App open ad shown.")
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "App open ad dismissed.")
                appOpenAd = null
                isShowingAd = false
                onShowAdComplete()
                loadAd(activity)
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                Log.w(TAG, "App open ad failed to show: $fullScreenContentError")
                appOpenAd = null
                isShowingAd = false
                onShowAdComplete()
                loadAd(activity)
            }
        }

        isShowingAd = true
        ad.show(activity)
    }

    fun isShowingAd(): Boolean = isShowingAd

    fun clearAd() {
        appOpenAd?.destroy()
        appOpenAd = null
        isLoadingAd = false
        loadTimeMs = 0L
    }

    private fun isAdAvailable(): Boolean {
        val loadedAd = appOpenAd ?: return false
        val isFresh = SystemClock.elapsedRealtime() - loadTimeMs < AD_MAX_AGE_MS
        if (!isFresh) {
            loadedAd.destroy()
            appOpenAd = null
        }
        return isFresh
    }
}
