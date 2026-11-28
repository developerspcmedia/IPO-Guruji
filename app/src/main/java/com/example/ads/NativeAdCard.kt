package com.example.ads

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.LocalMarketColors
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Policy-compliant Google AdMob Native Ad card styled to blend with IPO Guruji's fintech cards.
 * - Completely hidden (0dp height) when no ad is loaded so no empty gap ever appears.
 * - Auto-retries silently with exponential backoff if a load fails or no fill occurs.
 * - Registers all required views on NativeAdView and displays a clear "Ad" badge.
 */
@Composable
fun NativeAdSlot(
    modifier: Modifier = Modifier,
    placement: NativeAdPlacement = NativeAdPlacement.PRIMARY,
    topPaddingDp: Int = 0,
    onAdImpression: () -> Unit = {},
    slotTag: String = "native_ad_slot",
) {
    val canRequestAds by ConsentManager.canRequestAds.collectAsStateWithLifecycle()
    if (!canRequestAds) {
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val nativeUnitId = remember(context, placement) {
        AdMobManager.resolveNativeUnitId(context = context, placement = placement)
    }
    val sdkInitialized by AdMobManager.sdkInitialized.collectAsStateWithLifecycle()

    val colorScheme = MaterialTheme.colorScheme
    val marketColors = LocalMarketColors.current

    var nativeAd by remember(nativeUnitId) { mutableStateOf<NativeAd?>(null) }
    var isLoading by remember(nativeUnitId) { mutableStateOf(false) }
    var failureCount by remember(nativeUnitId) { mutableIntStateOf(0) }
    var refreshSignal by remember(nativeUnitId) { mutableIntStateOf(0) }
    var isDisposed by remember(nativeUnitId) { mutableStateOf(false) }
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    LaunchedEffect(context) {
        AdMobManager.initialize(context)
    }

    DisposableEffect(lifecycleOwner) {
        isDisposed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    if (nativeAd == null && !isLoading && failureCount > AdMobManager.MAX_BANNER_AUTO_RETRIES) {
                        failureCount = 0
                        refreshSignal++
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isDisposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            nativeAd?.destroy()
            nativeAd = null
        }
    }

    LaunchedEffect(nativeUnitId, sdkInitialized, isResumed, nativeAd, failureCount, refreshSignal) {
        if (!sdkInitialized || !isResumed || nativeAd != null || isDisposed) return@LaunchedEffect
        if (failureCount > AdMobManager.MAX_BANNER_AUTO_RETRIES) return@LaunchedEffect

        if (failureCount > 0) {
            delay(AdMobManager.computeBannerRetryDelayMs(failureCount))
        }
        if (!sdkInitialized || !isResumed || nativeAd != null || isDisposed || isLoading) return@LaunchedEffect

        isLoading = true
        runCatching {
            val loader = AdLoader.Builder(context, nativeUnitId)
                .forNativeAd { loadedAd ->
                    if (isDisposed) {
                        loadedAd.destroy()
                        return@forNativeAd
                    }
                    nativeAd?.destroy()
                    nativeAd = loadedAd
                    isLoading = false
                    failureCount = 0
                    AdMobManager.reportNativeLoaded()
                }
                .withAdListener(
                    object : AdListener() {
                        override fun onAdImpression() {
                            if (!isDisposed) onAdImpression()
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            if (isDisposed) return
                            isLoading = false
                            if (nativeAd == null) {
                                failureCount++
                                AdMobManager.reportNativeRetrying(
                                    errorCode = error.code,
                                    nextAttempt = failureCount,
                                )
                            }
                        }
                    },
                )
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build(),
                )
                .build()
            loader.loadAd(AdRequest.Builder().build())
        }.onFailure {
            isLoading = false
            failureCount++
        }

        // Watchdog: retry once if ad neither loaded nor errored within 30 seconds
        delay(30_000L)
        if (!isDisposed && isResumed && nativeAd == null && isLoading) {
            isLoading = false
            failureCount++
        }
    }

    val loadedAd = nativeAd
    AnimatedVisibility(
        visible = loadedAd != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
            .fillMaxWidth()
            .testTag(slotTag),
    ) {
        if (loadedAd != null) {
            val surfaceColor = colorScheme.surface.toArgb()
            val borderColor = marketColors.border.toArgb()
            val primaryTextColor = colorScheme.onSurface.toArgb()
            val mutedTextColor = marketColors.mutedText.toArgb()
            val badgeBgColor = colorScheme.primaryContainer.toArgb()
            val badgeTextColor = colorScheme.primary.toArgb()
            val ctaBgColor = marketColors.ctaGold.toArgb()
            val ctaTextColor = marketColors.ctaGoldInk.toArgb()

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topPaddingDp.dp)
                    .testTag("native_ad_view"),
                factory = { ctx ->
                    createStyledNativeAdView(
                        context = ctx,
                        surfaceColor = surfaceColor,
                        borderColor = borderColor,
                        primaryTextColor = primaryTextColor,
                        mutedTextColor = mutedTextColor,
                        badgeBgColor = badgeBgColor,
                        badgeTextColor = badgeTextColor,
                        ctaBgColor = ctaBgColor,
                        ctaTextColor = ctaTextColor,
                    )
                },
                update = { adView ->
                    bindNativeAdToView(adView, loadedAd)
                },
            )
        }
    }
}

