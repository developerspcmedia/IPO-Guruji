package com.example.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.ui.theme.LocalMarketColors
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class BannerAdPlacement {
    PRIMARY,
    SECONDARY,
}

enum class NativeAdPlacement {
    PRIMARY,
    SECONDARY,
}

object AdMobManager {
    private const val MIN_INTERSTITIAL_INTERVAL_MS = 45_000L
    private const val MAX_INTERSTITIAL_AUTO_RETRIES = 5
    private const val MAX_APP_OPEN_AUTO_RETRIES = 4
    internal const val MAX_BANNER_AUTO_RETRIES = 4
    private const val APP_OPEN_EXPIRY_MS = 4L * 60L * 60L * 1000L // 4 hours per AdMob guidance
    private const val MIN_APP_OPEN_RESUME_INTERVAL_MS = 90_000L

    // Official Google AdMob Android demo/test unit IDs (used strictly in Debug builds to enable
    // safe verification on physical devices without risking invalid-traffic penalties).
    internal const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    internal const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    internal const val TEST_NATIVE_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"
    internal const val TEST_APP_OPEN_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)

    private var interstitialAd: InterstitialAd? = null
    private var isLoadingInterstitial = false
    private var interstitialFailureCount = 0
    private var interstitialRetryJob: Job? = null
    private var lastInterstitialShownOpenCount = 0
    private var lastInterstitialShownTimeMs = 0L

    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoadedAtMs: Long = 0L
    private var isLoadingAppOpen = false
    private var appOpenFailureCount = 0
    private var appOpenRetryJob: Job? = null
    private var lastAppOpenShownTimeMs: Long = 0L
    private var isShowingFullScreenAd = false

    private val _sdkInitialized = MutableStateFlow(false)
    val sdkInitialized: StateFlow<Boolean> = _sdkInitialized.asStateFlow()

    private val _interstitialReady = MutableStateFlow(false)
    val interstitialReady: StateFlow<Boolean> = _interstitialReady.asStateFlow()

    private val _appOpenReady = MutableStateFlow(false)
    val appOpenReady: StateFlow<Boolean> = _appOpenReady.asStateFlow()

    private val _nativeAdLoaded = MutableStateFlow(false)
    val nativeAdLoaded: StateFlow<Boolean> = _nativeAdLoaded.asStateFlow()

    private val _lastAdStatus = MutableStateFlow("Initializing Google Mobile Ads SDK")
    val lastAdStatus: StateFlow<String> = _lastAdStatus.asStateFlow()

    fun initialize(context: Context) {
        if (!ConsentManager.canRequestAds.value) return
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        if (BuildConfig.DEBUG) {
            runCatching {
                val config = RequestConfiguration.Builder()
                    .setTestDeviceIds(listOf(AdRequest.DEVICE_ID_EMULATOR))
                    .build()
                MobileAds.setRequestConfiguration(config)
            }
        }
        scope.launch {
            runCatching {
                MobileAds.initialize(appContext) {
                    _sdkInitialized.value = true
                    _lastAdStatus.value = "AdMob SDK Ready"
                    scope.launch(Dispatchers.Main) {
                        preloadAppOpenAd(appContext)
                        preloadInterstitial(appContext)
                    }
                }
            }.onFailure {
                initialized.set(false)
                _lastAdStatus.value = "AdMob waiting for Play Services"
            }
        }
    }

    internal fun resolveBannerUnitId(
        context: Context,
        isDebug: Boolean = BuildConfig.DEBUG,
        placement: BannerAdPlacement = BannerAdPlacement.PRIMARY,
    ): String = when {
        isDebug -> TEST_BANNER_UNIT_ID
        placement == BannerAdPlacement.SECONDARY -> context.getString(R.string.admob_banner_unit_id_2)
        else -> context.getString(R.string.admob_banner_unit_id)
    }

    internal fun resolveInterstitialUnitId(
        context: Context,
        isDebug: Boolean = BuildConfig.DEBUG,
    ): String = if (isDebug) TEST_INTERSTITIAL_UNIT_ID else context.getString(R.string.admob_interstitial_unit_id)

    internal fun resolveNativeUnitId(
        context: Context,
        isDebug: Boolean = BuildConfig.DEBUG,
        placement: NativeAdPlacement = NativeAdPlacement.PRIMARY,
    ): String = when {
        isDebug -> TEST_NATIVE_UNIT_ID
        placement == NativeAdPlacement.SECONDARY -> context.getString(R.string.admob_native_unit_id_2)
        else -> context.getString(R.string.admob_native_unit_id)
    }

    internal fun resolveAppOpenUnitId(
        context: Context,
        isDebug: Boolean = BuildConfig.DEBUG,
    ): String = if (isDebug) TEST_APP_OPEN_UNIT_ID else context.getString(R.string.admob_app_open_unit_id)

    /**
     * Returns true when running on an Android Emulator (excluding Robolectric JVM test environment).
     * Used in debug builds to render AdMob banners via pure-native NativeAdView instead of Chromium
     * WebView HTML banners, preventing remote Google test-banner SVG viewBox JS console errors.
     */
    internal fun isAndroidEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        if (fingerprint.equals("robolectric", ignoreCase = true)) return false
        val model = Build.MODEL.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val board = Build.BOARD.orEmpty()
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            fingerprint.contains("test-keys", ignoreCase = true) && (
                product.contains("cf_", ignoreCase = true) ||
                    product.contains("sdk", ignoreCase = true) ||
                    device.contains("vsoc", ignoreCase = true)
                ) ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Cuttlefish", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            manufacturer.contains("Genymotion", ignoreCase = true) ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            device.contains("vsoc", ignoreCase = true) ||
            device.contains("emulator", ignoreCase = true) ||
            product.contains("sdk_gphone", ignoreCase = true) ||
            product.contains("sdk_phone", ignoreCase = true) ||
            product.contains("cf_", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true) ||
            product.contains("vbox", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true) ||
            hardware.contains("cutf", ignoreCase = true) ||
            board.contains("goldfish", ignoreCase = true) ||
            board.contains("cutf", ignoreCase = true)
    }

    internal fun computeBannerRetryDelayMs(failureAttempt: Int): Long {
        return when {
            failureAttempt <= 1 -> 4_000L
            failureAttempt == 2 -> 8_000L
            failureAttempt == 3 -> 15_000L
            else -> 30_000L
        }
    }

    internal fun isAppOpenAdFresh(
        loadedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (loadedAtMs <= 0L) return false
        return (nowMs - loadedAtMs) in 0L until APP_OPEN_EXPIRY_MS
    }

    internal fun shouldShowInterstitialForOpen(
        detailOpenCount: Int,
        lastShownOpenCount: Int,
        lastShownTimeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (detailOpenCount < 2) return false
        if (lastShownOpenCount == 0) return true
        val enoughOpens = (detailOpenCount - lastShownOpenCount) >= 2
        val enoughTime = (nowMs - lastShownTimeMs) >= MIN_INTERSTITIAL_INTERVAL_MS
        return enoughOpens && enoughTime
    }

    internal fun reportBannerLoaded() {
        _lastAdStatus.value = if (_interstitialReady.value || _appOpenReady.value || _nativeAdLoaded.value) {
            "Banner + Multi-Format Ads Active"
        } else {
            "Banner Ad Active"
        }
    }

    internal fun reportBannerRetrying(errorCode: Int, nextAttempt: Int) {
        _lastAdStatus.value = "No ad fill ($errorCode) • Auto-retry #$nextAttempt"
    }

    internal fun reportNativeLoaded() {
        _nativeAdLoaded.value = true
        _lastAdStatus.value = "Native + Banner Ads Active"
    }

    internal fun reportNativeRetrying(errorCode: Int, nextAttempt: Int) {
        if (!_nativeAdLoaded.value) {
            _lastAdStatus.value = "Native ad waiting ($errorCode) • Retry #$nextAttempt"
        }
    }

    fun preloadAppOpenAd(context: Context) {
        if (!ConsentManager.canRequestAds.value) return
        val currentAd = appOpenAd
        if (currentAd != null && isAppOpenAdFresh(appOpenLoadedAtMs)) {
            _appOpenReady.value = true
            return
        }
        if (currentAd != null) {
            appOpenAd = null
            _appOpenReady.value = false
        }
        if (isLoadingAppOpen) return
        val appContext = context.applicationContext
        if (!_sdkInitialized.value) {
            initialize(appContext)
            return
        }

        appOpenRetryJob?.cancel()
        isLoadingAppOpen = true
        val unitId = resolveAppOpenUnitId(appContext)
        val request = AdRequest.Builder().build()

        runCatching {
            AppOpenAd.load(
                appContext,
                unitId,
                request,
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        appOpenAd = ad
                        appOpenLoadedAtMs = System.currentTimeMillis()
                        isLoadingAppOpen = false
                        appOpenFailureCount = 0
                        _appOpenReady.value = true
                        _lastAdStatus.value = "App Open Ad Ready"
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        appOpenAd = null
                        isLoadingAppOpen = false
                        appOpenFailureCount++
                        _appOpenReady.value = false
                        if (appOpenFailureCount <= MAX_APP_OPEN_AUTO_RETRIES) {
                            val retryDelay = computeBannerRetryDelayMs(appOpenFailureCount)
                            appOpenRetryJob?.cancel()
                            appOpenRetryJob = scope.launch(Dispatchers.Main) {
                                delay(retryDelay)
                                preloadAppOpenAd(appContext)
                            }
                        }
                    }
                },
            )
        }.onFailure {
            isLoadingAppOpen = false
            _appOpenReady.value = false
        }
    }

    fun showAppOpenAdIfAvailable(
        context: Context,
        onImpression: () -> Unit = {},
        onFinished: () -> Unit,
    ): Boolean {
        if (!ConsentManager.canRequestAds.value) {
            onFinished()
            return false
        }
        if (isShowingFullScreenAd) {
            onFinished()
            return false
        }
        val activity = context.findActivity()
        val ad = appOpenAd
        if (activity == null || ad == null || !isAppOpenAdFresh(appOpenLoadedAtMs)) {
            if (ad != null && !isAppOpenAdFresh(appOpenLoadedAtMs)) {
                appOpenAd = null
                _appOpenReady.value = false
            }
            preloadAppOpenAd(context)
            onFinished()
            return false
        }

        isShowingFullScreenAd = true
        lastAppOpenShownTimeMs = System.currentTimeMillis()
        var impressionRecorded = false
        fun recordImpressionOnce() {
            if (!impressionRecorded) {
                impressionRecorded = true
                onImpression()
            }
        }
        var finishedCalled = false
        fun finishOnce() {
            if (!finishedCalled) {
                finishedCalled = true
                isShowingFullScreenAd = false
                appOpenAd = null
                _appOpenReady.value = false
                preloadAppOpenAd(context)
                onFinished()
            }
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                recordImpressionOnce()
            }

            override fun onAdImpression() {
                recordImpressionOnce()
            }

            override fun onAdDismissedFullScreenContent() {
                finishOnce()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                finishOnce()
            }
        }
        runCatching {
            ad.show(activity)
        }.onFailure {
            finishOnce()
            return false
        }
        return true
    }

    fun maybeShowAppOpenOnResume(
        context: Context,
        onImpression: () -> Unit = {},
    ) {
        if (!ConsentManager.canRequestAds.value) return
        val nowMs = System.currentTimeMillis()
        if (isShowingFullScreenAd) return
        if (lastAppOpenShownTimeMs > 0L && (nowMs - lastAppOpenShownTimeMs) < MIN_APP_OPEN_RESUME_INTERVAL_MS) {
            if (appOpenAd == null) preloadAppOpenAd(context)
            return
        }
        if (appOpenAd != null && isAppOpenAdFresh(appOpenLoadedAtMs, nowMs)) {
            showAppOpenAdIfAvailable(
                context = context,
                onImpression = onImpression,
                onFinished = {},
            )
        } else {
            preloadAppOpenAd(context)
        }
    }

    fun preloadInterstitial(context: Context) {
        if (!ConsentManager.canRequestAds.value) return
        if (interstitialAd != null || isLoadingInterstitial) return
        val appContext = context.applicationContext
        if (!_sdkInitialized.value) {
            initialize(appContext)
            return
        }
        interstitialRetryJob?.cancel()
        isLoadingInterstitial = true
        val unitId = resolveInterstitialUnitId(appContext)
        val request = AdRequest.Builder().build()
        runCatching {
            InterstitialAd.load(
                appContext,
                unitId,
                request,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitialAd = ad
                        isLoadingInterstitial = false
                        interstitialFailureCount = 0
                        _interstitialReady.value = true
                        _lastAdStatus.value = "Banner + Interstitial Ad Ready"
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitialAd = null
                        isLoadingInterstitial = false
                        interstitialFailureCount++
                        _interstitialReady.value = false
                        _lastAdStatus.value = "Ad load deferred (${error.code})"
                        if (interstitialFailureCount <= MAX_INTERSTITIAL_AUTO_RETRIES) {
                            val retryDelay = computeBannerRetryDelayMs(interstitialFailureCount)
                            interstitialRetryJob?.cancel()
                            interstitialRetryJob = scope.launch(Dispatchers.Main) {
                                delay(retryDelay)
                                preloadInterstitial(appContext)
                            }
                        }
                    }
                },
            )
        }.onFailure {
            isLoadingInterstitial = false
            _interstitialReady.value = false
        }
    }

    fun maybeShowInterstitial(
        context: Context,
        detailOpenCount: Int,
        onImpression: () -> Unit,
    ) {
        if (!ConsentManager.canRequestAds.value) return
        if (isShowingFullScreenAd) return
        val nowMs = System.currentTimeMillis()
        if (!shouldShowInterstitialForOpen(
                detailOpenCount = detailOpenCount,
                lastShownOpenCount = lastInterstitialShownOpenCount,
                lastShownTimeMs = lastInterstitialShownTimeMs,
                nowMs = nowMs,
            )
        ) {
            if (interstitialAd == null) {
                preloadInterstitial(context)
            }
            return
        }

        val activity = context.findActivity() ?: return
        val ad = interstitialAd ?: run {
            interstitialFailureCount = 0
            preloadInterstitial(context)
            return
        }

        isShowingFullScreenAd = true
        var impressionRecorded = false
        fun recordImpressionOnce() {
            if (!impressionRecorded) {
                impressionRecorded = true
                onImpression()
            }
        }

        lastInterstitialShownOpenCount = detailOpenCount
        lastInterstitialShownTimeMs = nowMs
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                recordImpressionOnce()
            }

            override fun onAdImpression() {
                recordImpressionOnce()
            }

            override fun onAdDismissedFullScreenContent() {
                isShowingFullScreenAd = false
                interstitialAd = null
                _interstitialReady.value = false
                preloadInterstitial(context)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isShowingFullScreenAd = false
                interstitialAd = null
                _interstitialReady.value = false
                preloadInterstitial(context)
            }
        }
        runCatching {
            ad.show(activity)
        }.onFailure {
            isShowingFullScreenAd = false
            interstitialAd = null
            _interstitialReady.value = false
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Composable
fun AdBannerSlot(
    modifier: Modifier = Modifier,
    placement: BannerAdPlacement = BannerAdPlacement.PRIMARY,
    onAdImpression: () -> Unit = {},
    topPaddingWhenLoadedDp: Int = 0,
    roundedCardStyle: Boolean = false,
    slotTag: String = "ad_banner_slot",
    containerTag: String = "ad_banner_container",
) {
    AdBannerSlot(
        modifier = modifier,
        placement = placement,
        onAdImpression = onAdImpression,
        initialLoadedForTest = false,
        onListenerReadyForTest = null,
        onLoadRequestedForTest = null,
        topPaddingWhenLoadedDp = topPaddingWhenLoadedDp,
        roundedCardStyle = roundedCardStyle,
        slotTag = slotTag,
        containerTag = containerTag,
    )
}

@Composable
internal fun AdBannerSlot(
    modifier: Modifier = Modifier,
    placement: BannerAdPlacement = BannerAdPlacement.PRIMARY,
    onAdImpression: () -> Unit = {},
    initialLoadedForTest: Boolean = false,
    onListenerReadyForTest: ((AdListener) -> Unit)? = null,
    onLoadRequestedForTest: (() -> Unit)? = null,
    topPaddingWhenLoadedDp: Int = 0,
    roundedCardStyle: Boolean = false,
    slotTag: String = "ad_banner_slot",
    containerTag: String = "ad_banner_container",
) {
    val isTestHookActive = onListenerReadyForTest != null || onLoadRequestedForTest != null
    val canRequestAds by ConsentManager.canRequestAds.collectAsStateWithLifecycle()
    if (!canRequestAds && !isTestHookActive) {
        return
    }
    val useWebViewFreeBanner = remember(isTestHookActive) {
        !isTestHookActive && BuildConfig.DEBUG && AdMobManager.isAndroidEmulator()
    }
    if (useWebViewFreeBanner) {
        CompactNativeBannerSlot(
            modifier = modifier,
            placement = if (placement == BannerAdPlacement.SECONDARY) {
                NativeAdPlacement.SECONDARY
            } else {
                NativeAdPlacement.PRIMARY
            },
            onAdImpression = onAdImpression,
            topPaddingWhenLoadedDp = topPaddingWhenLoadedDp,
            roundedCardStyle = roundedCardStyle,
            slotTag = slotTag,
            containerTag = containerTag,
        )
        return
    }

    val context = LocalContext.current
    val marketColors = LocalMarketColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val bannerUnitId = remember(context, placement) {
        AdMobManager.resolveBannerUnitId(context = context, placement = placement)
    }
    val sdkInitialized by AdMobManager.sdkInitialized.collectAsStateWithLifecycle()

    var isAdLoaded by remember(bannerUnitId) { mutableStateOf(initialLoadedForTest) }
    var isLoading by remember(bannerUnitId) { mutableStateOf(false) }
    var failureCount by remember(bannerUnitId) { mutableIntStateOf(0) }
    var refreshSignal by remember(bannerUnitId) { mutableIntStateOf(0) }
    var lastLoadRequestMs by remember(bannerUnitId) { mutableLongStateOf(0L) }
    var isDisposed by remember(bannerUnitId) { mutableStateOf(false) }
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    LaunchedEffect(context) {
        AdMobManager.initialize(context)
    }

    // Use deterministic fixed-size standard banner (320x50dp) with exact pixel layout bounds so the
    // AdView WebView always has valid non-zero dimensions (prevents malformed adaptive SVG viewBox JS errors
    // and avoids recreating WebViews when container width fluctuates during scrolling or layout passes).
    val adSize = remember { AdSize.BANNER }
    val bannerWidthDp = remember(adSize) { adSize.width.coerceAtLeast(320).dp }
    val bannerHeightDp = remember(adSize) { adSize.height.coerceAtLeast(50).dp }

    val bannerListener = remember(bannerUnitId) {
        object : AdListener() {
            override fun onAdLoaded() {
                if (isDisposed) return
                isAdLoaded = true
                isLoading = false
                failureCount = 0
                AdMobManager.reportBannerLoaded()
            }

            override fun onAdImpression() {
                if (isDisposed) return
                onAdImpression()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                if (isDisposed) return
                isLoading = false
                if (!isAdLoaded) {
                    failureCount++
                    AdMobManager.reportBannerRetrying(
                        errorCode = error.code,
                        nextAttempt = failureCount,
                    )
                }
            }
        }
    }

    val adView = remember(bannerUnitId) {
        val widthPx = adSize.getWidthInPixels(context).coerceAtLeast(1)
        val heightPx = adSize.getHeightInPixels(context).coerceAtLeast(1)
        AdView(context).apply {
            layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
            minimumWidth = widthPx
            minimumHeight = heightPx
            adUnitId = bannerUnitId
            setAdSize(adSize)
            adListener = bannerListener
        }
    }

    LaunchedEffect(bannerListener, onListenerReadyForTest) {
        onListenerReadyForTest?.invoke(bannerListener)
    }

    DisposableEffect(lifecycleOwner, adView) {
        isDisposed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    runCatching { adView.resume() }
                    val elapsed = System.currentTimeMillis() - lastLoadRequestMs
                    if (!isAdLoaded && !isLoading && elapsed >= 30_000L) {
                        failureCount = 0
                        refreshSignal++
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    runCatching { adView.pause() }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isDisposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                adView.adListener = object : AdListener() {}
                adView.pause()
                adView.destroy()
            }
        }
    }

    // Automatically load once SDK is ready & AdView is mounted, with bounded exponential backoff retries.
    LaunchedEffect(adView, sdkInitialized, isResumed, isAdLoaded, failureCount, refreshSignal) {
        if ((!sdkInitialized && !isTestHookActive) || !isResumed || isAdLoaded || isDisposed) return@LaunchedEffect
        if (failureCount > AdMobManager.MAX_BANNER_AUTO_RETRIES) return@LaunchedEffect

        if (failureCount > 0) {
            delay(AdMobManager.computeBannerRetryDelayMs(failureCount))
        }

        if ((!sdkInitialized && !isTestHookActive) || !isResumed || isAdLoaded || isDisposed || isLoading) return@LaunchedEffect

        isLoading = true
        lastLoadRequestMs = System.currentTimeMillis()
        onLoadRequestedForTest?.invoke()
        adView.post {
            if (!isDisposed && !isAdLoaded && isResumed) {
                runCatching {
                    adView.loadAd(AdRequest.Builder().build())
                }.onFailure {
                    isLoading = false
                    failureCount++
                }
            }
        }

        // Watchdog: if neither onAdLoaded nor onAdFailedToLoad fires within 30s, allow one clean retry.
        delay(30_000L)
        if (!isDisposed && isResumed && !isAdLoaded && isLoading) {
            isLoading = false
            failureCount++
        }
    }

    val heightFraction by animateFloatAsState(
        targetValue = if (isAdLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "ad_banner_visibility",
    )

    val shape = if (roundedCardStyle) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp)
    val topPaddingDp = if (topPaddingWhenLoadedDp > 0 && isAdLoaded) topPaddingWhenLoadedDp.dp else 0.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(slotTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPaddingDp)
                .testTag(containerTag)
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val targetHeight = (placeable.height * heightFraction).roundToInt().coerceAtLeast(0)
                    layout(placeable.width, targetHeight) {
                        // Keep AdView laid out at its exact fixed banner size so WebView always gets valid dimensions.
                        placeable.placeRelative(0, 0)
                    }
                }
                .alpha(heightFraction)
                .then(
                    if (isAdLoaded) {
                        Modifier
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = if (roundedCardStyle) 1.dp else 0.5.dp,
                                color = marketColors.border,
                                shape = shape,
                            )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { adView },
                modifier = Modifier
                    .width(bannerWidthDp)
                    .height(bannerHeightDp),
            )
        }
    }
}
