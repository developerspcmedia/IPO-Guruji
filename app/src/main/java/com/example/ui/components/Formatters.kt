package com.example.ui.components

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

private val indianLocale = Locale("en", "IN")

fun formatRupees(amount: Int): String {
    val formatter = NumberFormat.getNumberInstance(indianLocale)
    return "₹${formatter.format(amount)}"
}

fun formatCount(count: Int): String {
    val formatter = NumberFormat.getNumberInstance(indianLocale)
    return formatter.format(count)
}

fun formatLongCount(count: Long): String {
    val formatter = NumberFormat.getNumberInstance(indianLocale)
    return formatter.format(count)
}

fun formatRupeesSigned(amount: Int): String {
    val formatter = NumberFormat.getNumberInstance(indianLocale)
    return when {
        amount > 0 -> "+₹${formatter.format(amount)}"
        amount < 0 -> "-₹${formatter.format(abs(amount))}"
        else -> "₹0"
    }
}

fun formatPercentSigned(percent: Double): String {
    val formatted = String.format(Locale.US, "%.1f", abs(percent))
    return when {
        percent > 0.05 -> "+$formatted%"
        percent < -0.05 -> "-$formatted%"
        else -> "0.0%"
    }
}

fun formatCrores(amountCr: Double): String {
    return if (amountCr >= 1000.0) {
        val formatter = NumberFormat.getNumberInstance(indianLocale).apply {
            maximumFractionDigits = 0
        }
        "₹${formatter.format(amountCr)} Cr"
    } else {
        "₹${String.format(Locale.US, "%.1f", amountCr)} Cr"
    }
}

fun formatSubX(subX: Double): String {
    return if (subX <= 0.0) "—" else "${String.format(Locale.US, "%.2f", subX)}x"
}

fun formatCompactApps(count: Long): String {
    return when {
        count <= 0L -> "—"
        count >= 10_000_000L -> "${String.format(Locale.US, "%.2f", count / 10_000_000.0)}Cr"
        count >= 100_000L -> "${String.format(Locale.US, "%.2f", count / 100_000.0)}L"
        count >= 1_000L -> "${String.format(Locale.US, "%.1f", count / 1_000.0)}K"
        else -> NumberFormat.getNumberInstance(indianLocale).format(count)
    }
}

fun formatAllotmentOdds(subX: Double): String {
    return when {
        subX <= 0.0 -> "—"
        subX <= 1.0 -> "Confirmed"
        else -> {
            val denom = kotlin.math.ceil(subX).toLong().coerceAtLeast(2L)
            "1 in ${formatCompactApps(denom)}"
        }
    }
}
