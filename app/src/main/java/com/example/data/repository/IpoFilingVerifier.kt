package com.example.data.repository

import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoStatus
import java.util.Locale

enum class FilingRejectionReason(val label: String) {
    SINGLE_SOURCE_ONLY("Not verified across at least 2 independent sources"),
    UNFILED_OR_RUMORED("Marked as unfiled, rumored, withdrawn, or draft-only"),
    UNCONFIRMED_LOT_SIZE("Lot size is not confirmed"),
    UNCONFIRMED_ISSUE_SIZE("Issue size is not confirmed"),
    UNCONFIRMED_PRICE_BAND("Offer price band is not confirmed"),
    UNCONFIRMED_SCHEDULE("IPO filing schedule dates are not confirmed"),
    INVALID_COMPANY_NAME("Company name is missing or invalid"),
}

data class FilingVerificationResult(
    val isVerified: Boolean,
    val verifiedProviders: Set<String>,
    val rejectionReasons: List<FilingRejectionReason>,
)

/**
 * Cross-verifies IPO filings across multiple independent sources and filters out
 * any unfiled, rumored, or unconfirmed IPOs (missing lot size, issue size, offer price, or schedule).
 */
object IpoFilingVerifier {

    const val MIN_VERIFIED_SOURCES = 2

    private val OFFICIAL_EXCHANGE_PROVIDERS = setOf(
        "NSE India",
        "BSE India",
    )

    private val NON_FILING_NEWS_HINTS = listOf(
        "google news",
        "rss",
        "twitter",
        "reddit",
        "blog",
    )

    private val UNFILED_OR_RUMORED_MARKERS = listOf(
        "rumor",
        "rumour",
        "speculated",
        "tentative",
        "proposed",
        "drhp only",
        "draft only",
        "drhp awaited",
        "pre-ipo",
        "pre ipo",
        "unlisted",
        "not filed",
        "withdrawn",
        "cancelled",
        "canceled",
        "deferred",
        "postponed",
        "planning ipo",
    )

    private val PLACEHOLDER_VALUE_MARKERS = setOf(
        "",
        "-",
        "--",
        "---",
        "na",
        "n/a",
        "nil",
        "tba",
        "tbc",
        "tbd",
        "soon",
        "awaited",
        "expected",
        "upcoming",
        "to be announced",
        "to be decided",
        "not announced",
        "not declared",
        "not confirmed",
        "live schedule",
        "check rhp",
        "closed",
    )

    /**
     * Maps sub-endpoints on the same domain to their canonical independent provider family,
     * so two pages from the same website never count as two separate verification sources.
     */
    fun providerFamily(rawSourceName: String): String {
        val cleaned = rawSourceName.trim()
        if (cleaned.isBlank()) return ""
        val lower = cleaned.lowercase(Locale.US)
        if (NON_FILING_NEWS_HINTS.any { lower.contains(it) }) return ""
        return when {
            lower == "nse" || lower.startsWith("nse ") || lower.contains("nseindia") -> "NSE India"
            lower == "bse" || lower.startsWith("bse ") || lower.contains("bseindia") -> "BSE India"
            lower.contains("investorgain") -> "InvestorGain"
            lower.contains("ipowatch") -> "IPOWatch"
            lower.contains("chittorgarh") -> "Chittorgarh"
            lower.contains("ipo central") || lower.contains("ipocentral") -> "IPO Central"
            lower.contains("ipoji") -> "IPOJi"
            lower.contains("sebi") -> "SEBI"
            else -> cleaned
        }
    }

    fun isOfficialExchangeProvider(provider: String): Boolean =
        providerFamily(provider) in OFFICIAL_EXCHANGE_PROVIDERS

    fun extractVerifiedProvidersFromLabel(sourceLabel: String): Set<String> {
        if (sourceLabel.isBlank()) return emptySet()
        return sourceLabel
            .replace("Verified •", "", ignoreCase = true)
            .replace("Live •", "", ignoreCase = true)
            .split("•", ",", "|", "+")
            .mapNotNull { token ->
                val family = providerFamily(token)
                family.takeIf { it.isNotBlank() }
            }
            .toCollection(LinkedHashSet())
    }

    fun sanitizeConfirmedDate(raw: String): String {
        val cleaned = raw.trim()
        val lower = cleaned.lowercase(Locale.US)
        if (lower in PLACEHOLDER_VALUE_MARKERS) return ""
        if (lower.contains("tba") || lower.contains("tbc") || lower.contains("expected") || lower.contains("awaited")) {
            return ""
        }
        return cleaned
    }

    fun isConfirmedDate(raw: String, referenceTimeMs: Long = System.currentTimeMillis()): Boolean {
        val cleaned = sanitizeConfirmedDate(raw)
        if (cleaned.isBlank()) return false
        return IpoDateUtils.parseFlexibleDateMs(cleaned, referenceTimeMs) != null
    }

    fun containsUnfiledOrRumoredMarkers(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase(Locale.US)
        return UNFILED_OR_RUMORED_MARKERS.any { lower.contains(it) }
    }

