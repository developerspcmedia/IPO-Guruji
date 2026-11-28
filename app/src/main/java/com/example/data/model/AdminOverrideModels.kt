package com.example.data.model

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import java.util.Locale

@Keep
@IgnoreExtraProperties
data class IpoOverrideDocument(
    @DocumentId val id: String = "",
    val targetIpoId: String = "",
    val normalizedKey: String = "",
    val companyName: String = "",
    val published: Boolean = true,
    val customIpo: Boolean = false,
    val hidden: Boolean = false,
    val shortCode: String? = null,
    val sector: String? = null,
    val boardType: String? = null,
    val exchange: String? = null,
    val status: String? = null,
    val openDate: String? = null,
    val closeDate: String? = null,
    val allotmentDate: String? = null,
    val listingDate: String? = null,
    val priceLow: Int? = null,
    val priceHigh: Int? = null,
    val faceValue: Int? = null,
    val lotSize: Int? = null,
    val issueSizeCr: Double? = null,
    val freshIssueCr: Double? = null,
    val ofsCr: Double? = null,
    val gmpRupees: Int? = null,
    val kostakRupees: Int? = null,
    val qibSubX: Double? = null,
    val bNiiSubX: Double? = null,
    val sNiiSubX: Double? = null,
    val retailSubX: Double? = null,
    val employeeSubX: Double? = null,
    val totalSubX: Double? = null,
    val registrarName: String? = null,
    val registrarUrl: String? = null,
    val overview: String? = null,
    val adminNote: String? = null,
    @com.google.firebase.firestore.ServerTimestamp val createdAt: Timestamp? = null,
    @com.google.firebase.firestore.ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun stripSubscriptionOverrides(): IpoOverrideDocument = copy(
        qibSubX = null,
        bNiiSubX = null,
        sNiiSubX = null,
        retailSubX = null,
        employeeSubX = null,
        totalSubX = null,
    )

    /**
     * Counts non-null manually overridden fields (excluding subscription metrics, which are always live-synced).
     */
    fun overriddenFieldCount(baseLiveIpo: IpoEntity? = null): Int {
        var count = 0
        if (baseLiveIpo != null && companyName.isNotBlank() && companyName.trim() != baseLiveIpo.companyName.trim()) {
            count++
        }
        val optionalFields: List<Any?> = listOf(
            shortCode,
            sector,
            boardType,
            exchange,
            status,
            overview,
            openDate,
            closeDate,
            allotmentDate,
            listingDate,
            priceLow,
            priceHigh,
            faceValue,
            lotSize,
            issueSizeCr,
            freshIssueCr,
            ofsCr,
            gmpRupees,
            kostakRupees,
            registrarName,
            registrarUrl,
        )
        count += optionalFields.count { it != null }
        return count
    }

    fun hasAnyManualChanges(baseLiveIpo: IpoEntity? = null): Boolean =
        customIpo || hidden || !adminNote.isNullOrBlank() || overriddenFieldCount(baseLiveIpo) > 0

    private fun buildSectionMaps(): Map<String, Map<String, Any>> {
        val meta = mapOf(
            "shortCode" to shortCode,
            "sector" to sector,
            "boardType" to boardType,
            "exchange" to exchange,
            "status" to status,
            "overview" to overview,
        ).entries.mapNotNull { (k, v) -> if (v != null) k to v else null }.toMap()

        val schedule = mapOf(
            "openDate" to openDate,
            "closeDate" to closeDate,
            "allotmentDate" to allotmentDate,
            "listingDate" to listingDate,
        ).entries.mapNotNull { (k, v) -> if (v != null) k to v else null }.toMap()

        val pricing = mapOf(
            "priceLow" to priceLow,
            "priceHigh" to priceHigh,
            "faceValue" to faceValue,
            "lotSize" to lotSize,
            "issueSizeCr" to issueSizeCr,
            "freshIssueCr" to freshIssueCr,
            "ofsCr" to ofsCr,
            "gmpRupees" to gmpRupees,
            "kostakRupees" to kostakRupees,
        ).entries.mapNotNull { (k, v) -> if (v != null) k to v else null }.toMap()

        val registrar = mapOf(
            "registrarName" to registrarName,
            "registrarUrl" to registrarUrl,
        ).entries.mapNotNull { (k, v) -> if (v != null) k to v else null }.toMap()

        // Subscription metrics are intentionally excluded: subscription always stays in live sync mode.
        val sections = mutableMapOf<String, Map<String, Any>>()
        if (meta.isNotEmpty()) sections["meta"] = meta
        if (schedule.isNotEmpty()) sections["schedule"] = schedule
        if (pricing.isNotEmpty()) sections["pricing"] = pricing
        if (registrar.isNotEmpty()) sections["registrar"] = registrar
        return sections
    }

    /**
     * Exact create payload mirroring firestore.rules `allow create` (excludes `@DocumentId` `id`,
     * groups fields into structured nested maps, strips null optional values, and never writes subscription).
     */
    fun toCreateMap(): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "targetIpoId" to targetIpoId,
            "normalizedKey" to normalizedKey,
            "companyName" to companyName,
            "published" to published,
            "customIpo" to customIpo,
            "hidden" to hidden,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        payload.putAll(buildSectionMaps())
        return payload
    }

    /**
     * Exact partial update payload mirroring firestore.rules `allow update` (preserves immutable
     * `targetIpoId` and `createdAt`, strips legacy `subscription` maps, and deletes any emptied section maps
     * so reset fields never linger in Cloud Firestore).
     */
    fun toUpdateMap(): Map<String, Any> {
        val sections = buildSectionMaps()
        val payload = mutableMapOf<String, Any>(
            "normalizedKey" to normalizedKey,
            "companyName" to companyName,
            "published" to published,
            "customIpo" to customIpo,
            "hidden" to hidden,
            "subscription" to FieldValue.delete(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        for (sectionKey in listOf("meta", "schedule", "pricing", "registrar")) {
            val sectionMap = sections[sectionKey]
            payload[sectionKey] = sectionMap ?: FieldValue.delete()
        }
        return payload
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromDocumentSnapshot(doc: com.google.firebase.firestore.DocumentSnapshot): IpoOverrideDocument? {
            val data = doc.data ?: return null
            val meta = data["meta"] as? Map<String, Any?> ?: emptyMap()
            val schedule = data["schedule"] as? Map<String, Any?> ?: emptyMap()
            val pricing = data["pricing"] as? Map<String, Any?> ?: emptyMap()
            val subscription = data["subscription"] as? Map<String, Any?> ?: emptyMap()
            val registrar = data["registrar"] as? Map<String, Any?> ?: emptyMap()

            fun mapStr(m: Map<String, Any?>, key: String): String? = m[key] as? String
            fun mapInt(m: Map<String, Any?>, key: String): Int? = (m[key] as? Number)?.toInt()
            fun mapDouble(m: Map<String, Any?>, key: String): Double? = (m[key] as? Number)?.toDouble()

            val targetId = (data["targetIpoId"] as? String).orEmpty().ifBlank { doc.id }
            val name = (data["companyName"] as? String).orEmpty()
            if (name.isBlank()) return null

            return IpoOverrideDocument(
                id = doc.id,
                targetIpoId = targetId,
                normalizedKey = (data["normalizedKey"] as? String).orEmpty()
                    .ifBlank { IpoOverrideMerger.normalizeCompanyKey(name) },
                companyName = name,
                published = data["published"] as? Boolean ?: true,
                customIpo = data["customIpo"] as? Boolean ?: false,
                hidden = data["hidden"] as? Boolean ?: false,
                shortCode = mapStr(meta, "shortCode"),
                sector = mapStr(meta, "sector"),
                boardType = mapStr(meta, "boardType"),
                exchange = mapStr(meta, "exchange"),
                status = mapStr(meta, "status"),
                overview = mapStr(meta, "overview"),
                adminNote = null,
                openDate = mapStr(schedule, "openDate"),
                closeDate = mapStr(schedule, "closeDate"),
                allotmentDate = mapStr(schedule, "allotmentDate"),
                listingDate = mapStr(schedule, "listingDate"),
                priceLow = mapInt(pricing, "priceLow"),
                priceHigh = mapInt(pricing, "priceHigh"),
                faceValue = mapInt(pricing, "faceValue"),
                lotSize = mapInt(pricing, "lotSize"),
                issueSizeCr = mapDouble(pricing, "issueSizeCr"),
                freshIssueCr = mapDouble(pricing, "freshIssueCr"),
                ofsCr = mapDouble(pricing, "ofsCr"),
                gmpRupees = mapInt(pricing, "gmpRupees"),
                kostakRupees = mapInt(pricing, "kostakRupees"),
                qibSubX = mapDouble(subscription, "qibSubX"),
                bNiiSubX = mapDouble(subscription, "bNiiSubX"),
                sNiiSubX = mapDouble(subscription, "sNiiSubX"),
                retailSubX = mapDouble(subscription, "retailSubX"),
                employeeSubX = mapDouble(subscription, "employeeSubX"),
                totalSubX = mapDouble(subscription, "totalSubX"),
                registrarName = mapStr(registrar, "registrarName"),
                registrarUrl = mapStr(registrar, "registrarUrl"),
                createdAt = doc.getTimestamp("createdAt", com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                updatedAt = doc.getTimestamp("updatedAt", com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
            )
        }
    }
}

@Keep
@IgnoreExtraProperties
data class AdminProfileDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val role: String = "admin",
    @com.google.firebase.firestore.ServerTimestamp val createdAt: Timestamp? = null,
    @com.google.firebase.firestore.ServerTimestamp val lastActiveAt: Timestamp? = null,
)

@Entity(tableName = "admin_ipo_overrides")
data class CachedAdminOverrideEntity(
    @PrimaryKey val overrideId: String,
    val targetIpoId: String,
    val normalizedKey: String,
    val companyName: String,
    val published: Boolean = true,
    val customIpo: Boolean = false,
    val hidden: Boolean = false,
    val shortCode: String? = null,
    val sector: String? = null,
    val boardType: String? = null,
    val exchange: String? = null,
    val status: String? = null,
    val openDate: String? = null,
    val closeDate: String? = null,
    val allotmentDate: String? = null,
    val listingDate: String? = null,
    val priceLow: Int? = null,
    val priceHigh: Int? = null,
    val faceValue: Int? = null,
    val lotSize: Int? = null,
    val issueSizeCr: Double? = null,
    val freshIssueCr: Double? = null,
    val ofsCr: Double? = null,
    val gmpRupees: Int? = null,
    val kostakRupees: Int? = null,
    val qibSubX: Double? = null,
    val bNiiSubX: Double? = null,
    val sNiiSubX: Double? = null,
    val retailSubX: Double? = null,
    val employeeSubX: Double? = null,
    val totalSubX: Double? = null,
    val registrarName: String? = null,
    val registrarUrl: String? = null,
    val overview: String? = null,
    // Legacy nullable column retained for Room schema compatibility; never stored or read (admin notes live exclusively in /ipo_admin_notes/{overrideId}).
    val adminNote: String? = null,
    val updatedAtMs: Long = 0L,
) {
    fun toDocument(): IpoOverrideDocument = IpoOverrideDocument(
        id = overrideId,
        targetIpoId = targetIpoId,
        normalizedKey = normalizedKey,
        companyName = companyName,
        published = published,
        customIpo = customIpo,
        hidden = hidden,
        shortCode = shortCode,
        sector = sector,
        boardType = boardType,
        exchange = exchange,
        status = status,
        openDate = openDate,
        closeDate = closeDate,
        allotmentDate = allotmentDate,
        listingDate = listingDate,
        priceLow = priceLow,
        priceHigh = priceHigh,
        faceValue = faceValue,
        lotSize = lotSize,
        issueSizeCr = issueSizeCr,
        freshIssueCr = freshIssueCr,
        ofsCr = ofsCr,
        gmpRupees = gmpRupees,
        kostakRupees = kostakRupees,
        qibSubX = qibSubX,
        bNiiSubX = bNiiSubX,
        sNiiSubX = sNiiSubX,
        retailSubX = retailSubX,
        employeeSubX = employeeSubX,
        totalSubX = totalSubX,
        registrarName = registrarName,
        registrarUrl = registrarUrl,
        overview = overview,
        adminNote = null,
    )

    companion object {
        fun fromDocument(doc: IpoOverrideDocument): CachedAdminOverrideEntity = CachedAdminOverrideEntity(
            overrideId = doc.id.ifBlank { doc.targetIpoId },
            targetIpoId = doc.targetIpoId.ifBlank { doc.id },
            normalizedKey = doc.normalizedKey,
            companyName = doc.companyName,
            published = doc.published,
            customIpo = doc.customIpo,
            hidden = doc.hidden,
            shortCode = doc.shortCode,
            sector = doc.sector,
            boardType = doc.boardType,
            exchange = doc.exchange,
            status = doc.status,
            openDate = doc.openDate,
            closeDate = doc.closeDate,
            allotmentDate = doc.allotmentDate,
            listingDate = doc.listingDate,
            priceLow = doc.priceLow,
            priceHigh = doc.priceHigh,
            faceValue = doc.faceValue,
            lotSize = doc.lotSize,
            issueSizeCr = doc.issueSizeCr,
            freshIssueCr = doc.freshIssueCr,
            ofsCr = doc.ofsCr,
            gmpRupees = doc.gmpRupees,
            kostakRupees = doc.kostakRupees,
            qibSubX = doc.qibSubX,
            bNiiSubX = doc.bNiiSubX,
            sNiiSubX = doc.sNiiSubX,
            retailSubX = doc.retailSubX,
            employeeSubX = doc.employeeSubX,
            totalSubX = doc.totalSubX,
            registrarName = doc.registrarName,
            registrarUrl = doc.registrarUrl,
            overview = doc.overview,
            adminNote = null,
            updatedAtMs = doc.updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
        )
    }
}

object IpoOverrideMerger {
    const val ADMIN_SOURCE_PREFIX = "Admin Verified •"

    fun isAdminManagedEntity(entity: IpoEntity): Boolean =
        entity.gmpSourceLabel.startsWith(ADMIN_SOURCE_PREFIX, ignoreCase = true) ||
            entity.updatedAgoLabel.contains("Admin", ignoreCase = true)

    fun sanitizeDocumentId(raw: String): String {
        val cleaned = raw.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(100)
        return cleaned.ifBlank { "ipo_${System.currentTimeMillis()}" }
    }

    fun normalizeCompanyKey(companyName: String): String {
        return companyName.lowercase(Locale.US)
            .replace(Regex("\\b(limited|ltd|india|industries|technologies|tech|private|pvt|ipo|sme|mainboard)\\b"), "")
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { companyName.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "") }
            .take(100)
    }

    /**
     * Merges field-level manual Admin overrides on top of the automatic live/cached catalog.
     * - Hidden overrides suppress matching IPOs from the visible catalog.
     * - Only explicitly edited fields override live values; untouched fields (including GMP) stay live-synced.
     * - Subscription metrics (QIB, bNII, sNii, Retail, Employee, Total) ALWAYS stay in live sync mode.
     * - Custom IPO overrides materialize complete [IpoEntity] records directly into the catalog.
     */
    fun applyOverridesToCatalog(
        baseCatalog: List<IpoEntity>,
        overrides: List<IpoOverrideDocument>,
    ): List<IpoEntity> {
        val activeOverrides = overrides.filter { it.published }
        if (activeOverrides.isEmpty()) return baseCatalog

        val byTargetId = activeOverrides.associateBy { it.targetIpoId.ifBlank { it.id } }
        val byNormKey = activeOverrides
            .filter { it.normalizedKey.isNotBlank() }
            .associateBy { it.normalizedKey }

        val consumedOverrideIds = HashSet<String>()
        val merged = mutableListOf<IpoEntity>()

        for (item in baseCatalog) {
            val itemNormKey = normalizeCompanyKey(item.companyName)
            val matched = byTargetId[item.id] ?: byNormKey[itemNormKey]
            if (matched == null) {
                merged.add(item)
            } else {
                val overrideKey = matched.targetIpoId.ifBlank { matched.id }
                consumedOverrideIds.add(overrideKey)
                if (!matched.hidden) {
                    merged.add(overlayEntity(item, matched))
                }
            }
        }

        // Materialize custom IPOs or overrides whose base IPO is no longer in live feeds
        activeOverrides.forEachIndexed { idx, override ->
            val overrideKey = override.targetIpoId.ifBlank { override.id }
            if (overrideKey !in consumedOverrideIds && !override.hidden) {
                merged.add(0, buildEntityFromOverride(override, sortOrder = -500 + idx))
            }
        }

        return merged
    }

    fun overlayEntity(base: IpoEntity, override: IpoOverrideDocument): IpoEntity {
        val resolvedBoard = override.boardType
            ?.let { raw -> enumValues<BoardType>().firstOrNull { it.name == raw } }
            ?: base.boardType
        val resolvedStatus = override.status
            ?.let { raw -> enumValues<IpoStatus>().firstOrNull { it.name == raw } }
            ?: base.status
        val resolvedLow = override.priceLow ?: base.priceLow
        val resolvedHigh = override.priceHigh?.let { if (it == 0) 0 else maxOf(resolvedLow, it) }
            ?: if (base.priceHigh > 0) maxOf(resolvedLow, base.priceHigh) else base.priceHigh

        // GMP stays live-synced unless explicitly overridden by the admin
        val hasManualGmp = override.gmpRupees != null
        val resolvedGmp = override.gmpRupees ?: base.gmpRupees
        val resolvedHistory = if (hasManualGmp) {
            val prior = if (base.gmpHistory.size >= 2) base.gmpHistory.dropLast(1) else base.gmpHistory
            prior + GmpPoint(dayLabel = "Admin", gmpRupees = resolvedGmp)
        } else {
            base.gmpHistory
        }
        val resolvedGmpSource = if (hasManualGmp) {
            if (override.customIpo) "Admin Verified • Custom" else "Admin Verified • Manual"
        } else {
            base.gmpSourceLabel
        }
        val resolvedUpdatedLabel = when {
            !override.hasAnyManualChanges(base) -> base.updatedAgoLabel
            base.updatedAgoLabel.contains("Admin", ignoreCase = true) -> base.updatedAgoLabel
            base.updatedAgoLabel.isBlank() -> "Admin Updated"
            else -> "${base.updatedAgoLabel} • Admin"
        }

        return base.copy(
            companyName = override.companyName.takeIf { it.isNotBlank() } ?: base.companyName,
            shortCode = override.shortCode?.takeIf { it.isNotBlank() } ?: base.shortCode,
            sector = override.sector?.takeIf { it.isNotBlank() } ?: base.sector,
            boardType = resolvedBoard,
            exchange = override.exchange?.takeIf { it.isNotBlank() } ?: base.exchange,
            status = resolvedStatus,
            openDate = override.openDate?.takeIf { it.isNotBlank() } ?: base.openDate,
            closeDate = override.closeDate?.takeIf { it.isNotBlank() } ?: base.closeDate,
            allotmentDate = override.allotmentDate?.takeIf { it.isNotBlank() } ?: base.allotmentDate,
            listingDate = override.listingDate?.takeIf { it.isNotBlank() } ?: base.listingDate,
            priceLow = resolvedLow,
            priceHigh = resolvedHigh,
            faceValue = override.faceValue ?: base.faceValue,
            lotSize = override.lotSize ?: base.lotSize,
            issueSizeCr = override.issueSizeCr ?: base.issueSizeCr,
            freshIssueCr = override.freshIssueCr ?: base.freshIssueCr,
            ofsCr = override.ofsCr ?: base.ofsCr,
            gmpRupees = resolvedGmp,
            kostakRupees = override.kostakRupees ?: base.kostakRupees,
            // Subscription metrics ALWAYS remain live-synced from base (even if legacy docs contain subscription values)
            qibSubX = base.qibSubX,
            bNiiSubX = base.bNiiSubX,
            sNiiSubX = base.sNiiSubX,
            retailSubX = base.retailSubX,
            employeeSubX = base.employeeSubX,
            totalSubX = base.totalSubX,
            dailySubscriptions = base.dailySubscriptions,
            liveApplicationCounts = base.liveApplicationCounts,
            subscriptionSourceLabel = base.subscriptionSourceLabel,
            subscriptionUpdatedAtMs = base.subscriptionUpdatedAtMs,
            registrarName = override.registrarName?.takeIf { it.isNotBlank() } ?: base.registrarName,
            registrarUrl = override.registrarUrl?.takeIf { it.isNotBlank() } ?: base.registrarUrl,
            overview = override.overview?.takeIf { it.isNotBlank() } ?: base.overview,
            gmpHistory = resolvedHistory,
            updatedAgoLabel = resolvedUpdatedLabel,
            gmpSourceLabel = resolvedGmpSource,
        )
    }

    fun buildEntityFromOverride(
        override: IpoOverrideDocument,
        sortOrder: Int = -100,
    ): IpoEntity {
        val resolvedBoard = override.boardType
            ?.let { raw -> enumValues<BoardType>().firstOrNull { it.name == raw } }
            ?: BoardType.MAINBOARD
        val resolvedStatus = override.status
            ?.let { raw -> enumValues<IpoStatus>().firstOrNull { it.name == raw } }
            ?: IpoStatus.UPCOMING
        val resolvedLow = override.priceLow ?: 0
        val resolvedHigh = override.priceHigh?.let { if (it == 0) 0 else maxOf(resolvedLow, it) } ?: resolvedLow
        val resolvedGmp = override.gmpRupees ?: 0
        val code = override.shortCode?.takeIf { it.isNotBlank() }
            ?: override.companyName.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.take(3).uppercase(Locale.US) }
                .ifBlank { "IPO" }

        return IpoEntity(
            id = override.targetIpoId.ifBlank { override.id },
            companyName = override.companyName,
            shortCode = code,
            sector = override.sector?.takeIf { it.isNotBlank() }
                ?: if (resolvedBoard == BoardType.SME) "SME Growth" else "Mainboard Equity",
            boardType = resolvedBoard,
            exchange = override.exchange?.takeIf { it.isNotBlank() }
                ?: if (resolvedBoard == BoardType.SME) "NSE SME" else "NSE, BSE",
            status = resolvedStatus,
            openDate = override.openDate?.takeIf { it.isNotBlank() } ?: "TBA",
            closeDate = override.closeDate?.takeIf { it.isNotBlank() } ?: "TBA",
            allotmentDate = override.allotmentDate?.takeIf { it.isNotBlank() } ?: "TBA",
            listingDate = override.listingDate?.takeIf { it.isNotBlank() } ?: "TBA",
            priceLow = resolvedLow,
            priceHigh = resolvedHigh,
            faceValue = override.faceValue ?: 10,
            lotSize = override.lotSize ?: 0,
            issueSizeCr = override.issueSizeCr ?: 0.0,
            freshIssueCr = override.freshIssueCr ?: (override.issueSizeCr ?: 0.0),
            ofsCr = override.ofsCr ?: 0.0,
            gmpRupees = resolvedGmp,
            kostakRupees = override.kostakRupees ?: 0,
            // Custom IPOs start with 0.0 subscription and receive live exchange updates automatically when matched
            qibSubX = 0.0,
            bNiiSubX = 0.0,
            sNiiSubX = 0.0,
            retailSubX = 0.0,
            employeeSubX = 0.0,
            totalSubX = 0.0,
            registrarName = override.registrarName?.takeIf { it.isNotBlank() } ?: "Link Intime India Pvt Ltd",
            registrarUrl = override.registrarUrl?.takeIf { it.isNotBlank() }
                ?: "https://in.mpms.mufg.com/Initial_Offer/public-issues.html",
            leadManagers = listOf("Check RHP / DRHP Filing"),
            overview = override.overview?.takeIf { it.isNotBlank() }
                ?: "${override.companyName} details curated and verified by IPO Guruji Admin.",
            objectsOfIssue = listOf("General corporate purposes & working capital requirements."),
            strengths = listOf("Verified manually via Admin Dashboard."),
            risks = listOf("Refer to official SEBI DRHP/RHP filing before investing."),
            financialYears = emptyList(),
            gmpHistory = listOf(GmpPoint("Admin", resolvedGmp)),
            dailySubscriptions = emptyList(),
            valuation = ValuationMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            isWatchlisted = false,
            userLots = 1,
            updatedAgoLabel = "Admin Updated",
            gmpSourceLabel = if (override.customIpo) "Admin Verified • Custom" else "Admin Verified • Manual",
            sortOrder = sortOrder,
        )
    }
}
