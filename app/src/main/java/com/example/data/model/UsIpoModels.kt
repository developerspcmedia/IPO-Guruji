package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
enum class UsIpoStatus(val label: String) {
    UPCOMING("Upcoming"),
    PRICED("Priced"),
    FILED("Filed"),
}

@Entity(tableName = "us_ipos")
data class UsIpoEntity(
    @PrimaryKey val dealId: String,
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val status: UsIpoStatus,
    val priceRange: String,
    val sharesOffered: String,
    val dealSizeUsd: String,
    val dealSizeNumericUsd: Double,
    val eventDate: String,
    val eventDateEpochMs: Long,
    val secFormType: String,
    val secFilingUrl: String,
    val nasdaqUrl: String,
    val sourceLabel: String,
    val syncedAtMs: Long,
) {
    val displayTicker: String
        get() = symbol.trim().ifBlank { "TBD" }

    val compactDealSize: String
        get() = UsIpoFormatters.formatCompactUsd(dealSizeNumericUsd, dealSizeUsd)

    val displayPrice: String
        get() = priceRange.trim().ifBlank { "TBD" }

    val displayShares: String
        get() = sharesOffered.trim().ifBlank { "TBD" }

    val displayEventDate: String
        get() = eventDate.trim().ifBlank { "TBA" }

    val dateLabel: String
        get() = when (status) {
            UsIpoStatus.UPCOMING -> "Expected Date"
            UsIpoStatus.PRICED -> "Priced Date"
            UsIpoStatus.FILED -> "Filed Date"
        }

    val shortDateLabel: String
        get() = when (status) {
            UsIpoStatus.UPCOMING -> "Expected"
            UsIpoStatus.PRICED -> "Priced"
            UsIpoStatus.FILED -> "Filed"
        }

    val hasDirectSecArchiveUrl: Boolean
        get() = secFilingUrl.contains("sec.gov/Archives/edgar/data/", ignoreCase = true)

    val resolvedSecFilingUrl: String
        get() = secFilingUrl.ifBlank { UsIpoFormatters.buildSecEdgarSearchUrl(companyName, symbol) }

    val resolvedNasdaqUrl: String
        get() = nasdaqUrl.ifBlank { UsIpoFormatters.buildNasdaqIpoUrl(symbol) }
}

object UsIpoFormatters {
    private val parsePatterns = listOf(
        "MM/dd/yyyy",
        "M/d/yyyy",
        "yyyy-MM-dd",
        "MMM d, yyyy",
        "MMM dd, yyyy",
        "dd MMM yyyy",
    )

    fun parseUsdAmount(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0
        val cleaned = raw.trim().uppercase(Locale.US)
            .replace("$", "")
            .replace(",", "")
            .replace("USD", "")
            .trim()
        if (cleaned.isBlank() || cleaned == "N/A" || cleaned == "TBD" || cleaned == "-") return 0.0

        val multiplier = when {
            cleaned.endsWith("B") -> 1_000_000_000.0
            cleaned.endsWith("M") -> 1_000_000.0
            cleaned.endsWith("K") -> 1_000.0
            else -> 1.0
        }
        val numericPart = cleaned.trimEnd('B', 'M', 'K', ' ')
        return (numericPart.toDoubleOrNull() ?: 0.0) * multiplier
    }

    fun formatCompactUsd(amount: Double, fallbackRaw: String = ""): String {
        if (amount <= 0.0) {
            val clean = fallbackRaw.trim()
            return if (clean.isBlank() || clean.equals("N/A", ignoreCase = true) || clean == "$0") {
                "TBD"
            } else {
                clean
            }
        }
        return when {
            amount >= 1_000_000_000.0 -> String.format(Locale.US, "$%.2fB", amount / 1_000_000_000.0)
            amount >= 1_000_000.0 -> {
                val millions = amount / 1_000_000.0
                if (millions >= 100.0) {
                    String.format(Locale.US, "$%.0fM", millions)
                } else {
                    String.format(Locale.US, "$%.1fM", millions)
                }
            }
            amount >= 1_000.0 -> String.format(Locale.US, "$%.0fK", amount / 1_000.0)
            else -> String.format(Locale.US, "$%.0f", amount)
        }
    }

    fun normalizeExchange(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return "US Market"
        val upper = value.uppercase(Locale.US)
        return when {
            upper.contains("NASDAQ") -> "NASDAQ"
            upper.contains("NYSE AMERICAN") || upper.contains("AMEX") -> "NYSE American"
            upper.contains("NYSE ARCA") -> "NYSE Arca"
            upper.contains("NYSE") || upper.contains("NEW YORK") -> "NYSE"
            upper.contains("CBOE") || upper.contains("BATS") -> "Cboe"
            else -> value
        }
    }

    fun normalizePriceRange(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("N/A", ignoreCase = true) || trimmed == "-") return "TBD"
        return if (trimmed.startsWith("$")) trimmed else "$$trimmed"
    }

    fun parseEventDateEpochMs(raw: String?): Long {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isBlank() || cleaned.equals("TBD", ignoreCase = true) || cleaned.equals("N/A", ignoreCase = true)) {
            return 0L
        }
        for (pattern in parsePatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(cleaned)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
                // Try next pattern
            }
        }
        return 0L
    }

    fun formatDisplayDate(raw: String?, epochMs: Long): String {
        if (epochMs > 0L) {
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(java.util.Date(epochMs))
        }
        val cleaned = raw?.trim().orEmpty()
        return if (cleaned.isBlank() || cleaned.equals("N/A", ignoreCase = true)) "TBA" else cleaned
    }

    fun buildSecEdgarSearchUrl(companyName: String, symbol: String = ""): String {
        val cleanedName = companyName
            .replace(Regex("\\s+(Inc\\.?|Corp\\.?|Corporation|Ltd\\.?|Limited|PLC|LLC|LP|Holdings|Group)$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { symbol.trim() }
            .ifBlank { "IPO" }
        val encoded = URLEncoder.encode(cleanedName, StandardCharsets.UTF_8.name())
        return "https://www.sec.gov/edgar/search/#/q=$encoded&forms=S-1,F-1"
    }

    fun buildSecDirectFilingUrl(cikRaw: String?, accessionNoRaw: String?, primaryFileName: String? = null): String {
        val cikDigits = cikRaw?.filter { it.isDigit() }?.trimStart('0').orEmpty()
        val accessionClean = accessionNoRaw?.trim().orEmpty()
        val accessionNoDashes = accessionClean.replace("-", "")
        if (cikDigits.isBlank() || accessionNoDashes.isBlank()) return ""
        val fileName = primaryFileName?.trim().orEmpty()
        return if (fileName.isNotBlank() && !fileName.contains("/")) {
            "https://www.sec.gov/Archives/edgar/data/$cikDigits/$accessionNoDashes/$fileName"
        } else {
            "https://www.sec.gov/Archives/edgar/data/$cikDigits/$accessionNoDashes/$accessionClean-index.htm"
        }
    }

    fun buildNasdaqIpoUrl(symbol: String): String {
        val cleanSymbol = symbol.trim().uppercase(Locale.US)
        return if (cleanSymbol.isNotBlank() && cleanSymbol != "TBD" && cleanSymbol.all { it.isLetterOrDigit() }) {
            "https://www.nasdaq.com/market-activity/stocks/${cleanSymbol.lowercase(Locale.US)}"
        } else {
            "https://www.nasdaq.com/market-activity/ipos"
        }
    }
}