/**
 * Compact 52dp horizontal AdMob Native Banner rendered with 100% native Android Views (NativeAdView).
 * Used on Android Emulator debug builds to avoid WebView HTML banner SVG viewBox JS errors emitted by
 * Google's remote emulator test banner creative while preserving real AdMob SDK integration and 0dp
 * collapse when unfilled.
 */
@Composable
internal fun CompactNativeBannerSlot(
    modifier: Modifier = Modifier,
    placement: NativeAdPlacement = NativeAdPlacement.PRIMARY,
    onAdImpression: () -> Unit = {},
    topPaddingWhenLoadedDp: Int = 0,
    roundedCardStyle: Boolean = false,
    slotTag: String = "ad_banner_slot",
    containerTag: String = "ad_banner_container",
) {
    val canRequestAds by ConsentManager.canRequestAds.collectAsStateWithLifecycle()
    if (!canRequestAds) {
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val nativeUnitId = remember(context, placement) {
        AdMobManager.resolveNativeUnitId(context = context, placement = placement)
    }
    val sdkInitialized by AdMobManager.sdkInitialized.collectAsStateWithLifecycle()

    val colorScheme = MaterialTheme.colorScheme
    val marketColors = LocalMarketColors.current

    var nativeAd by remember(nativeUnitId) { mutableStateOf<NativeAd?>(null) }
    var isLoading by remember(nativeUnitId) { mutableStateOf(false) }
    var failureCount by remember(nativeUnitId) { mutableIntStateOf(0) }
    var isDisposed by remember(nativeUnitId) { mutableStateOf(false) }
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    LaunchedEffect(context) {
        AdMobManager.initialize(context)
    }

    DisposableEffect(lifecycleOwner) {
        isDisposed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isDisposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            nativeAd?.destroy()
            nativeAd = null
        }
    }

    LaunchedEffect(nativeUnitId, sdkInitialized, isResumed, nativeAd, failureCount) {
        if (!sdkInitialized || !isResumed || nativeAd != null || isDisposed) return@LaunchedEffect
        if (failureCount > AdMobManager.MAX_BANNER_AUTO_RETRIES) return@LaunchedEffect

        if (failureCount > 0) {
            delay(AdMobManager.computeBannerRetryDelayMs(failureCount))
        }
        if (!sdkInitialized || !isResumed || nativeAd != null || isDisposed || isLoading) return@LaunchedEffect

        isLoading = true
        runCatching {
            val loader = AdLoader.Builder(context, nativeUnitId)
                .forNativeAd { loadedAd ->
                    if (isDisposed) {
                        loadedAd.destroy()
                        return@forNativeAd
                    }
                    nativeAd?.destroy()
                    nativeAd = loadedAd
                    isLoading = false
                    failureCount = 0
                    AdMobManager.reportBannerLoaded()
                }
                .withAdListener(
                    object : AdListener() {
                        override fun onAdImpression() {
                            if (!isDisposed) onAdImpression()
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            if (isDisposed) return
                            isLoading = false
                            if (nativeAd == null) {
                                failureCount++
                                AdMobManager.reportBannerRetrying(
                                    errorCode = error.code,
                                    nextAttempt = failureCount,
                                )
                            }
                        }
                    },
                )
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build(),
                )
                .build()
            loader.loadAd(AdRequest.Builder().build())
        }.onFailure {
            isLoading = false
            failureCount++
        }
    }

    val loadedAd = nativeAd
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(slotTag),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(containerTag),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (loadedAd != null) {
                val surfaceColor = colorScheme.surface.toArgb()
                val borderColor = marketColors.border.toArgb()
                val primaryTextColor = colorScheme.onSurface.toArgb()
                val mutedTextColor = marketColors.mutedText.toArgb()
                val badgeBgColor = colorScheme.primaryContainer.toArgb()
                val badgeTextColor = colorScheme.primary.toArgb()
                val ctaBgColor = marketColors.ctaGold.toArgb()
                val ctaTextColor = marketColors.ctaGoldInk.toArgb()

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topPaddingWhenLoadedDp.dp),
                    factory = { ctx ->
                        createCompactNativeBannerView(
                            context = ctx,
                            surfaceColor = surfaceColor,
                            borderColor = borderColor,
                            primaryTextColor = primaryTextColor,
                            mutedTextColor = mutedTextColor,
                            badgeBgColor = badgeBgColor,
                            badgeTextColor = badgeTextColor,
                            ctaBgColor = ctaBgColor,
                            ctaTextColor = ctaTextColor,
                            cornerRadiusDp = if (roundedCardStyle) 14 else 0,
                        )
                    },
                    update = { adView ->
                        bindNativeAdToView(adView, loadedAd)
                    },
                )
            }
        }
    }
}

