package com.izzy2lost.nin64

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

class NativeAdPlacement(
    private val activity: AppCompatActivity,
) {
    private var nativeAd: NativeAd? = null
    private var boundNativeAdView: NativeAdView? = null
    private var loadRequested = false

    fun hasAd(): Boolean {
        return AdsController.areAdsEnabled(activity) && nativeAd != null
    }

    fun load(
        onLoaded: () -> Unit = {},
        onFailed: () -> Unit = {},
    ) {
        if (!AdsController.canRequestAds(activity) || loadRequested || activity.isFinishing || activity.isDestroyed) {
            return
        }

        loadRequested = true
        AdsController.initialize(activity) {
            loadNativeAd(onLoaded, onFailed)
        }
    }

    fun loadInto(container: FrameLayout) {
        if (!AdsController.areAdsEnabled(activity)) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.GONE
        load(
            onLoaded = {
                bind(container)
                container.visibility = View.VISIBLE
            },
            onFailed = {
                container.visibility = View.GONE
            },
        )
    }

    fun bind(container: FrameLayout) {
        val loadedNativeAd = nativeAd ?: return
        (boundNativeAdView?.parent as? ViewGroup)?.removeView(boundNativeAdView)
        boundNativeAdView?.destroy()

        val adView = activity.layoutInflater.inflate(
            R.layout.view_native_banner_ad,
            container,
            false,
        ) as NativeAdView
        populateNativeAdView(loadedNativeAd, adView)

        container.removeAllViews()
        container.addView(adView)
        boundNativeAdView = adView
    }

    fun destroy() {
        boundNativeAdView?.destroy()
        boundNativeAdView = null
        nativeAd?.destroy()
        nativeAd = null
    }

    private fun loadNativeAd(
        onLoaded: () -> Unit,
        onFailed: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        val adRequest = NativeAdRequest
            .Builder(
                AdsController.NATIVE_AD_UNIT_ID,
                listOf(NativeAd.NativeAdType.NATIVE),
            )
            .setMediaAspectRatio(NativeAd.NativeMediaAspectRatio.LANDSCAPE)
            .build()

        NativeAdLoader.load(
            adRequest,
            object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed || !AdsController.areAdsEnabled(activity)) {
                            nativeAd.destroy()
                            return@runOnUiThread
                        }

                        this@NativeAdPlacement.nativeAd?.destroy()
                        this@NativeAdPlacement.nativeAd = nativeAd
                        onLoaded()
                        Log.d(TAG, "Native ad loaded.")
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.w(TAG, "Native ad failed to load: $adError")
                    loadRequested = false
                    activity.runOnUiThread {
                        onFailed()
                    }
                }
            },
        )
    }

    private fun populateNativeAdView(loadedNativeAd: NativeAd, adView: NativeAdView) {
        val mediaView = adView.findViewById<MediaView>(R.id.adMedia)
        val iconView = adView.findViewById<ImageView>(R.id.adAppIcon)
        val headlineView = adView.findViewById<TextView>(R.id.adHeadline)
        val bodyView = adView.findViewById<TextView>(R.id.adBody)
        val advertiserView = adView.findViewById<TextView>(R.id.adAdvertiser)
        val callToActionView = adView.findViewById<TextView>(R.id.adCallToAction)

        adView.headlineView = headlineView
        adView.bodyView = bodyView
        adView.advertiserView = advertiserView
        adView.callToActionView = callToActionView
        adView.iconView = iconView

        mediaView.imageScaleType = ImageView.ScaleType.CENTER_CROP
        headlineView.text = loadedNativeAd.headline
        bodyView.text = loadedNativeAd.body
        advertiserView.text = loadedNativeAd.advertiser
        callToActionView.text = loadedNativeAd.callToAction
        iconView.setImageDrawable(loadedNativeAd.icon?.drawable)

        bodyView.visibility = assetVisibility(loadedNativeAd.body)
        advertiserView.visibility = assetVisibility(loadedNativeAd.advertiser)
        callToActionView.visibility = assetVisibility(loadedNativeAd.callToAction)
        iconView.visibility = assetVisibility(loadedNativeAd.icon)

        adView.registerNativeAd(loadedNativeAd, mediaView)
    }

    private fun assetVisibility(asset: Any?): Int {
        return if (asset == null) View.GONE else View.VISIBLE
    }

    companion object {
        private const val TAG = "Nin64Ads"
    }
}
