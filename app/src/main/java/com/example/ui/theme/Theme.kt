package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.preferences.ThemePreference

@Immutable
data class MarketColors(
    val bullish: Color,
    val bullishContainer: Color,
    val bearish: Color,
    val bearishContainer: Color,
    val smeBadge: Color,
    val smeContainer: Color,
    val border: Color,
    val mutedText: Color,
    val ctaGold: Color = CtaGoldLight,
    val ctaGoldInk: Color = CtaGoldInk,
    val liveAccent: Color = LiveAccentLight,
    val liveContainer: Color = LiveBgLight,
)

@Immutable
data class GlassStyle(
    val isDark: Boolean,
    val isAmoled: Boolean = false,
    val isGlass: Boolean = false,
    val backdropTop: Color,
    val backdropMid: Color,
    val backdropBottom: Color,
    val ambientOrbPrimary: Color,
    val ambientOrbSecondary: Color,
    val ambientOrbTertiary: Color,
    val cardFill: Color,
    val elevatedFill: Color,
    val dockFill: Color,
    val sheetFill: Color,
    val pillFill: Color,
    val activeDockPillFill: Color,
    val specularBorderTop: Color,
    val specularBorderBottom: Color,
    val innerSheen: Color,
    val hairline: Color,
)

/** Clean opaque daylight theme */
val LightGlassStyle = GlassStyle(
    isDark = false,
    isAmoled = false,
    isGlass = false,
    backdropTop = PaperBackground,
    backdropMid = PaperBackground,
    backdropBottom = PaperBackground,
    ambientOrbPrimary = Color.Transparent,
    ambientOrbSecondary = Color.Transparent,
    ambientOrbTertiary = Color.Transparent,
    cardFill = PureCardSurface,
    elevatedFill = PureCardSurface,
    dockFill = PureCardSurface,
    sheetFill = Color(0xFFFAFBFD),
    pillFill = SoftSurfaceVariant,
    activeDockPillFill = CobaltContainerLight,
    specularBorderTop = HairlineBorderLight,
    specularBorderBottom = HairlineBorderLight,
    innerSheen = Color.Transparent,
    hairline = HairlineBorderLight,
)

/** True pitch-black AMOLED OLED dark theme with opaque surfaces and maximum text legibility */
val AmoledDarkGlassStyle = GlassStyle(
    isDark = true,
    isAmoled = true,
    isGlass = false,
    backdropTop = AmoledBackground,
    backdropMid = AmoledBackground,
    backdropBottom = AmoledBackground,
    ambientOrbPrimary = Color.Transparent,
    ambientOrbSecondary = Color.Transparent,
    ambientOrbTertiary = Color.Transparent,
    cardFill = AmoledSurface,
    elevatedFill = AmoledElevatedSurface,
    dockFill = AmoledDockSurface,
    sheetFill = AmoledSheetSurface,
    pillFill = AmoledSurfaceVariant,
    activeDockPillFill = CobaltContainerAmoled,
    specularBorderTop = AmoledBorder,
    specularBorderBottom = AmoledBorder,
    innerSheen = Color.Transparent,
    hairline = AmoledHairline,
)

/** Luminous iOS-style transparent frosted glass theme layered over a soft aurora backdrop */
val IosTransparentGlassStyle = GlassStyle(
    isDark = false,
    isAmoled = false,
    isGlass = true,
    backdropTop = GlassBackdropIosTop,
    backdropMid = GlassBackdropIosMid,
    backdropBottom = GlassBackdropIosBottom,
    ambientOrbPrimary = GlassOrbIosPrimary,
    ambientOrbSecondary = GlassOrbIosSecondary,
    ambientOrbTertiary = GlassOrbIosTertiary,
    cardFill = GlassSurfaceCard,
    elevatedFill = Color(0xDBFFFFFF),
    dockFill = Color(0xC7FFFFFF),
    sheetFill = Color(0xE0F2F7FF),
    pillFill = Color(0x8CFFFFFF),
    activeDockPillFill = CobaltContainerGlass,
    specularBorderTop = Color(0xFFFFFFFF),
    specularBorderBottom = Color(0x4064748B),
    innerSheen = Color(0x8AFFFFFF),
    hairline = Color(0x260F172A),
)

val LocalGlassStyle = staticCompositionLocalOf { LightGlassStyle }

val LocalMarketColors = staticCompositionLocalOf {
    MarketColors(
        bullish = BullishEmerald,
        bullishContainer = BullishBgLight,
        bearish = BearishCrimson,
        bearishContainer = BearishBgLight,
        smeBadge = AmberGold,
        smeContainer = AmberBgLight,
        border = HairlineBorderLight,
        mutedText = MutedSlate,
        ctaGold = CtaGoldLight,
        ctaGoldInk = CtaGoldInk,
        liveAccent = LiveAccentLight,
        liveContainer = LiveBgLight,
    )
}

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = CobaltPrimary,
    secondary = TealAccent,
    onSecondary = Color.White,
    secondaryContainer = BullishBgLight,
    onSecondaryContainer = BullishEmerald,
    tertiary = AmberGold,
    onTertiary = Color.White,
    tertiaryContainer = AmberBgLight,
    onTertiaryContainer = AmberGold,
    background = PaperBackground,
    onBackground = NavyInk,
    surface = PureCardSurface,
    onSurface = NavyInk,
    surfaceVariant = SoftSurfaceVariant,
    onSurfaceVariant = SlateInk,
    outline = HairlineBorderLight,
    outlineVariant = HairlineBorderLight,
    error = BearishCrimson,
    onError = Color.White,
    errorContainer = BearishBgLight,
    onErrorContainer = BearishCrimson,
)

