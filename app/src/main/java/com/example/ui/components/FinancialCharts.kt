package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.FinancialYearReport
import com.example.data.model.GmpPoint
import com.example.ui.theme.LocalMarketColors
import com.example.ui.theme.MonoNumericStyle
import kotlin.math.max
import kotlin.math.min

@Composable
fun GmpSparklineChart(
    points: List<GmpPoint>,
    isBullish: Boolean,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val marketColors = LocalMarketColors.current
    val strokeColor = if (isBullish) marketColors.bullish else marketColors.bearish
    val gridColor = marketColors.border

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            val leftPad = 14.dp.toPx()
            val rightPad = 14.dp.toPx()
            val topPad = 16.dp.toPx()
            val bottomPad = 16.dp.toPx()
            val drawWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val drawHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            val rawMin = points.minOf { it.gmpRupees }.toFloat()
            val rawMax = points.maxOf { it.gmpRupees }.toFloat()
            val minVal = min(0f, rawMin)
            val maxVal = max(rawMax, minVal + 10f)
            val range = (maxVal - minVal).coerceAtLeast(1f)

            fun yFor(value: Float): Float {
                val normalized = (value - minVal) / range
                return topPad + drawHeight * (1f - normalized)
            }

            // Subtle horizontal guide lines
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            val zeroY = yFor(0f)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, zeroY),
                end = Offset(size.width - rightPad, zeroY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashEffect,
            )
            drawLine(
                color = gridColor.copy(alpha = 0.55f),
                start = Offset(leftPad, topPad),
                end = Offset(size.width - rightPad, topPad),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashEffect,
            )

            val stepX = if (points.size > 1) drawWidth / (points.size - 1) else 0f
            val coords = points.mapIndexed { index, point ->
                Offset(
                    x = leftPad + index * stepX,
                    y = yFor(point.gmpRupees.toFloat()),
                )
            }

            val linePath = Path()
            val fillPath = Path()

            coords.forEachIndexed { index, pt ->
                if (index == 0) {
                    linePath.moveTo(pt.x, pt.y)
                    fillPath.moveTo(pt.x, size.height - bottomPad)
                    fillPath.lineTo(pt.x, pt.y)
                } else {
                    val prev = coords[index - 1]
                    val midX = (prev.x + pt.x) / 2f
                    linePath.cubicTo(midX, prev.y, midX, pt.y, pt.x, pt.y)
                    fillPath.cubicTo(midX, prev.y, midX, pt.y, pt.x, pt.y)
                }
            }

            fillPath.lineTo(coords.last().x, size.height - bottomPad)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        strokeColor.copy(alpha = 0.28f),
                        strokeColor.copy(alpha = 0.02f),
                    ),
                    startY = topPad,
                    endY = size.height - bottomPad,
                ),
            )

            drawPath(
                path = linePath,
                color = strokeColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            coords.forEachIndexed { index, pt ->
                val isLast = index == coords.lastIndex
                if (isLast) {
                    drawCircle(
                        color = strokeColor.copy(alpha = 0.24f),
                        radius = 9.dp.toPx(),
                        center = pt,
                    )
                    drawCircle(
                        color = strokeColor,
                        radius = 4.5.dp.toPx(),
                        center = pt,
                    )
                } else {
                    drawCircle(
                        color = strokeColor,
                        radius = 2.5.dp.toPx(),
                        center = pt,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            points.forEach { point ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatRupeesSigned(point.gmpRupees),
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = point.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = marketColors.mutedText,
                    )
                }
            }
        }
    }
}

@Composable
fun FinancialBarChart(
    years: List<FinancialYearReport>,
    modifier: Modifier = Modifier,
) {
    if (years.isEmpty()) return
    val marketColors = LocalMarketColors.current
    val revenueColor = MaterialTheme.colorScheme.primary
    val ebitdaColor = marketColors.smeBadge
    val patColor = marketColors.bullish
    val gridColor = marketColors.border

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(label = "Revenue", color = revenueColor)
            LegendDot(label = "EBITDA", color = ebitdaColor)
            LegendDot(label = "PAT", color = patColor)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
        ) {
            val maxVal = years.maxOf {
                max(kotlin.math.abs(it.revenueCr), max(kotlin.math.abs(it.ebitdaCr), kotlin.math.abs(it.patCr)))
            }.coerceAtLeast(10.0).toFloat()

            val topPad = 12.dp.toPx()
            val bottomPad = 8.dp.toPx()
            val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

            // 3 horizontal gridlines
            listOf(0f, 0.5f, 1f).forEach { ratio ->
                val y = topPad + chartHeight * ratio
                drawLine(
                    color = gridColor.copy(alpha = 0.7f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (ratio == 1f) null else dashEffect,
                )
            }

            val groupWidth = size.width / years.size
            val barWidth = (groupWidth * 0.20f).coerceAtMost(22.dp.toPx())
            val barGap = (barWidth * 0.35f)
            val totalGroupBarsWidth = barWidth * 3 + barGap * 2

            years.forEachIndexed { index, report ->
                val groupStartX = index * groupWidth + (groupWidth - totalGroupBarsWidth) / 2f
                val values = listOf(
                    report.revenueCr.toFloat() to revenueColor,
                    report.ebitdaCr.toFloat() to ebitdaColor,
                    report.patCr.toFloat() to patColor,
                )
                values.forEachIndexed { barIndex, (rawValue, defaultColor) ->
                    val magnitude = kotlin.math.abs(rawValue)
                    val barColor = if (rawValue < 0f) marketColors.bearish else defaultColor
                    val barHeight = ((magnitude / maxVal) * chartHeight).coerceAtLeast(4.dp.toPx())
                    val x = groupStartX + barIndex * (barWidth + barGap)
                    val y = topPad + chartHeight - barHeight
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            years.forEach { report ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = report.fiscalYear,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "PAT ${formatCrores(report.patCr)}",
                        style = MonoNumericStyle.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        ),
                        color = if (report.patCr >= 0) patColor else marketColors.bearish,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun SubscriptionProgressBar(
    label: String,
    multiplier: Double,
    maxScale: Double,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val targetProgress = if (maxScale > 0.0) {
        (multiplier / maxScale).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 550),
        label = "sub_bar_$label",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatSubX(multiplier),
                style = MonoNumericStyle.copy(
                    fontSize = MaterialTheme.typography.titleSmall.fontSize,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (multiplier >= 1.0) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = if (multiplier > 0.0) animatedProgress.coerceAtLeast(0.03f) else 0f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(barColor.copy(alpha = 0.78f), barColor),
                        ),
                    ),
            )
        }
        if (multiplier == 0.0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Bidding opens soon",
                style = MaterialTheme.typography.bodySmall,
                color = marketColors.mutedText,
            )
        }
    }
}
