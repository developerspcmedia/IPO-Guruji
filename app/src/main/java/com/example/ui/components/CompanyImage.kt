package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.data.model.BoardType
import com.example.ui.theme.LocalMarketColors
import okhttp3.OkHttpClient

object IpoImageLoader {
    @Volatile
    private var instance: ImageLoader? = null
    @Volatile
    private var cachedAppContext: Context? = null

    fun get(context: Context): ImageLoader {
        val appContext = context.applicationContext
        val current = instance
        if (current != null && cachedAppContext === appContext) {
            return current
        }
        return synchronized(this) {
            val recheck = instance
            if (recheck != null && cachedAppContext === appContext) {
                recheck
            } else {
                build(appContext).also {
                    instance = it
                    cachedAppContext = appContext
                }
            }
        }
    }

    private fun build(appContext: Context): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "IPOGuruji/1.0 (https://ipoguruji.app; developers.pcmedia@gmail.com)",
                    )
                    .build()
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(appContext)
            .okHttpClient(okHttpClient)
            .components {
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}

@Composable
fun rememberIpoImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) { IpoImageLoader.get(context) }
}

@Composable
fun CompanyLogo(
    logoUrl: String,
    companyName: String,
    shortCode: String,
    boardType: BoardType,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    width: Dp = size,
    height: Dp = size,
) {
    val marketColors = LocalMarketColors.current
    val context = LocalContext.current
    val imageLoader = rememberIpoImageLoader()

    if (logoUrl.isBlank()) {
        CompanyMonogram(
            shortCode = shortCode,
            boardType = boardType,
            modifier = modifier
                .size(width = width, height = height)
                .testTag("company_logo_$shortCode"),
        )
        return
    }

    Surface(
        modifier = modifier
            .size(width = width, height = height)
            .testTag("company_logo_$shortCode"),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, marketColors.border.copy(alpha = 0.65f)),
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(logoUrl)
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = "$companyName logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            loading = {
                CompanyMonogram(
                    shortCode = shortCode,
                    boardType = boardType,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            error = {
                CompanyMonogram(
                    shortCode = shortCode,
                    boardType = boardType,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }
}

@Composable
fun CompanyBannerImage(
    bannerUrl: String,
    contentDescriptionName: String,
    headline: String,
    subheadline: String,
    modifier: Modifier = Modifier,
    height: Dp = 148.dp,
) {
    val context = LocalContext.current
    val imageLoader = rememberIpoImageLoader()
    val fallbackBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF0C1C3C),
            Color(0xFF173673),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(fallbackBrush)
            .testTag("company_banner_image"),
    ) {
        if (bannerUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(bannerUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = "$contentDescriptionName industry image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(fallbackBrush),
                    )
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(fallbackBrush),
                    )
                },
            )
        }

        // Bottom readability scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x66091326),
                            Color(0xDD091326),
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subheadline,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFCBD5E1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
