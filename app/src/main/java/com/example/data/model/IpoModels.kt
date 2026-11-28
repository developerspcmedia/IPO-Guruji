package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class BoardType(val displayLabel: String) {
    MAINBOARD("Mainboard"),
    SME("SME"),
}

@Serializable
enum class IpoStatus(val displayLabel: String) {
    LIVE("Open"),
    PRE_APPLY("Pre-Apply"),
    UPCOMING("Upcoming"),
    CLOSED("Closed"),
}

@Serializable
data class FinancialYearReport(
    val fiscalYear: String,
    val revenueCr: Double,
    val ebitdaCr: Double,
    val patCr: Double,
    val netWorthCr: Double,
    val totalDebtCr: Double,
    val assetsCr: Double,
)

@Serializable
data class GmpPoint(
    val dayLabel: String,
    val gmpRupees: Int,
)

@Serializable
data class DaySubscription(
    val dayLabel: String,
    val qibX: Double,
    val niiX: Double,
    val retailX: Double,
    val totalX: Double,
    val dateLabel: String = "",
    val bNiiX: Double = 0.0,
    val sNiiX: Double = 0.0,
)

@Serializable
data class LiveApplicationCount(
    val categoryId: String,
    val categoryLabel: String,
    val reservedApplications: Long = 0L,
    val appliedApplications: Long = 0L,
    val subscriptionX: Double = 0.0,
)

@Serializable
data class ValuationMetrics(
    val eps: Double,
    val peRatio: Double,
    val ronwPercent: Double,
    val roePercent: Double,
    val debtToEquity: Double,
    val navPerShare: Double,
    val priceToBook: Double,
    val marketCapCr: Double,
)