private fun createCompactNativeBannerView(
    context: Context,
    surfaceColor: Int,
    borderColor: Int,
    primaryTextColor: Int,
    mutedTextColor: Int,
    badgeBgColor: Int,
    badgeTextColor: Int,
    ctaBgColor: Int,
    ctaTextColor: Int,
    cornerRadiusDp: Int,
): NativeAdView {
    fun Int.dpPx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        context.resources.displayMetrics,
    ).roundToInt()

    val adView = NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp.dpPx().toFloat()
            setColor(surfaceColor)
            setStroke(1.dpPx(), borderColor)
        }
        setPadding(10.dpPx(), 8.dpPx(), 10.dpPx(), 8.dpPx())
    }

    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(34.dpPx(), 34.dpPx()).apply {
            marginEnd = 8.dpPx()
        }
    }

    val textCol = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8.dpPx()
        }
    }

    val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val adBadge = TextView(context).apply {
        text = "Ad"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(badgeTextColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 99.dpPx().toFloat()
            setColor(badgeBgColor)
        }
        setPadding(6.dpPx(), 1.dpPx(), 6.dpPx(), 1.dpPx())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = 6.dpPx()
        }
    }

    val headlineView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(primaryTextColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    titleRow.addView(adBadge)
    titleRow.addView(headlineView)

    val bodyView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(mutedTextColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    textCol.addView(titleRow)
    textCol.addView(bodyView)

    val ctaButton = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(ctaTextColor)
        minHeight = 32.dpPx()
        minimumHeight = 32.dpPx()
        minWidth = 0
        minimumWidth = 0
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 9.dpPx().toFloat()
            setColor(ctaBgColor)
        }
        setPadding(10.dpPx(), 4.dpPx(), 10.dpPx(), 4.dpPx())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    row.addView(iconView)
    row.addView(textCol)
    row.addView(ctaButton)
    adView.addView(row)

    adView.iconView = iconView
    adView.headlineView = headlineView
    adView.bodyView = bodyView
    adView.callToActionView = ctaButton

    return adView
}

