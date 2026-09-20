package com.example

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Manages Google AdMob initialization and Rewarded Video Ads lifecycle.
 */
class AdMobRewardedManager(private val context: Context) {

    companion object {
        private const val TAG = "AdMobRewarded"

        // Official Google AdMob App ID
        const val ADMOB_APP_ID = "ca-app-pub-8410578267301371~7948926096"

        // Real Rewarded Ad Unit ID provided by user
        const val REAL_REWARDED_AD_UNIT_ID = "ca-app-pub-8410578267301371/4181816334"

        // Official Google test ad unit ID for Rewarded Ads (prevents account suspension in debug/development)
        const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var pendingShowAfterLoad = false
    private var pendingActivity: Activity? = null
    private var pendingOnReward: (() -> Unit)? = null
    private var pendingOnFail: (() -> Unit)? = null

    /**
     * Resolves the appropriate Ad Unit ID.
     * Uses Google's test ad unit ID in debug mode to comply with AdMob policies during testing,
     * and the real user Ad Unit ID in release mode.
     */
    fun resolveAdUnitId(requestedId: String? = null): String {
        return if (BuildConfig.DEBUG) {
            TEST_REWARDED_AD_UNIT_ID
        } else {
            if (!requestedId.isNullOrBlank() && requestedId.startsWith("ca-app-pub-")) {
                requestedId
            } else {
                REAL_REWARDED_AD_UNIT_ID
            }
        }
    }

    /**
     * Initializes Google Mobile Ads SDK and preloads the first Rewarded Ad.
     */
    fun initialize(onInitialized: (() -> Unit)? = null) {
        Log.d(TAG, "Initializing Google Mobile Ads SDK with App ID: $ADMOB_APP_ID")
        MobileAds.initialize(context) { initializationStatus ->
            Log.d(TAG, "Google Mobile Ads SDK initialization completed: $initializationStatus")
            loadRewardedAd()
            onInitialized?.invoke()
        }
    }

    fun isAdLoaded(): Boolean = rewardedAd != null

    /**
     * Loads a Rewarded Ad from Google AdMob.
     */
    fun loadRewardedAd(
        adUnitId: String? = null,
        onLoaded: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        if (isLoading) {
            Log.d(TAG, "RewardedAd is currently loading, request ignored.")
            return
        }
        if (rewardedAd != null) {
            Log.d(TAG, "RewardedAd is already loaded and ready.")
            onLoaded?.invoke()
            return
        }

        isLoading = true
        val unitId = resolveAdUnitId(adUnitId)
        val adRequest = AdRequest.Builder().build()

        Log.d(TAG, "Invoking RewardedAd.load with unit ID: $unitId")
        RewardedAd.load(
            context,
            unitId,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    Log.d(TAG, "RewardedAd successfully loaded.")
                    rewardedAd = ad
                    isLoading = false
                    onLoaded?.invoke()

                    if (pendingShowAfterLoad) {
                        pendingShowAfterLoad = false
                        val act = pendingActivity
                        val onRew = pendingOnReward
                        val onFl = pendingOnFail
                        pendingActivity = null
                        pendingOnReward = null
                        pendingOnFail = null
                        if (act != null && onRew != null && onFl != null) {
                            showRewardedAd(act, onRew, onFl)
                        }
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.e(
                        TAG,
                        "RewardedAd failed to load: ${loadAdError.message} (Code: ${loadAdError.code})"
                    )
                    rewardedAd = null
                    isLoading = false
                    onFailed?.invoke(loadAdError.message)

                    if (pendingShowAfterLoad) {
                        pendingShowAfterLoad = false
                        val onFl = pendingOnFail
                        pendingActivity = null
                        pendingOnReward = null
                        pendingOnFail = null
                        onFl?.invoke()
                    }
                }
            }
        )
    }

    /**
     * Shows the Rewarded Ad if available, or attempts an immediate load.
     * Invokes callbacks for closed ad or failure so playback never freezes.
     */
    fun showRewardedAd(
        activity: Activity,
        onRewardedClosed: () -> Unit,
        onAdFailed: () -> Unit
    ) {
        val currentAd = rewardedAd
        if (currentAd == null) {
            Log.w(TAG, "RewardedAd not cached yet. Loading immediately...")
            pendingShowAfterLoad = true
            pendingActivity = activity
            pendingOnReward = onRewardedClosed
            pendingOnFail = onAdFailed
            loadRewardedAd(
                onFailed = {
                    if (pendingShowAfterLoad) {
                        pendingShowAfterLoad = false
                        onAdFailed()
                    }
                }
            )
            return
        }

        currentAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "RewardedAd is showing full-screen content.")
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "RewardedAd was dismissed by user.")
                rewardedAd = null
                // Preload the next ad in the background
                loadRewardedAd()
                // Resume episode playback via JS callback
                onRewardedClosed()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(TAG, "RewardedAd failed to show: ${adError.message}")
                rewardedAd = null
                // Preload next ad
                loadRewardedAd()
                // Do not freeze the app; play the episode
                onAdFailed()
            }
        }

        currentAd.show(activity) { rewardItem ->
            Log.d(TAG, "User earned reward: ${rewardItem.amount} ${rewardItem.type}")
        }
    }
}
