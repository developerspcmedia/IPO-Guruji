package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ads.AdMobManager
import com.example.ui.components.GlassBackdrop
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AppOpenSplashScreen(
    onFinished: () -> Unit,
    onAdImpression: () -> Unit = {},
    minimumDisplayMs: Long = 1_100L,
    adWaitTimeoutMs: Long = 2_800L,
    appOpenReadyFlow: StateFlow<Boolean> = AdMobManager.appOpenReady,
    onShowAppOpenAd: (onImpression: () -> Unit, onDone: () -> Unit) -> Boolean = { _, _ -> false },
    useDefaultAdMobShow: Boolean = true,
) {
    val context = LocalContext.current
    val glass = LocalGlassStyle.current
    val marketColors = LocalMarketColors.current
    val isAppOpenReady by appOpenReadyFlow.collectAsStateWithLifecycle()
    var targetProgress by remember { mutableFloatStateOf(0.18f) }
    var statusText by remember { mutableStateOf("Loading live IPO & GMP feed...") }
    var hasCompleted by remember { mutableStateOf(false) }
    var minDelayElapsed by remember { mutableStateOf(false) }

    fun completeOnce() {
        if (!hasCompleted) {
            hasCompleted = true
            onFinished()
        }
    }

    fun tryShowAdAndFinish() {
        if (hasCompleted) return
        val shown = if (useDefaultAdMobShow) {
            AdMobManager.showAppOpenAdIfAvailable(
                context = context,
                onImpression = onAdImpression,
                onFinished = ::completeOnce,
            )
        } else {
            onShowAppOpenAd(onAdImpression, ::completeOnce)
        }
        if (!shown) {
            completeOnce()
        }
    }

    LaunchedEffect(context) {
        if (useDefaultAdMobShow) {
            AdMobManager.initialize(context)
            AdMobManager.preloadAppOpenAd(context)
        }
        targetProgress = 0.55f
        delay(minimumDisplayMs)
        minDelayElapsed = true
        if (isAppOpenReady && !hasCompleted) {
            targetProgress = 1f
            tryShowAdAndFinish()
            return@LaunchedEffect
        }

        statusText = "Syncing Mainboard & SME IPOs..."
        targetProgress = 0.88f
        val remainingMs = (adWaitTimeoutMs - minimumDisplayMs).coerceAtLeast(300L)
        delay(remainingMs)
        targetProgress = 1f
        tryShowAdAndFinish()
    }

    LaunchedEffect(isAppOpenReady, minDelayElapsed) {
        if (isAppOpenReady && minDelayElapsed && !hasCompleted) {
            targetProgress = 1f
            tryShowAdAndFinish()
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "splash_progress",
    )

    GlassBackdrop(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_open_splash_screen"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            // Centered Liquid Glass Brand Hero
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(116.dp),
                    shape = RoundedCornerShape(32.dp),
                    color = glass.cardFill,
                    border = glassBorderStroke(glass = glass, width = 1.2.dp),
                    shadowElevation = if (glass.isDark) 0.dp else 10.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            modifier = Modifier
                                .size(86.dp)
                                .testTag("splash_brand_logo"),
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xFF080B12),
                            border = BorderStroke(0.8.dp, glass.specularBorderTop),
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_ipo_guruji_logo),
                                contentDescription = "IPO Guruji App Logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = "IPO Guruji",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Mainboard & SME IPO Tracker • Live GMP • Subscription",
                    style = MaterialTheme.typography.bodyMedium,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SplashFeatureBadge(label = "Live GMP", dotColor = marketColors.bullish)
                    SplashFeatureBadge(label = "NSE • BSE", dotColor = MaterialTheme.colorScheme.primary)
                    SplashFeatureBadge(label = "SME Radar", dotColor = marketColors.smeBadge)
                }
            }

            // Minimalist Bottom Loading Bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = 300.dp)
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .testTag("splash_loading_bar"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = glass.pillFill,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = marketColors.mutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("splash_status_text"),
                )
            }
        }
    }
}

@Composable
private fun SplashFeatureBadge(
    label: String,
    dotColor: Color,
) {
    val glass = LocalGlassStyle.current
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = glass.cardFill,
        border = glassBorderStroke(glass = glass, width = 0.85.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