    fun isHardRejectedStatus(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase(Locale.US)
        return lower.contains("withdrawn") ||
            lower.contains("cancelled") ||
            lower.contains("canceled") ||
            lower.contains("deferred") ||
            lower.contains("postponed") ||
            lower.contains("not filed") ||
            lower.contains("rumor") ||
            lower.contains("rumour") ||
            lower.contains("speculated")
    }

    fun verifyQuote(
        quote: LiveGmpQuote,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): FilingVerificationResult {
        val reasons = mutableListOf<FilingRejectionReason>()

        val normalizedProviders = (quote.verifiedProviders + extractVerifiedProvidersFromLabel(quote.sourceName))
            .mapNotNull { providerFamily(it).takeIf { f -> f.isNotBlank() } }
            .toCollection(LinkedHashSet())

        if (normalizedProviders.size < MIN_VERIFIED_SOURCES) {
            reasons += FilingRejectionReason.SINGLE_SOURCE_ONLY
        }

        val cleanName = quote.companyName.trim()
        if (cleanName.length < 3 || cleanName.lowercase(Locale.US) in PLACEHOLDER_VALUE_MARKERS) {
            reasons += FilingRejectionReason.INVALID_COMPANY_NAME
        }

        if (quote.isUnconfirmedOrRumored || containsUnfiledOrRumoredMarkers(cleanName)) {
            reasons += FilingRejectionReason.UNFILED_OR_RUMORED
        }

        if (quote.lotSize <= 0) {
            reasons += FilingRejectionReason.UNCONFIRMED_LOT_SIZE
        }

        if (quote.issueSizeCr <= 0.0) {
            reasons += FilingRejectionReason.UNCONFIRMED_ISSUE_SIZE
        }

        if (quote.priceLow <= 0 || quote.priceHigh <= 0 || quote.priceHigh < quote.priceLow) {
            reasons += FilingRejectionReason.UNCONFIRMED_PRICE_BAND
        }

        val effectiveStatus = quote.status ?: IpoStatus.PRE_APPLY
        val isClosedOrListed = effectiveStatus == IpoStatus.CLOSED ||
            IpoDateUtils.isCloseDatePassed(
                closeDate = quote.closeDate,
                status = effectiveStatus,
                listingDate = quote.listingDate,
                referenceTimeMs = referenceTimeMs,
            )

        val hasValidSchedule = if (isClosedOrListed) {
            isConfirmedDate(quote.listingDate, referenceTimeMs) ||
                isConfirmedDate(quote.closeDate, referenceTimeMs) ||
                isConfirmedDate(quote.openDate, referenceTimeMs)
        } else {
            isConfirmedDate(quote.openDate, referenceTimeMs) &&
                isConfirmedDate(quote.closeDate, referenceTimeMs)
        }

        if (!hasValidSchedule) {
            reasons += FilingRejectionReason.UNCONFIRMED_SCHEDULE
        }

        return FilingVerificationResult(
            isVerified = reasons.isEmpty(),
            verifiedProviders = normalizedProviders,
            rejectionReasons = reasons,
        )
    }

    /**
     * Final safety gate on persisted or displayed [IpoEntity] records so no IPO
     * with unconfirmed lot size, issue size, offer price, or unconfirmed filing dates
     * is ever displayed in the list.
     */
    fun isVerifiedFiledEntity(
        entity: IpoEntity,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val cleanName = entity.companyName.trim()
        if (cleanName.length < 3 || containsUnfiledOrRumoredMarkers(cleanName)) return false
        if (entity.lotSize <= 0) return false
        if (entity.issueSizeCr <= 0.0) return false
        if (entity.priceLow <= 0 || entity.priceHigh <= 0 || entity.priceHigh < entity.priceLow) return false

        val isClosedOrListed = entity.status == IpoStatus.CLOSED ||
            IpoDateUtils.isCloseDatePassed(
                closeDate = entity.closeDate,
                status = entity.status,
                listingDate = entity.listingDate,
                referenceTimeMs = referenceTimeMs,
            )

        return if (isClosedOrListed) {
            isConfirmedDate(entity.listingDate, referenceTimeMs) ||
                isConfirmedDate(entity.closeDate, referenceTimeMs) ||
                isConfirmedDate(entity.openDate, referenceTimeMs)
        } else {
            isConfirmedDate(entity.openDate, referenceTimeMs) &&
                isConfirmedDate(entity.closeDate, referenceTimeMs)
        }
    }

    fun formatVerifiedSourcesLabel(providers: Set<String>): String {
        val ordered = providers
            .mapNotNull { providerFamily(it).takeIf { f -> f.isNotBlank() } }
            .distinct()
            .sortedBy { providerSortOrder(it) }
        return if (ordered.isEmpty()) {
            "Multi-Source Verified"
        } else {
            ordered.joinToString(" • ")
        }
    }

    private fun providerSortOrder(provider: String): Int = when (provider) {
        "NSE India" -> 0
        "BSE India" -> 1
        "SEBI" -> 2
        "InvestorGain" -> 3
        "Chittorgarh" -> 4
        "IPOWatch" -> 5
        "IPO Central" -> 6
        "IPOJi" -> 7
        else -> 10
    }
}
