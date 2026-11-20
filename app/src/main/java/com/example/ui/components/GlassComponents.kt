package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GlassStyle
import com.example.ui.theme.LocalGlassStyle

/**
 * Luminous atmospheric mesh canvas inspired by the latest iOS Liquid Glass aesthetic.
 * Frosted glass cards, capsules, and floating docks rest transparently over this canvas.
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassStyle.current

    Box(
        modifier = modifier.drawBehind {
            if (!glass.isGlass) {
                // Pure pitch-black (#000000) for AMOLED Dark or crisp daylight for Light
                drawRect(color = glass.backdropMid)
                return@drawBehind
            }

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glass.backdropTop,
                        glass.backdropMid,
                        glass.backdropBottom,
                    ),
                ),
            )

            val w = size.width
            val h = size.height
            val baseRadius = maxOf(w, h) * 0.58f

            // Top-left luminous sky orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glass.ambientOrbPrimary, Color.Transparent),
                    center = Offset(w * 0.14f, h * 0.08f),
                    radius = baseRadius * 0.94f,
                ),
                radius = baseRadius * 0.94f,
                center = Offset(w * 0.14f, h * 0.08f),
            )

            // Mid-right soft iris/cyan orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glass.ambientOrbTertiary, Color.Transparent),
                    center = Offset(w * 0.90f, h * 0.36f),
                    radius = baseRadius * 0.80f,
                ),
                radius = baseRadius * 0.80f,
                center = Offset(w * 0.90f, h * 0.36f),
            )

            // Bottom-left luminous lavender/indigo orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glass.ambientOrbSecondary, Color.Transparent),
                    center = Offset(w * 0.22f, h * 0.86f),
                    radius = baseRadius * 0.86f,
                ),
                radius = baseRadius * 0.86f,
                center = Offset(w * 0.22f, h * 0.86f),
            )
        },
        content = content,
    )
}

/**
 * Creates a diagonal top-left-to-bottom-right specular border brush for iOS Glass,
 * or a crisp high-definition hairline border for AMOLED Dark and Light modes.
 */
fun glassSpecularBorderBrush(
    glass: GlassStyle,
    accentTint: Color? = null,
): Brush {
    if (!glass.isGlass) {
        val edgeColor = accentTint?.copy(alpha = if (glass.isAmoled) 0.72f else 0.45f)
            ?: glass.specularBorderTop
        return Brush.linearGradient(colors = listOf(edgeColor, glass.specularBorderBottom))
    }

    val startColor = accentTint?.copy(alpha = 0.52f) ?: glass.specularBorderTop
    val midColor = Color.White.copy(alpha = 0.78f)
    return Brush.linearGradient(
        colors = listOf(
            startColor,
            midColor,
            glass.specularBorderBottom,
        ),
    )
}

fun glassBorderStroke(
    glass: GlassStyle,
    width: Dp = 1.dp,
    accentTint: Color? = null,
): BorderStroke = BorderStroke(
    width = if (glass.isGlass) width * 1.15f else width,
    brush = glassSpecularBorderBrush(glass = glass, accentTint = accentTint),
)

fun Modifier.glassPanel(
    glass: GlassStyle,
    shape: Shape,
    fillColor: Color = glass.cardFill,
    borderWidth: Dp = 1.dp,
    accentTint: Color? = null,
): Modifier = this
    .clip(shape)
    .background(fillColor, shape)
    .border(
        border = glassBorderStroke(glass = glass, width = borderWidth, accentTint = accentTint),
        shape = shape,
    )

/**
 * Minimalist circular iOS Liquid Glass toolbar icon button with a guaranteed 48.dp touch target.
 */
@Composable
fun GlassCircleIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = false,
) {
    val glass = LocalGlassStyle.current
    val fill = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        glass.pillFill
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .glassPanel(
                    glass = glass,
                    shape = CircleShape,
                    fillColor = fill,
                    borderWidth = 0.9.dp,
                    accentTint = if (active) MaterialTheme.colorScheme.primary else null,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) MaterialTheme.colorScheme.primary else tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
