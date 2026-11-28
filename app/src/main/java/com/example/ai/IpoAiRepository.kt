package com.example.ai

import com.example.data.model.IpoEntity
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.util.Locale

class IpoAiRepository {
    private val model by lazy {
        Firebase.ai.generativeModel(MODEL_NAME)
    }

    fun streamIpoAnalysis(
        ipo: IpoEntity,
        userQuestion: String? = null,
    ): Flow<String> {
        val prompt = buildGroundedPrompt(ipo = ipo, userQuestion = userQuestion)
        return model.generateContentStream(prompt).mapNotNull { chunk ->
            chunk.text
        }
    }

    internal fun buildGroundedPrompt(
        ipo: IpoEntity,
        userQuestion: String? = null,
    ): String {
        val priceBand = if (ipo.priceLow > 0 && ipo.priceHigh > 0) {
            "₹${ipo.priceLow}–₹${ipo.priceHigh}"
        } else {
            "TBA"
        }
        val gmpText = if (ipo.priceHigh > 0) {
            String.format(Locale.US, "₹%d (%.1f%%)", ipo.gmpRupees, ipo.gmpPercent)
        } else {
            "₹${ipo.gmpRupees}"
        }
        val financialsSummary = ipo.financialYears.takeLast(3).joinToString("; ") { fy ->
            String.format(
                Locale.US,
                "%s: Rev ₹%.1f Cr, PAT ₹%.1f Cr, Debt ₹%.1f Cr",
                fy.fiscalYear,
                fy.revenueCr,
                fy.patCr,
                fy.totalDebtCr,
            )
        }.ifBlank { "Not reported" }
        val strengthsText = ipo.strengths.take(4).joinToString("; ").ifBlank { "Not listed" }
        val risksText = ipo.risks.take(4).joinToString("; ").ifBlank { "Not listed" }

        val task = userQuestion?.trim()?.takeIf { it.isNotBlank() }
            ?: "Give a concise, balanced IPO research brief covering strengths, key risks, valuation, and live subscription/GMP signals in 4–6 bullet points."

        return buildString {
            appendLine("You are an Indian stock market IPO research analyst inside the IPO Guruji app.")
            appendLine("Use ONLY the provided IPO data and general market concepts. Be concise, balanced, and factual.")
            appendLine("Never guarantee listing gains or give personalized financial advice. End with a 1-line educational disclaimer.")
            appendLine()
            appendLine("IPO Snapshot:")
            appendLine("- Company: ${ipo.companyName} (${ipo.boardType.displayLabel}, ${ipo.exchange}, Sector: ${ipo.sector})")
            appendLine("- Status & Dates: ${ipo.status.displayLabel} | Open: ${ipo.openDate} | Close: ${ipo.closeDate} | Listing: ${ipo.listingDate}")
            appendLine("- Price Band: $priceBand | Lot Size: ${ipo.lotSize} | Issue Size: ₹${String.format(Locale.US, "%.1f", ipo.issueSizeCr)} Cr (Fresh: ₹${String.format(Locale.US, "%.1f", ipo.freshIssueCr)} Cr, OFS: ₹${String.format(Locale.US, "%.1f", ipo.ofsCr)} Cr)")
            appendLine("- Grey Market Premium (GMP): $gmpText")
            appendLine(
                String.format(
                    Locale.US,
                    "- Subscription: Total %.2fx (QIB %.2fx, bNII %.2fx, sNII %.2fx, Retail %.2fx)",
                    ipo.totalSubX,
                    ipo.qibSubX,
                    ipo.bNiiSubX,
                    ipo.sNiiSubX,
                    ipo.retailSubX,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "- Valuation: P/E %.2f, EPS ₹%.2f, ROE %.1f%%, RoNW %.1f%%, D/E %.2f",
                    ipo.valuation.peRatio,
                    ipo.valuation.eps,
                    ipo.valuation.roePercent,
                    ipo.valuation.ronwPercent,
                    ipo.valuation.debtToEquity,
                ),
            )
            appendLine("- Financials: $financialsSummary")
            appendLine("- Overview: ${ipo.overview.take(350)}")
            appendLine("- Strengths: $strengthsText")
            appendLine("- Risks: $risksText")
            appendLine()
            appendLine("User Request: $task")
        }
    }

    companion object {
        const val MODEL_NAME = "gemini-3.8-flash"
    }
}