private val AmoledDarkColorScheme = darkColorScheme(
    primary = CobaltPrimaryAmoled,
    onPrimary = Color(0xFF001429),
    primaryContainer = CobaltContainerAmoled,
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = TealAccentBright,
    onSecondary = Color(0xFF001826),
    secondaryContainer = BullishBgAmoled,
    onSecondaryContainer = BullishEmeraldBright,
    tertiary = AmberGoldBright,
    onTertiary = Color(0xFF211300),
    tertiaryContainer = AmberBgAmoled,
    onTertiaryContainer = AmberGoldBright,
    background = AmoledBackground,
    onBackground = AmoledPrimaryText,
    surface = AmoledSurface,
    onSurface = AmoledPrimaryText,
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = AmoledSecondaryText,
    surfaceContainerLowest = AmoledBackground,
    surfaceContainerLow = AmoledSheetSurface,
    surfaceContainer = AmoledSurface,
    surfaceContainerHigh = AmoledSurfaceVariant,
    surfaceContainerHighest = AmoledElevatedSurface,
    outline = AmoledBorder,
    outlineVariant = AmoledHairline,
    error = BearishCrimsonBright,
    onError = Color(0xFF280507),
    errorContainer = BearishBgAmoled,
    onErrorContainer = BearishCrimsonBright,
)

private val IosGlassColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = CobaltContainerGlass,
    onPrimaryContainer = Color(0xFF0057B8),
    secondary = TealAccent,
    onSecondary = Color.White,
    secondaryContainer = BullishBgGlass,
    onSecondaryContainer = BullishEmerald,
    tertiary = AmberGold,
    onTertiary = Color.White,
    tertiaryContainer = AmberBgGlass,
    onTertiaryContainer = Color(0xFFB45309),
    background = GlassBackdropIosMid,
    onBackground = GlassInkPrimary,
    surface = GlassSurfaceCard,
    onSurface = GlassInkPrimary,
    surfaceVariant = GlassSurfaceVariant,
    onSurfaceVariant = GlassInkSecondary,
    outline = GlassBorder,
    outlineVariant = Color(0x3364748B),
    error = BearishCrimson,
    onError = Color.White,
    errorContainer = BearishBgGlass,
    onErrorContainer = BearishCrimson,
)

val AppShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun resolveEffectiveTheme(
    themePreference: ThemePreference,
    systemDark: Boolean = isSystemInDarkTheme(),
): ThemePreference = when (themePreference) {
    ThemePreference.SYSTEM -> if (systemDark) ThemePreference.DARK else ThemePreference.LIGHT
    ThemePreference.LIGHT -> ThemePreference.LIGHT
    ThemePreference.DARK -> ThemePreference.DARK
    ThemePreference.IOS_GLASS -> ThemePreference.IOS_GLASS
}

@Composable
fun IpoPulseTheme(
    themePreference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val effective = resolveEffectiveTheme(themePreference)

    val colorScheme = when (effective) {
        ThemePreference.DARK -> AmoledDarkColorScheme
        ThemePreference.IOS_GLASS -> IosGlassColorScheme
        else -> LightColorScheme
    }

    val glassStyle = when (effective) {
        ThemePreference.DARK -> AmoledDarkGlassStyle
        ThemePreference.IOS_GLASS -> IosTransparentGlassStyle
        else -> LightGlassStyle
    }

    val marketColors = when (effective) {
        ThemePreference.DARK -> MarketColors(
            bullish = BullishEmeraldBright,
            bullishContainer = BullishBgAmoled,
            bearish = BearishCrimsonBright,
            bearishContainer = BearishBgAmoled,
            smeBadge = AmberGoldBright,
            smeContainer = AmberBgAmoled,
            border = AmoledBorder,
            mutedText = AmoledMutedText,
            ctaGold = CtaGoldAmoled,
            ctaGoldInk = CtaGoldInk,
            liveAccent = LiveAccentAmoled,
            liveContainer = LiveBgAmoled,
        )

        ThemePreference.IOS_GLASS -> MarketColors(
            bullish = BullishEmerald,
            bullishContainer = BullishBgGlass,
            bearish = BearishCrimson,
            bearishContainer = BearishBgGlass,
            smeBadge = Color(0xFFB45309),
            smeContainer = AmberBgGlass,
            border = GlassBorder,
            mutedText = GlassMutedText,
            ctaGold = CtaGoldLight,
            ctaGoldInk = CtaGoldInk,
            liveAccent = LiveAccentLight,
            liveContainer = LiveBgLight,
        )

        else -> MarketColors(
            bullish = BullishEmerald,
            bullishContainer = BullishBgLight,
            bearish = BearishCrimson,
            bearishContainer = BearishBgLight,
            smeBadge = AmberGold,
            smeContainer = AmberBgLight,
            border = HairlineBorderLight,
            mutedText = MutedSlate,
            ctaGold = CtaGoldLight,
            ctaGoldInk = CtaGoldInk,
            liveAccent = LiveAccentLight,
            liveContainer = LiveBgLight,
        )
    }

    CompositionLocalProvider(
        LocalMarketColors provides marketColors,
        LocalGlassStyle provides glassStyle,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

@Composable
fun IpoPulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    IpoPulseTheme(
        themePreference = if (darkTheme) ThemePreference.DARK else ThemePreference.LIGHT,
        content = content,
    )
}
