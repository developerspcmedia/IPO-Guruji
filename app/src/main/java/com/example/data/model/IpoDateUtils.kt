package com.example.data.model

import android.util.LruCache
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class IpoTimelineBucket {
    CURRENT,
    UPCOMING,
    LISTED,
}

object IpoDateUtils {
    private val IST_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private const val ONE_DAY_MS: Long = 86_400_000L
    private const val MAX_VALID_BIDDING_SPAN_DAYS: Long = 45L
    private const val FALLBACK_LISTING_WORKING_DAYS: Int = 3
    private const val PARSE_CACHE_MAX_SIZE: Int = 256

    private val PAREN_REGEX = Regex("\\([^)]*\\)")
    private val TIME_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm)?\\s*(?:ist)?\\b", RegexOption.IGNORE_CASE)
    private val WEEKDAY_REGEX = Regex("\\b(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*,?\\s+", RegexOption.IGNORE_CASE)
    private val ORDINAL_REGEX = Regex("(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val YEAR_PATTERNS: List<String> = listOf(
        "dd MMM yyyy",
        "d MMM yyyy",
        "dd MMMM yyyy",
        "d MMMM yyyy",
        "dd-MMM-yyyy",
        "d-MMM-yyyy",
        "dd-MMMM-yyyy",
        "d-MMMM-yyyy",
        "dd MMM, yyyy",
        "d MMM, yyyy",
        "dd MMMM, yyyy",
        "d MMMM, yyyy",
        "MMM dd, yyyy",
        "MMM d, yyyy",
        "MMMM dd, yyyy",
        "MMMM d, yyyy",
        "MMM dd yyyy",
        "MMM d yyyy",
        "MMMM dd yyyy",
        "MMMM d yyyy",
        "dd MMM yy",
        "d MMM yy",
        "dd-MMM-yy",
        "d-MMM-yy",
        "dd-MM-yyyy",
        "d-M-yyyy",
        "dd/MM/yyyy",
        "d/M/yyyy",
        "dd.MM.yyyy",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
    )

    private val MONTH_DAY_PATTERNS: List<String> = listOf(
        "dd MMM",
        "d MMM",
        "dd MMMM",
        "d MMMM",
        "dd-MMM",
        "d-MMM",
        "MMM dd",
        "MMM d",
        "MMMM dd",
        "MMMM d",
        "MMM-dd",
        "MMM-d",
    )

    private data class CachedParseResult(val epochMs: Long?)

    private val parseCache = LruCache<String, CachedParseResult>(PARSE_CACHE_MAX_SIZE)

    private val canonicalFormatter: ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("dd MMM yyyy", Locale.US).apply {
                    timeZone = IST_TIME_ZONE
                }
        }

    private val yearFormatters: ThreadLocal<List<SimpleDateFormat>> =
        object : ThreadLocal<List<SimpleDateFormat>>() {
            override fun initialValue(): List<SimpleDateFormat> = createFormatters(YEAR_PATTERNS)
        }

    private val monthDayFormatters: ThreadLocal<List<SimpleDateFormat>> =
        object : ThreadLocal<List<SimpleDateFormat>>() {
            override fun initialValue(): List<SimpleDateFormat> = createFormatters(MONTH_DAY_PATTERNS)
        }

    private fun createFormatters(patterns: List<String>): List<SimpleDateFormat> =
        patterns.map { pattern ->
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = IST_TIME_ZONE
            }
        }

    internal fun clearParseCacheForTest() {
        parseCache.evictAll()
    }

    internal fun parseCacheSizeForTest(): Int = parseCache.size()

    fun startOfDayMs(timeMs: Long = System.currentTimeMillis()): Long {
        return Calendar.getInstance(IST_TIME_ZONE).apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun hasExplicitYear(raw: String): Boolean {
        val cleaned = raw.trim()
        return Regex("\\b20\\d{2}\\b").containsMatchIn(cleaned) ||
            Regex("(?:[-/\\s])\\d{2}\\b$").containsMatchIn(cleaned)
    }

    fun formatCanonicalDate(timeMs: Long): String {
        val sdf = canonicalFormatter.get() ?: SimpleDateFormat("dd MMM yyyy", Locale.US).apply {
            timeZone = IST_TIME_ZONE
        }
        return sdf.format(Date(timeMs))
    }

    /**
     * Parses a raw date string and normalizes it into canonical "dd MMM yyyy" (e.g. "24 Nov 2026").
     * Returns an empty string if the input is not a valid calendar date.
     */
    fun normalizeDateString(
        raw: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): String {
        val parsedMs = parseFlexibleDateMs(raw, referenceTimeMs) ?: return ""
        return formatCanonicalDate(parsedMs)
    }

    private fun cleanRawDateCandidate(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.isBlank()) return ""
        // Strip parenthetical weekday notes like "(Thu)" or "(Tentative)" and trailing time stamps like "10:00 AM IST"
        cleaned = cleaned
            .replace(PAREN_REGEX, " ")
            .replace(TIME_REGEX, " ")
            .replace(WEEKDAY_REGEX, " ")
            .replace(ORDINAL_REGEX, "$1")
            .replace(",", ", ")
            .replace(WHITESPACE_REGEX, " ")
            .trim(' ', ',', '-', ':', '|', '.')
        return cleaned
    }

    fun parseFlexibleDateMs(
        raw: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Long? {
        val cleaned = cleanRawDateCandidate(raw)
        if (cleaned.isBlank()) return null
        // Reject currency, percentages, multipliers, or bare numbers that are not calendar dates
        if (cleaned.contains('₹') || cleaned.contains('%') || cleaned.all { it.isDigit() }) {
            return null
        }

        val refDayMs = startOfDayMs(referenceTimeMs)
        val cacheKey = "$cleaned|$refDayMs"
        parseCache.get(cacheKey)?.let { cached ->
            return cached.epochMs
        }

        val parsedMs = parseCleanedDateMs(cleaned, refDayMs)
        parseCache.put(cacheKey, CachedParseResult(parsedMs))
        return parsedMs
    }

    private fun parseCleanedDateMs(cleaned: String, refDayMs: Long): Long? {
        val refCal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = refDayMs }
        val currentYear = refCal.get(Calendar.YEAR)
        val currentMonth = refCal.get(Calendar.MONTH)

        val yearFormatList = yearFormatters.get() ?: createFormatters(YEAR_PATTERNS)
        for (formatter in yearFormatList) {
            val pos = ParsePosition(0)
            val parsed = runCatching {
                formatter.parse(cleaned, pos)
            }.getOrNull()
            if (parsed != null && pos.index == cleaned.length) {
                val cal = Calendar.getInstance(IST_TIME_ZONE).apply {
                    time = parsed
                    val yr = get(Calendar.YEAR)
                    if (yr < 100) set(Calendar.YEAR, 2000 + yr)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val yr = cal.get(Calendar.YEAR)
                if (yr in 2015..2035) {
                    return cal.timeInMillis
                }
            }
        }

        val monthDayFormatList = monthDayFormatters.get() ?: createFormatters(MONTH_DAY_PATTERNS)
        for (formatter in monthDayFormatList) {
            val pos = ParsePosition(0)
            val parsed = runCatching {
                formatter.parse(cleaned, pos)
            }.getOrNull()
            if (parsed != null && pos.index == cleaned.length) {
                val cal = Calendar.getInstance(IST_TIME_ZONE).apply {
                    time = parsed
                    val parsedMonth = get(Calendar.MONTH)
                    val resolvedYear = when {
                        parsedMonth - currentMonth > 6 -> currentYear - 1
                        currentMonth - parsedMonth > 6 -> currentYear + 1
                        else -> currentYear
                    }
                    set(Calendar.YEAR, resolvedYear)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return cal.timeInMillis
            }
        }

        return null
    }

    /**
     * Intelligently parses an IPO date cell that may hold either a single date ("12-Nov-2026")
     * or a date range ("12-14 Nov 2026", "Nov 12–14, 2026", "28 Nov - 2 Dec 2026",
     * "12-Nov-2026 to 14-Nov-2026").
     *
     * Never breaks hyphenated single dates and never fabricates closeDate == openDate when
     * only a single date is provided.
     */
    fun parseDateRange(
        raw: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Pair<String, String> {
        val cleaned = raw.trim()
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return "" to ""

        // 1. If the entire string is already a valid single date (e.g. "12-Nov-2026", "2026-11-12"),
        // do NOT split on '-' and do NOT fabricate a closeDate.
        val singleMs = parseFlexibleDateMs(cleaned, referenceTimeMs)
        if (singleMs != null) {
            return formatCanonicalDate(singleMs) to ""
        }

        val normalizedInput = cleaned
            .replace(Regex("(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE), "$1")

        // 2. Compact same-month day range: "12-14 Nov 2026", "12 to 14 Nov, 2026", "12–14 Nov"
        val dayFirstCompact = Regex(
            "^(\\d{1,2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})\\s+([A-Za-z]{3,9})(?:[,\\s]+(\\d{2,4}))?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(normalizedInput)
        if (dayFirstCompact != null) {
            val d1 = dayFirstCompact.groupValues[1]
            val d2 = dayFirstCompact.groupValues[2]
            val month = dayFirstCompact.groupValues[3]
            val year = dayFirstCompact.groupValues[4].trim()
            val suffix = if (year.isNotBlank()) " $month $year" else " $month"
            val openMs = parseFlexibleDateMs("$d1$suffix", referenceTimeMs)
            val closeMs = parseFlexibleDateMs("$d2$suffix", referenceTimeMs)
            if (openMs != null && closeMs != null && isValidBiddingWindow(openMs, closeMs)) {
                return formatCanonicalDate(openMs) to formatCanonicalDate(closeMs)
            }
        }

        // 3. Compact month-first day range: "Nov 12-14, 2026", "November 12 to 14 2026", "Nov 12–14"
        val monthFirstCompact = Regex(
            "^([A-Za-z]{3,9})\\s+(\\d{1,2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})(?:[,\\s]+(\\d{2,4}))?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(normalizedInput)
        if (monthFirstCompact != null) {
            val month = monthFirstCompact.groupValues[1]
            val d1 = monthFirstCompact.groupValues[2]
            val d2 = monthFirstCompact.groupValues[3]
            val year = monthFirstCompact.groupValues[4].trim()
            val yearSuffix = if (year.isNotBlank()) " $year" else ""
            val openMs = parseFlexibleDateMs("$d1 $month$yearSuffix", referenceTimeMs)
            val closeMs = parseFlexibleDateMs("$d2 $month$yearSuffix", referenceTimeMs)
            if (openMs != null && closeMs != null && isValidBiddingWindow(openMs, closeMs)) {
                return formatCanonicalDate(openMs) to formatCanonicalDate(closeMs)
            }
        }

        // 4. Explicit range separators that preserve internal hyphens inside "12-Nov-2026":
        // Try " to ", en/em dash, spaced " - ", and finally hyphen between two month-bearing date tokens.
        val candidateSplits: List<List<String>> = listOf(
            normalizedInput.split(Regex("\\s+to\\s+", RegexOption.IGNORE_CASE)),
            normalizedInput.split(Regex("\\s*[–—]\\s*")),
            normalizedInput.split(Regex("\\s+-\\s+")),
            // e.g. "28 Nov-2 Dec 2026" or "12 Nov 2026-14 Nov 2026" (never splits "12-Nov-2026")
            Regex("^(.+?[A-Za-z]{3,9}(?:[,\\s]+\\d{2,4})?)\\s*-\\s*(\\d{1,2}\\s+[A-Za-z]{3,9}.*|[A-Za-z]{3,9}\\s+\\d{1,2}.*)$", RegexOption.IGNORE_CASE)
                .matchEntire(normalizedInput)
                ?.let { listOf(it.groupValues[1], it.groupValues[2]) }
                ?: emptyList(),
        )

        for (parts in candidateSplits) {
            if (parts.size != 2) continue
            val leftRaw = parts[0].trim()
            val rightRaw = parts[1].trim()
            if (leftRaw.isBlank() || rightRaw.isBlank()) continue

            val closeMs = parseFlexibleDateMs(rightRaw, referenceTimeMs) ?: continue
            var openMs = parseFlexibleDateMs(leftRaw, referenceTimeMs)

            // If right side carried an explicit year (e.g. "28 Nov - 2 Dec 2026") and left side did not,
            // align left side to right side's year (handling Dec -> Jan year rollover cleanly).
            if (hasExplicitYear(rightRaw) && !hasExplicitYear(leftRaw)) {
                val closeCal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = closeMs }
                val closeYear = closeCal.get(Calendar.YEAR)
                val closeMonth = closeCal.get(Calendar.MONTH)
                val leftWithCloseYear = parseFlexibleDateMs("$leftRaw $closeYear", referenceTimeMs)
                if (leftWithCloseYear != null) {
                    val leftCal = Calendar.getInstance(IST_TIME_ZONE).apply { timeInMillis = leftWithCloseYear }
                    val leftMonth = leftCal.get(Calendar.MONTH)
                    openMs = if (leftMonth > closeMonth && (leftMonth - closeMonth) >= 6) {
                        parseFlexibleDateMs("$leftRaw ${closeYear - 1}", referenceTimeMs) ?: leftWithCloseYear
                    } else {
                        leftWithCloseYear
                    }
                }
            }

            if (openMs != null && isValidBiddingWindow(openMs, closeMs)) {
                return formatCanonicalDate(openMs) to formatCanonicalDate(closeMs)
            }
        }

        return "" to ""
    }

    fun isValidBiddingWindow(openMs: Long, closeMs: Long): Boolean {
        if (closeMs < openMs) return false
        val spanDays = (closeMs - openMs) / ONE_DAY_MS
        return spanDays in 0L..MAX_VALID_BIDDING_SPAN_DAYS
    }

    fun isCloseDatePassed(
        closeDate: String,
        status: IpoStatus,
        referenceTimeMs: Long = System.currentTimeMillis(),
        listingDate: String = "",
    ): Boolean {
        val today = startOfDayMs(referenceTimeMs)
        val closeMs = parseFlexibleDateMs(closeDate, referenceTimeMs)
        if (closeMs != null) {
            return closeMs < today
        }
        val listMs = parseFlexibleDateMs(listingDate, referenceTimeMs)
        if (listMs != null) {
            return listMs <= today
        }
        return status == IpoStatus.CLOSED
    }

    private fun addWorkingDaysIst(startMs: Long, workingDays: Int): Long {
        val cal = Calendar.getInstance(IST_TIME_ZONE).apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var remaining = workingDays
        while (remaining > 0) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY) {
                remaining--
            }
        }
        return cal.timeInMillis
    }

    /**
     * Returns true ONLY when the IPO's listing date has arrived (listingDate <= today),
     * or when listingDate is missing and today is AFTER the expected T+3 working-day listing date
     * (skipping Saturday and Sunday in IST).
     */
    fun isListingDateArrived(
        listingDate: String,
        closeDate: String,
        status: IpoStatus,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val today = startOfDayMs(referenceTimeMs)
        val listMs = parseFlexibleDateMs(listingDate, referenceTimeMs)
        if (listMs != null) {
            return listMs <= today
        }
        val closeMs = parseFlexibleDateMs(closeDate, referenceTimeMs)
        if (closeMs != null) {
            val expectedListingMs = addWorkingDaysIst(closeMs, FALLBACK_LISTING_WORKING_DAYS)
            return today > expectedListingMs
        }
        return status == IpoStatus.CLOSED
    }

    /**
     * Returns true when an IPO has finished bidding (confirmed closeDate < today)
     * while its listing date has not arrived yet (Allotment / Pre-Listing waiting phase).
     */
    fun isInAllotmentPhase(
        closeDate: String,
        listingDate: String,
        status: IpoStatus,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (isListingDateArrived(
                listingDate = listingDate,
                closeDate = closeDate,
                status = status,
                referenceTimeMs = referenceTimeMs,
            )
        ) {
            return false
        }
        val today = startOfDayMs(referenceTimeMs)
        val closeMs = parseFlexibleDateMs(closeDate, referenceTimeMs)
        return closeMs != null && closeMs < today
    }

    /**
     * Returns true ONLY when an IPO has confirmed Open & Close dates and today falls
     * within the active bidding window (`openDate <= today <= closeDate`).
     */
    fun isBiddingOpenToday(
        openDate: String,
        closeDate: String,
        listingDate: String,
        status: IpoStatus,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (isListingDateArrived(listingDate, closeDate, status, referenceTimeMs)) return false
        if (isInAllotmentPhase(closeDate, listingDate, status, referenceTimeMs)) return false
        val today = startOfDayMs(referenceTimeMs)
        val openMs = parseFlexibleDateMs(openDate, referenceTimeMs) ?: return false
        val closeMs = parseFlexibleDateMs(closeDate, referenceTimeMs) ?: return false
        if (!isValidBiddingWindow(openMs, closeMs)) return false
        return openMs <= today && today <= closeMs
    }

    /**
     * Classifies every IPO into one of the three home tabs using strict date-first truth (IST):
     * 1. [IpoTimelineBucket.LISTED]: Listing date has arrived (`listingDate <= today`), or when
     *    listingDate is missing, today is after the expected T+3 working-day listing date.
     * 2. [IpoTimelineBucket.CURRENT]: ONLY IPOs with confirmed valid dates that are either:
     *    - Currently open for bidding today (`openDate <= today <= closeDate`), OR
     *    - Recently closed awaiting listing (`closeDate < today < listingDate` or through the T+3 working-day fallback).
     * 3. [IpoTimelineBucket.UPCOMING]: Future IPOs (`openDate > today`) AND any IPO with missing,
     *    TBA, malformed, or unverified Open/Close dates (NEVER allowed into Current).
     */
    fun classifyTimelineTab(
        openDate: String,
        closeDate: String,
        listingDate: String,
        status: IpoStatus,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): IpoTimelineBucket {
        if (isListingDateArrived(
                listingDate = listingDate,
                closeDate = closeDate,
                status = status,
                referenceTimeMs = referenceTimeMs,
            )
        ) {
            return IpoTimelineBucket.LISTED
        }

        val today = startOfDayMs(referenceTimeMs)
        val openMs = parseFlexibleDateMs(openDate, referenceTimeMs)
        val closeMs = parseFlexibleDateMs(closeDate, referenceTimeMs)

        // Missing or unverified Open/Close dates -> NEVER enter Current!
        if (openMs == null && closeMs == null) {
            return IpoTimelineBucket.UPCOMING
        }
        if (openMs != null && openMs > today) {
            return IpoTimelineBucket.UPCOMING
        }
        if (openMs == null && closeMs != null) {
            // Only recently closed issues awaiting listing can enter Current without openDate
            return if (closeMs < today) IpoTimelineBucket.CURRENT else IpoTimelineBucket.UPCOMING
        }
        if (openMs != null && closeMs == null) {
            // Unconfirmed Close Date -> keep in Upcoming unless openDate is clearly stale in the past
            return if (today - openMs > (7L * ONE_DAY_MS)) {
                IpoTimelineBucket.LISTED
            } else {
                IpoTimelineBucket.UPCOMING
            }
        }

        // Both openMs and closeMs are confirmed non-null
        val confirmedOpen = openMs!!
        val confirmedClose = closeMs!!
        if (!isValidBiddingWindow(confirmedOpen, confirmedClose)) {
            return IpoTimelineBucket.UPCOMING
        }

        return when {
            confirmedOpen <= today && today <= confirmedClose -> IpoTimelineBucket.CURRENT
            confirmedClose < today -> IpoTimelineBucket.CURRENT // Allotment phase (step 1 ensured not listed yet)
            else -> IpoTimelineBucket.UPCOMING
        }
    }
}
