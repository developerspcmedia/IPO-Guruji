package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.AllotmentWatchStatus
import com.example.data.model.BoardType
import com.example.data.model.DaySubscription
import com.example.data.model.FinancialYearReport
import com.example.data.model.GmpPoint
import com.example.data.model.IpoStatus
import com.example.data.model.ValuationMetrics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class IpoTypeConverters {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromBoardType(value: BoardType): String = value.name

    @TypeConverter
    fun toBoardType(value: String): BoardType = BoardType.valueOf(value)

    @TypeConverter
    fun fromIpoStatus(value: IpoStatus): String = value.name

    @TypeConverter
    fun toIpoStatus(value: String): IpoStatus =
        runCatching { IpoStatus.valueOf(value) }.getOrDefault(IpoStatus.UPCOMING)

    @TypeConverter
    fun fromStringList(list: List<String>): String = json.encodeToString(list)

    @TypeConverter
    fun toStringList(raw: String): List<String> = json.decodeFromString(raw)

    @TypeConverter
    fun fromFinancialYears(list: List<FinancialYearReport>): String = json.encodeToString(list)

    @TypeConverter
    fun toFinancialYears(raw: String): List<FinancialYearReport> = json.decodeFromString(raw)

    @TypeConverter
    fun fromGmpPoints(list: List<GmpPoint>): String = json.encodeToString(list)

    @TypeConverter
    fun toGmpPoints(raw: String): List<GmpPoint> = json.decodeFromString(raw)

    @TypeConverter
    fun fromDailySubscriptions(list: List<DaySubscription>): String = json.encodeToString(list)

    @TypeConverter
    fun toDailySubscriptions(raw: String): List<DaySubscription> = json.decodeFromString(raw)

    @TypeConverter
    fun fromValuation(valuation: ValuationMetrics): String = json.encodeToString(valuation)

    @TypeConverter
    fun toValuation(raw: String): ValuationMetrics = json.decodeFromString(raw)

    @TypeConverter
    fun fromAllotmentWatchStatus(value: AllotmentWatchStatus): String = value.name

    @TypeConverter
    fun toAllotmentWatchStatus(value: String): AllotmentWatchStatus =
        runCatching { AllotmentWatchStatus.valueOf(value) }.getOrDefault(AllotmentWatchStatus.PENDING)
}