@Entity(tableName = "ipos")
data class IpoEntity(
    @PrimaryKey val id: String,
    val companyName: String,
    val shortCode: String,
    val sector: String,
    val boardType: BoardType,
    val exchange: String,
    val status: IpoStatus,
    val openDate: String,
    val closeDate: String,
    val allotmentDate: String,
    val listingDate: String,
    val priceLow: Int,
    val priceHigh: Int,
    val faceValue: Int,
    val lotSize: Int,
    val issueSizeCr: Double,
    val freshIssueCr: Double,
    val ofsCr: Double,
    val gmpRupees: Int,
    val kostakRupees: Int,
    val qibSubX: Double,
    val bNiiSubX: Double,
    val sNiiSubX: Double,
    val retailSubX: Double,
    val employeeSubX: Double,
    val totalSubX: Double,
    val registrarName: String,
    val registrarUrl: String,
    val leadManagers: List<String>,
    val overview: String,
    val objectsOfIssue: List<String>,
    val strengths: List<String>,
    val risks: List<String>,
    val financialYears: List<FinancialYearReport>,
    val gmpHistory: List<GmpPoint>,
    val dailySubscriptions: List<DaySubscription>,
    val valuation: ValuationMetrics,
    val isWatchlisted: Boolean = false,
    val userLots: Int = 1,
    val updatedAgoLabel: String = "Updated 12m ago",
    val gmpSourceLabel: String = "InvestorGain • IPOWatch",
    val sortOrder: Int = 0,
    val logoUrl: String = "",
    val bannerImageUrl: String = "",
    val liveApplicationCounts: List<LiveApplicationCount> = emptyList(),
    val subscriptionSourceLabel: String = "",
    val subscriptionUpdatedAtMs: Long = 0L,
) {
    val minInvestment: Int
        get() = priceHigh * lotSize

    val estimatedListingPrice: Int
        get() = priceHigh + gmpRupees

    val gmpPercent: Double
        get() = if (priceHigh > 0) (gmpRupees.toDouble() / priceHigh.toDouble()) * 100.0 else 0.0

    val expectedLotProfit: Int
        get() = gmpRupees * lotSize

    val niiCombinedSubX: Double
        get() = dailySubscriptions.lastOrNull()?.niiX?.takeIf { it > 0.0 }
            ?: if (bNiiSubX > 0.0 || sNiiSubX > 0.0) {
                (bNiiSubX * 2.0 + sNiiSubX) / 3.0
            } else {
                0.0
            }

    val retailMaxLots: Int
        get() = if (minInvestment > 0) (200_000 / minInvestment).coerceAtLeast(1) else 1

    val sHniMinLots: Int
        get() = retailMaxLots + 1

    val sHniMaxLots: Int
        get() = if (minInvestment > 0) (1_000_000 / minInvestment).coerceAtLeast(sHniMinLots) else sHniMinLots

    val bHniMinLots: Int
        get() = sHniMaxLots + 1

    val categoryLotTiers: List<CategoryLotTier>
        get() {
            val tiers = listOf(
                CategoryLotTier(
                    id = "retail_min",
                    categoryLabel = "Retail (Min)",
                    limitHint = "Min bid",
                    lots = 1,
                    shares = lotSize,
                    totalAmount = minInvestment,
                ),
                CategoryLotTier(
                    id = "retail_max",
                    categoryLabel = "Retail (Max)",
                    limitHint = "≤ ₹2 Lakh",
                    lots = retailMaxLots,
                    shares = lotSize * retailMaxLots,
                    totalAmount = minInvestment * retailMaxLots,
                ),
                CategoryLotTier(
                    id = "shni_min",
                    categoryLabel = "S-HNI (Min)",
                    limitHint = "> ₹2 Lakh",
                    lots = sHniMinLots,
                    shares = lotSize * sHniMinLots,
                    totalAmount = minInvestment * sHniMinLots,
                ),
                CategoryLotTier(
                    id = "shni_max",
                    categoryLabel = "S-HNI (Max)",
                    limitHint = "≤ ₹10 Lakh",
                    lots = sHniMaxLots,
                    shares = lotSize * sHniMaxLots,
                    totalAmount = minInvestment * sHniMaxLots,
                ),
                CategoryLotTier(
                    id = "bhni_min",
                    categoryLabel = "Big HNI (Min)",
                    limitHint = "> ₹10 Lakh",
                    lots = bHniMinLots,
                    shares = lotSize * bHniMinLots,
                    totalAmount = minInvestment * bHniMinLots,
                ),
            )
            return tiers
        }

    val totalIssueShares: Long
        get() = if (issueSizeCr > 0.0 && priceHigh > 0) {
            ((issueSizeCr * 10_000_000.0) / priceHigh.toDouble()).toLong().coerceAtLeast(lotSize.toLong())
        } else {
            0L
        }

    val hasLiveApplicationCounts: Boolean
        get() = liveApplicationCounts.any { it.appliedApplications > 0L }

    val liveTotalApplications: Long
        get() {
            val explicitTotal = liveApplicationCounts
                .firstOrNull { it.categoryId == "total" }
                ?.appliedApplications
                ?: 0L
            if (explicitTotal > 0L) return explicitTotal
            return liveApplicationCounts
                .filter { it.categoryId != "total" }
                .sumOf { it.appliedApplications }
        }

    val applicationWiseBreakup: List<ApplicationBreakupRow>
        get() {
            val liveById = liveApplicationCounts.associateBy { it.categoryId }
            val hasQuotaMath = issueSizeCr > 0.0 && priceHigh > 0 && lotSize > 0
            if (!hasQuotaMath && liveById.isEmpty()) return emptyList()

            val totalShares = if (hasQuotaMath) totalIssueShares else 0L
            val isSme = boardType == BoardType.SME
            val retailPct = if (isSme) 0.3325 else 0.35
            val sHniPct = if (isSme) 0.0475 else 0.05
            val bHniPct = if (isSme) 0.095 else 0.10

            val estRetailReserved = if (hasQuotaMath) {
                ((totalShares * retailPct) / lotSize.toDouble()).toLong().coerceAtLeast(1L)
            } else {
                0L
            }
            val estSHniReserved = if (hasQuotaMath) {
                ((totalShares * sHniPct) / (lotSize.toLong() * sHniMinLots).toDouble()).toLong().coerceAtLeast(1L)
            } else {
                0L
            }
            val estBHniReserved = if (hasQuotaMath) {
                ((totalShares * bHniPct) / (lotSize.toLong() * bHniMinLots).toDouble()).toLong().coerceAtLeast(1L)
            } else {
                0L
            }

            fun buildRow(
                id: String,
                label: String,
                quotaLabel: String,
                estReserved: Long,
                fallbackSubX: Double,
            ): ApplicationBreakupRow {
                val live = liveById[id]
                val reserved = live?.reservedApplications?.takeIf { it > 0L } ?: estReserved
                val subX = live?.subscriptionX?.takeIf { it > 0.0 } ?: fallbackSubX
                val hasLiveApps = (live?.appliedApplications ?: 0L) > 0L
                val applied = if (hasLiveApps) {
                    live!!.appliedApplications
                } else {
                    (reserved * subX).toLong()
                }
                return ApplicationBreakupRow(
                    id = id,
                    categoryLabel = live?.categoryLabel?.takeIf { it.isNotBlank() } ?: label,
                    quotaLabel = quotaLabel,
                    reservedApplications = reserved,
                    receivedApplications = applied,
                    subscriptionX = subX,
                    isLiveExchangeCount = hasLiveApps,
                )
            }

            val rows = mutableListOf(
                buildRow(
                    id = "retail",
                    label = "Retail",
                    quotaLabel = if (isSme) "~33% Quota" else "35% Quota",
                    estReserved = estRetailReserved,
                    fallbackSubX = retailSubX,
                ),
                buildRow(
                    id = "shni",
                    label = "S-HNI",
                    quotaLabel = "₹2L–₹10L",
                    estReserved = estSHniReserved,
                    fallbackSubX = sNiiSubX,
                ),
                buildRow(
                    id = "bhni",
                    label = "Big HNI",
                    quotaLabel = "> ₹10 Lakh",
                    estReserved = estBHniReserved,
                    fallbackSubX = bNiiSubX,
                ),
            )

            // Append Employee / Shareholder rows when live exchange application counts are published
            liveById["employee"]?.takeIf { it.appliedApplications > 0L }?.let { emp ->
                rows.add(
                    ApplicationBreakupRow(
                        id = "employee",
                        categoryLabel = "Employee",
                        quotaLabel = "Emp Quota",
                        reservedApplications = emp.reservedApplications,
                        receivedApplications = emp.appliedApplications,
                        subscriptionX = emp.subscriptionX.takeIf { it > 0.0 } ?: employeeSubX,
                        isLiveExchangeCount = true,
                    ),
                )
            }
            liveById["shareholder"]?.takeIf { it.appliedApplications > 0L }?.let { sh ->
                rows.add(
                    ApplicationBreakupRow(
                        id = "shareholder",
                        categoryLabel = "Shareholder",
                        quotaLabel = "SH Quota",
                        reservedApplications = sh.reservedApplications,
                        receivedApplications = sh.appliedApplications,
                        subscriptionX = sh.subscriptionX,
                        isLiveExchangeCount = true,
                    ),
                )
            }

            return rows
        }

    val reservationCategoryBreakdown: List<ReservationCategoryRow>
        get() {
            val totalShares = totalIssueShares
            if (totalShares <= 0L || lotSize <= 0) return emptyList()
            val breakup = applicationWiseBreakup.associateBy { it.id }
            val retailApps = breakup["retail"]?.reservedApplications
            val sHniApps = breakup["shni"]?.reservedApplications
            val bHniApps = breakup["bhni"]?.reservedApplications

            return if (boardType == BoardType.SME) {
                val mmShares = (totalShares * 0.05).toLong()
                val qibShares = (totalShares * 0.475).toLong()
                val bHniShares = (totalShares * 0.095).toLong()
                val sHniShares = (totalShares * 0.0475).toLong()
                val niiShares = bHniShares + sHniShares
                val retailShares = (totalShares - mmShares - qibShares - niiShares).coerceAtLeast(0L)
                listOf(
                    ReservationCategoryRow("Market Maker", mmShares, 5.00),
                    ReservationCategoryRow("QIB (incl. Anchor)", qibShares, 47.50),
                    ReservationCategoryRow("NII", niiShares, 14.25),
                    ReservationCategoryRow("bHNI", bHniShares, 9.50, maxAllottees = bHniApps, isSubRow = true),
                    ReservationCategoryRow("sHNI", sHniShares, 4.75, maxAllottees = sHniApps, isSubRow = true),
                    ReservationCategoryRow("Retail", retailShares, 33.25, maxAllottees = retailApps),
                    ReservationCategoryRow("Total", totalShares, 100.00, isTotal = true),
                )
            } else {
                val anchorShares = (totalShares * 0.30).toLong()
                val qibShares = (totalShares * 0.20).toLong()
                val bHniShares = (totalShares * 0.10).toLong()
                val sHniShares = (totalShares * 0.05).toLong()
                val niiShares = bHniShares + sHniShares
                val retailShares = (totalShares - anchorShares - qibShares - niiShares).coerceAtLeast(0L)
                listOf(
                    ReservationCategoryRow("Anchor Investor", anchorShares, 30.00),
                    ReservationCategoryRow("QIB (Ex-Anchor)", qibShares, 20.00),
                    ReservationCategoryRow("NII", niiShares, 15.00),
                    ReservationCategoryRow("bHNI", bHniShares, 10.00, maxAllottees = bHniApps, isSubRow = true),
                    ReservationCategoryRow("sHNI", sHniShares, 5.00, maxAllottees = sHniApps, isSubRow = true),
                    ReservationCategoryRow("Retail", retailShares, 35.00, maxAllottees = retailApps),
                    ReservationCategoryRow("Total", totalShares, 100.00, isTotal = true),
                )
            }
        }
}

data class CategoryLotTier(
    val id: String,
    val categoryLabel: String,
    val limitHint: String,
    val lots: Int,
    val shares: Int,
    val totalAmount: Int,
)

data class ApplicationBreakupRow(
    val id: String,
    val categoryLabel: String,
    val quotaLabel: String,
    val reservedApplications: Long,
    val receivedApplications: Long,
    val subscriptionX: Double,
    val isLiveExchangeCount: Boolean = false,
)

data class ReservationCategoryRow(
    val category: String,
    val sharesOffered: Long,
    val percentage: Double,
    val maxAllottees: Long? = null,
    val isSubRow: Boolean = false,
    val isTotal: Boolean = false,
)
