package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object IpoDateUtils {
    fun startOfDayMs(timeMs: Long = System.currentTimeMillis()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun parseFlexibleDateMs(
        raw: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Long? {
        val cleaned = raw.trim()
            .replace(Regex("(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE), "$1")
            .replace(",", ", ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return null

        val refCal = Calendar.getInstance().apply { timeInMillis = referenceTimeMs }
        val currentYear = refCal.get(Calendar.YEAR)
        val currentMonth = refCal.get(Calendar.MONTH)

        val yearPatterns = listOf(
            "dd MMM yyyy",
            "d MMM yyyy",
            "dd-MMM-yyyy",
            "d-MMM-yyyy",
            "dd MMM, yyyy",
            "d MMM, yyyy",
            "MMM dd, yyyy",
            "MMM d, yyyy",
            "MMM dd yyyy",
            "MMM d yyyy",
            "dd MMM yy",
            "d MMM yy",
            "dd-MMM-yy",
            "d-MMM-yy",
            "dd-MM-yyyy",
            "dd/MM/yyyy",
            "yyyy-MM-dd",
        )
        for (pattern in yearPatterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(cleaned)
            }.getOrNull()
            if (parsed != null) {
                val cal = Calendar.getInstance().apply {
                    time = parsed
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return cal.timeInMillis
            }
        }

        val monthDayPatterns = listOf("dd MMM", "d MMM", "dd-MMM", "d-MMM", "MMM dd", "MMM d", "MMM-dd", "MMM-d")
        for (pattern in monthDayPatterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(cleaned)
            }.getOrNull()
            if (parsed != null) {
                val cal = Calendar.getInstance().apply {
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

    fun isCloseDatePassed(
        closeDate: String,
        status: IpoStatus,
        referenceTimeMs: Long = System.currentTimeMillis(),
        listingDate: String = "",
    ): Boolean {
        if (status == IpoStatus.CLOSED) return true
        val today = startOfDayMs(referenceTimeMs)
        val closeMs = parseFlexibleDateMs(closeDate, referenceTimeMs)
        if (closeMs != null) {
            return closeMs < today
        }
        val listMs = parseFlexibleDateMs(listingDate, referenceTimeMs)
        if (listMs != null) {
            return listMs <= today
        }
        return false
    }
}