private fun createStyledNativeAdView(
    context: Context,
    surfaceColor: Int,
    borderColor: Int,
    primaryTextColor: Int,
    mutedTextColor: Int,
    badgeBgColor: Int,
    badgeTextColor: Int,
    ctaBgColor: Int,
    ctaTextColor: Int,
): NativeAdView {
    fun Int.dpPx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        context.resources.displayMetrics,
    ).roundToInt()

    val adView = NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18.dpPx().toFloat()
            setColor(surfaceColor)
            setStroke(1.dpPx(), borderColor)
        }
        setPadding(14.dpPx(), 14.dpPx(), 14.dpPx(), 14.dpPx())
    }

    val rootColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    // Header row: "Ad" disclosure pill + Advertiser name
    val badgeRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = 10.dpPx()
        }
    }

    val adBadge = TextView(context).apply {
        text = "Ad"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(badgeTextColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 99.dpPx().toFloat()
            setColor(badgeBgColor)
        }
        setPadding(8.dpPx(), 2.dpPx(), 8.dpPx(), 2.dpPx())
    }

    val advertiserView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setTextColor(mutedTextColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply {
            marginStart = 8.dpPx()
            marginEnd = 24.dpPx() // Leave room for AdChoices badge in top-right
        }
    }

    badgeRow.addView(adBadge)
    badgeRow.addView(advertiserView)

    // Content row: Icon + Headline/Body + CTA button
    val contentRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(44.dpPx(), 44.dpPx()).apply {
            marginEnd = 12.dpPx()
        }
    }

    val textCol = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply {
            marginEnd = 10.dpPx()
        }
    }

    val headlineView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(primaryTextColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    val bodyView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(mutedTextColor)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = 3.dpPx()
        }
    }

    textCol.addView(headlineView)
    textCol.addView(bodyView)

    val ctaButton = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(ctaTextColor)
        minHeight = 38.dpPx()
        minimumHeight = 38.dpPx()
        minWidth = 0
        minimumWidth = 0
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 11.dpPx().toFloat()
            setColor(ctaBgColor)
        }
        setPadding(12.dpPx(), 6.dpPx(), 12.dpPx(), 6.dpPx())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    contentRow.addView(iconView)
    contentRow.addView(textCol)
    contentRow.addView(ctaButton)

    rootColumn.addView(badgeRow)
    rootColumn.addView(contentRow)
    adView.addView(rootColumn)

    adView.advertiserView = advertiserView
    adView.iconView = iconView
    adView.headlineView = headlineView
    adView.bodyView = bodyView
    adView.callToActionView = ctaButton

    return adView
}

private fun bindNativeAdToView(adView: NativeAdView, nativeAd: NativeAd) {
    (adView.headlineView as? TextView)?.text = nativeAd.headline.orEmpty()

    val bodyTextView = adView.bodyView as? TextView
    if (!nativeAd.body.isNullOrBlank()) {
        bodyTextView?.visibility = View.VISIBLE
        bodyTextView?.text = nativeAd.body
    } else {
        bodyTextView?.visibility = View.GONE
    }

    val advertiserTextView = adView.advertiserView as? TextView
    val advertiser = nativeAd.advertiser ?: nativeAd.store
    if (!advertiser.isNullOrBlank()) {
        advertiserTextView?.visibility = View.VISIBLE
        advertiserTextView?.text = advertiser
    } else {
        advertiserTextView?.visibility = View.GONE
    }

    val iconImageView = adView.iconView as? ImageView
    val iconDrawable = nativeAd.icon?.drawable
    if (iconDrawable != null) {
        iconImageView?.visibility = View.VISIBLE
        iconImageView?.setImageDrawable(iconDrawable)
    } else {
        iconImageView?.visibility = View.GONE
    }

    val ctaView = adView.callToActionView as? Button
    if (!nativeAd.callToAction.isNullOrBlank()) {
        ctaView?.visibility = View.VISIBLE
        ctaView?.text = nativeAd.callToAction
    } else {
        ctaView?.visibility = View.GONE
    }

    adView.setNativeAd(nativeAd)
}
