package com.example.data.repository

import com.example.data.local.AllotmentDao
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.AllotmentWatchStatus
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.PanPrivacyUtils
import com.example.data.model.PrivateUserProfileEntity
import com.example.security.CryptoBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AllotmentCheckEvent(
    val watch: AllotmentWatchEntity,
    val shouldNotifyLive: Boolean = false,
    val shouldNotifyAllotted: Boolean = false,
    val shouldNotifyNotAllotted: Boolean = false,
)

data class AllotmentBatchCheckResult(
    val checkedCount: Int,
    val events: List<AllotmentCheckEvent>,
    val summaryMessage: String,
)

interface RegistrarAllotmentChecker {
    suspend fun checkStatus(
        watch: AllotmentWatchEntity,
        panNumber: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): RegistrarCheckOutcome
}

data class RegistrarCheckOutcome(
    val status: AllotmentWatchStatus,
    val sharesAllotted: Int = 0,
    val summary: String,
)

/**
 * Direct device-to-registrar HTTPS checker.
 * Never routes PAN or application details through any third-party or app backend server.
 */
class DirectRegistrarAllotmentChecker(
    private val enableNetworkFetch: Boolean = true,
) : RegistrarAllotmentChecker {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun checkStatus(
        watch: AllotmentWatchEntity,
        panNumber: String,
        referenceTimeMs: Long,
    ): RegistrarCheckOutcome = withContext(Dispatchers.IO) {
        if (watch.status == AllotmentWatchStatus.ALLOTTED || watch.status == AllotmentWatchStatus.NOT_ALLOTTED) {
            return@withContext RegistrarCheckOutcome(
                status = watch.status,
                sharesAllotted = watch.sharesAllotted,
                summary = watch.lastCheckSummary,
            )
        }

        val normalizedRegistrar = "${watch.registrarName} ${watch.registrarUrl}".lowercase()
        if (enableNetworkFetch) {
            // 1. Direct query to MUFG Intime (formerly Link Intime) public allotment endpoint
            if (normalizedRegistrar.contains("intime") || normalizedRegistrar.contains("mufg")) {
                val directResult = runCatching {
                    queryMufgIntimeDirect(watch.ipoName, panNumber)
                }.getOrNull()
                if (directResult != null) {
                    return@withContext directResult
                }
            }

            // 2. Direct public issue-list check on registrar website for other registrars (no PAN transmitted)
            val portalLive = runCatching {
                isIpoPublishedOnRegistrarPage(watch.ipoName, watch.registrarUrl)
            }.getOrDefault(false)
            if (portalLive) {
                return@withContext RegistrarCheckOutcome(
                    status = AllotmentWatchStatus.RESULT_LIVE,
                    summary = "Allotment result is live on ${watch.registrarName}. Tap Open Portal to verify.",
                )
            }
        }

        // 3. Date-based allotment readiness check (T+1 allotment date reached or passed)
        val todayMs = IpoDateUtils.startOfDayMs(referenceTimeMs)
        val allotmentMs = IpoDateUtils.parseFlexibleDateMs(watch.allotmentDate, referenceTimeMs)
        if (allotmentMs != null && allotmentMs <= todayMs) {
            return@withContext RegistrarCheckOutcome(
                status = AllotmentWatchStatus.RESULT_LIVE,
                summary = "Allotment date (${watch.allotmentDate}) reached — check ${watch.registrarName} portal now.",
            )
        }

        val dateNote = if (watch.allotmentDate.isNotBlank() && !watch.allotmentDate.equals("TBA", true)) {
            "Expected on ${watch.allotmentDate}"
        } else {
            "Awaiting official allotment date"
        }
        RegistrarCheckOutcome(
            status = AllotmentWatchStatus.PENDING,
            summary = "Checked registrar • $dateNote",
        )
    }

    private fun queryMufgIntimeDirect(ipoName: String, panNumber: String): RegistrarCheckOutcome? {
        val listRequest = Request.Builder()
            .url("https://in.mpms.mufg.com/Initial_Offer/IPO.aspx/GetDetails")
            .post("{}".toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val detailsBody = client.newCall(listRequest).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string().orEmpty()
        }
        if (detailsBody.isBlank()) return null

        val companyCode = matchMufgCompanyCode(detailsBody, ipoName) ?: return null
        val cleanPan = PanPrivacyUtils.normalizePan(panNumber)
        if (!PanPrivacyUtils.isValidPan(cleanPan)) {
            return RegistrarCheckOutcome(
                status = AllotmentWatchStatus.RESULT_LIVE,
                summary = "Result is live on MUFG Intime — enter valid PAN to auto-check.",
            )
        }

        val payload = JSONObject().apply {
            put("clientid", companyCode)
            put("PAN", cleanPan)
            put("IFSC", "")
            put("CHKVAL", "1")
            put("token", "")
        }.toString()

        val searchRequest = Request.Builder()
            .url("https://in.mpms.mufg.com/Initial_Offer/IPO.aspx/SearchOnPan")
            .post(payload.toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val searchBody = client.newCall(searchRequest).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string().orEmpty()
        }
        if (searchBody.isBlank()) {
            return RegistrarCheckOutcome(
                status = AllotmentWatchStatus.RESULT_LIVE,
                summary = "Result is live on MUFG Intime — tap Open Portal to check.",
            )
        }

        return parseMufgSearchResponse(searchBody)
            ?: RegistrarCheckOutcome(
                status = AllotmentWatchStatus.RESULT_LIVE,
                summary = "Allotment published on MUFG Intime — tap Open Portal to verify.",
            )
    }

    private fun isIpoPublishedOnRegistrarPage(ipoName: String, registrarUrl: String): Boolean {
        val targetUrl = registrarUrl.trim()
        if (!targetUrl.startsWith("http")) return false
        val request = Request.Builder()
            .url(targetUrl)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()
        val html = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return false
            resp.body?.string().orEmpty()
        }
        val tokens = significantCompanyTokens(ipoName)
        if (tokens.isEmpty()) return false
        val lowerHtml = html.lowercase()
        val matched = tokens.count { lowerHtml.contains(it) }
        return matched >= tokens.size.coerceAtMost(2)
    }

    companion object {
        internal fun matchMufgCompanyCode(responseJson: String, ipoName: String): String? {
            val tokens = significantCompanyTokens(ipoName)
            if (tokens.isEmpty()) return null
            val innerText = runCatching { JSONObject(responseJson).optString("d") }
                .getOrDefault(responseJson)

            // Match XML or JSON entries containing company_id & companyname
            val xmlItemRegex = Regex(
                "<Table>.*?<company_id>(.*?)</company_id>.*?<companyname>(.*?)</companyname>.*?</Table>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            for (match in xmlItemRegex.findAll(innerText)) {
                val id = match.groupValues[1].trim()
                val name = match.groupValues[2].trim().lowercase()
                if (id.isNotBlank() && tokens.count { name.contains(it) } >= tokens.size.coerceAtMost(2)) {
                    return id
                }
            }

            val jsonArray = runCatching { JSONArray(innerText) }.getOrNull()
            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val id = obj.optString("company_id").ifBlank { obj.optString("id") }.trim()
                    val name = obj.optString("companyname").ifBlank { obj.optString("name") }.lowercase()
                    if (id.isNotBlank() && tokens.count { name.contains(it) } >= tokens.size.coerceAtMost(2)) {
                        return id
                    }
                }
            }
            return null
        }

        internal fun parseMufgSearchResponse(responseJson: String): RegistrarCheckOutcome? {
            val inner = runCatching { JSONObject(responseJson).optString("d") }
                .getOrDefault(responseJson)
            if (inner.isBlank()) return null

            val allotRegex = Regex("<ALLOT>(.*?)</ALLOT>", RegexOption.IGNORE_CASE)
            val allotRaw = allotRegex.find(inner)?.groupValues?.getOrNull(1)?.trim()
                ?: Regex("\"ALLOT\"\\s*:\\s*\"?(\\d+)\"?", RegexOption.IGNORE_CASE)
                    .find(inner)?.groupValues?.getOrNull(1)?.trim()

            val sharesAllotted = allotRaw?.replace(",", "")?.toIntOrNull()
            if (sharesAllotted != null) {
                return if (sharesAllotted > 0) {
                    RegistrarCheckOutcome(
                        status = AllotmentWatchStatus.ALLOTTED,
                        sharesAllotted = sharesAllotted,
                        summary = "Confirmed via MUFG Intime: $sharesAllotted shares allotted!",
                    )
                } else {
                    RegistrarCheckOutcome(
                        status = AllotmentWatchStatus.NOT_ALLOTTED,
                        sharesAllotted = 0,
                        summary = "Checked MUFG Intime: 0 shares allotted (Not Allotted).",
                    )
                }
            }

            val lower = inner.lowercase()
            if (lower.contains("no record") || lower.contains("not found") || lower.contains("not allotted")) {
                return RegistrarCheckOutcome(
                    status = AllotmentWatchStatus.NOT_ALLOTTED,
                    sharesAllotted = 0,
                    summary = "Checked MUFG Intime: No allotment found for this PAN.",
                )
            }
            return null
        }

        private fun significantCompanyTokens(ipoName: String): List<String> {
            val stopWords = setOf(
                "ltd", "limited", "india", "indian", "ipo", "sme", "private", "pvt",
                "holdings", "industries", "technologies", "services", "corp", "corporation",
            )
            return ipoName.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 3 && it !in stopWords }
                .take(3)
        }
    }
}

class AllotmentRepository(
    private val allotmentDao: AllotmentDao,
    private val checker: RegistrarAllotmentChecker = DirectRegistrarAllotmentChecker(),
) {
    fun observeProfiles(): Flow<List<PrivateUserProfileEntity>> =
        allotmentDao.observeProfiles().map { list -> list.map(::decryptProfile) }

    fun observeWatches(): Flow<List<AllotmentWatchEntity>> = allotmentDao.observeWatches()

    suspend fun getAllProfiles(): List<PrivateUserProfileEntity> =
        allotmentDao.getAllProfiles().map(::decryptProfile)

    suspend fun getAllWatches(): List<AllotmentWatchEntity> = allotmentDao.getAllWatches()

    suspend fun hasPendingWatches(): Boolean {
        return allotmentDao.getAllWatches().any {
            it.status == AllotmentWatchStatus.PENDING || it.status == AllotmentWatchStatus.RESULT_LIVE
        }
    }

    suspend fun saveProfile(
        existingId: String? = null,
        displayName: String,
        panNumber: String,
        dematId: String = "",
        upiHandle: String = "",
    ): Result<PrivateUserProfileEntity> {
        val cleanName = displayName.trim()
        val cleanPan = PanPrivacyUtils.normalizePan(panNumber)
        if (cleanName.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a profile name (e.g., Self, Spouse, Parent)."))
        }
        if (!PanPrivacyUtils.isValidPan(cleanPan)) {
            return Result.failure(IllegalArgumentException("Enter a valid 10-character PAN (e.g., ABCDE1234F)."))
        }

        val existingProfiles = getAllProfiles()
        val duplicatePan = existingProfiles.firstOrNull {
            it.panNumber.equals(cleanPan, ignoreCase = true) && it.id != existingId
        }
        if (duplicatePan != null) {
            return Result.failure(IllegalArgumentException("This PAN is already saved under \"${duplicatePan.displayName}\"."))
        }

        val profileId = existingId ?: UUID.randomUUID().toString()
        val existing = existingId?.let { allotmentDao.getProfileById(it) }
        val plaintextEntity = PrivateUserProfileEntity(
            id = profileId,
            displayName = cleanName,
            panNumber = cleanPan,
            dematId = dematId.trim(),
            upiHandle = upiHandle.trim(),
            isDefault = existing?.isDefault ?: existingProfiles.isEmpty(),
            createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
        )
        allotmentDao.upsertProfile(encryptProfile(plaintextEntity))

        // Sync display name & masked PAN on existing watches belonging to this profile
        allotmentDao.getAllWatches()
            .filter { it.profileId == profileId }
            .forEach { watch ->
                allotmentDao.updateWatch(
                    watch.copy(
                        profileName = plaintextEntity.displayName,
                        maskedPan = plaintextEntity.maskedPan,
                    ),
                )
            }

        return Result.success(plaintextEntity)
    }

    suspend fun deleteProfile(profileId: String) {
        allotmentDao.deleteWatchesForProfile(profileId)
        allotmentDao.deleteProfile(profileId)
    }

    suspend fun trackIpoForProfiles(
        ipo: IpoEntity,
        profileIds: List<String>,
        lotsApplied: Int,
        applicationNumber: String = "",
    ): Result<List<AllotmentWatchEntity>> {
        if (profileIds.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one private profile to track."))
        }
        val created = mutableListOf<AllotmentWatchEntity>()
        val now = System.currentTimeMillis()
        for (profileId in profileIds) {
            val profile = allotmentDao.getProfileById(profileId)?.let(::decryptProfile) ?: continue
            val existing = allotmentDao.findWatchForProfileAndIpo(profile.id, ipo.id)
            val watch = AllotmentWatchEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                profileId = profile.id,
                profileName = profile.displayName,
                maskedPan = profile.maskedPan,
                ipoId = ipo.id,
                ipoName = ipo.companyName,
                registrarName = ipo.registrarName.ifBlank { "Official Registrar" },
                registrarUrl = ipo.registrarUrl.ifBlank { defaultRegistrarPortalUrl(ipo.registrarName) },
                allotmentDate = ipo.allotmentDate,
                listingDate = ipo.listingDate,
                lotsApplied = lotsApplied.coerceAtLeast(1),
                sharesPerLot = ipo.lotSize.coerceAtLeast(1),
                applicationNumber = applicationNumber.trim(),
                status = existing?.status ?: AllotmentWatchStatus.PENDING,
                sharesAllotted = existing?.sharesAllotted ?: 0,
                lastCheckedAtMs = existing?.lastCheckedAtMs ?: 0L,
                lastCheckSummary = existing?.lastCheckSummary
                    ?: "Alert active • Allotment date ${ipo.allotmentDate}",
                notifiedAllotmentLive = existing?.notifiedAllotmentLive ?: false,
                notifiedFinalResult = existing?.notifiedFinalResult ?: false,
                createdAtMs = existing?.createdAtMs ?: now,
            )
            allotmentDao.upsertWatch(watch)
            created += watch
        }
        return Result.success(created)
    }

    suspend fun deleteWatch(watchId: String) {
        allotmentDao.deleteWatch(watchId)
    }

    suspend fun wipeAllPrivateData() {
        allotmentDao.deleteAllWatches()
        allotmentDao.deleteAllProfiles()
    }

    suspend fun checkSingleWatch(
        watchId: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): AllotmentCheckEvent? {
        val watch = allotmentDao.getWatchById(watchId) ?: return null
        val profile = allotmentDao.getProfileById(watch.profileId)?.let(::decryptProfile) ?: return null
        return evaluateAndPersistWatch(watch, profile, referenceTimeMs)
    }

    suspend fun checkAllPendingWatches(
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): AllotmentBatchCheckResult {
        val profilesById = getAllProfiles().associateBy { it.id }
        val watches = allotmentDao.getAllWatches()
            .filter {
                it.status == AllotmentWatchStatus.PENDING || it.status == AllotmentWatchStatus.RESULT_LIVE
            }
        if (watches.isEmpty()) {
            return AllotmentBatchCheckResult(
                checkedCount = 0,
                events = emptyList(),
                summaryMessage = "No pending IPO applications to check.",
            )
        }

        val events = mutableListOf<AllotmentCheckEvent>()
        for (watch in watches) {
            val profile = profilesById[watch.profileId] ?: continue
            events += evaluateAndPersistWatch(watch, profile, referenceTimeMs)
        }

        val allottedCount = events.count { it.watch.status == AllotmentWatchStatus.ALLOTTED }
        val liveCount = events.count { it.watch.status == AllotmentWatchStatus.RESULT_LIVE }
        val summary = when {
            allottedCount > 0 -> "$allottedCount IPO(s) confirmed ALLOTTED!"
            liveCount > 0 -> "$liveCount IPO allotment result(s) live now."
            else -> "Checked ${events.size} application(s) • Waiting for registrar updates."
        }
        return AllotmentBatchCheckResult(
            checkedCount = events.size,
            events = events,
            summaryMessage = summary,
        )
    }

    suspend fun confirmManualStatus(
        watchId: String,
        status: AllotmentWatchStatus,
        sharesAllotted: Int,
    ): AllotmentCheckEvent? {
        val existing = allotmentDao.getWatchById(watchId) ?: return null
        val now = System.currentTimeMillis()
        val finalShares = if (status == AllotmentWatchStatus.ALLOTTED) {
            sharesAllotted.coerceAtLeast(existing.sharesPerLot.coerceAtLeast(1))
        } else {
            0
        }
        val summary = when (status) {
            AllotmentWatchStatus.ALLOTTED -> "Confirmed allotted: $finalShares shares"
            AllotmentWatchStatus.NOT_ALLOTTED -> "Marked as Not Allotted • Refund / UPI unblock by T+2"
            AllotmentWatchStatus.RESULT_LIVE -> "Result live on ${existing.registrarName}"
            AllotmentWatchStatus.PENDING -> "Reset to Pending • Monitoring registrar"
        }
        val shouldNotifyAllotted = status == AllotmentWatchStatus.ALLOTTED && !existing.notifiedFinalResult
        val shouldNotifyNotAllotted = status == AllotmentWatchStatus.NOT_ALLOTTED && !existing.notifiedFinalResult
        val updated = existing.copy(
            status = status,
            sharesAllotted = finalShares,
            lastCheckedAtMs = now,
            lastCheckSummary = summary,
            notifiedAllotmentLive = existing.notifiedAllotmentLive || status != AllotmentWatchStatus.PENDING,
            notifiedFinalResult = status == AllotmentWatchStatus.ALLOTTED || status == AllotmentWatchStatus.NOT_ALLOTTED,
        )
        allotmentDao.updateWatch(updated)
        return AllotmentCheckEvent(
            watch = updated,
            shouldNotifyAllotted = shouldNotifyAllotted,
            shouldNotifyNotAllotted = shouldNotifyNotAllotted,
        )
    }

    private suspend fun evaluateAndPersistWatch(
        watch: AllotmentWatchEntity,
        profile: PrivateUserProfileEntity,
        referenceTimeMs: Long,
    ): AllotmentCheckEvent {
        val outcome = checker.checkStatus(
            watch = watch,
            panNumber = profile.panNumber,
            referenceTimeMs = referenceTimeMs,
        )
        val notifyLive = outcome.status == AllotmentWatchStatus.RESULT_LIVE && !watch.notifiedAllotmentLive
        val notifyAllotted = outcome.status == AllotmentWatchStatus.ALLOTTED && !watch.notifiedFinalResult
        val notifyNotAllotted = outcome.status == AllotmentWatchStatus.NOT_ALLOTTED && !watch.notifiedFinalResult

        val updated = watch.copy(
            status = outcome.status,
            sharesAllotted = outcome.sharesAllotted,
            lastCheckedAtMs = referenceTimeMs,
            lastCheckSummary = outcome.summary,
            notifiedAllotmentLive = watch.notifiedAllotmentLive || notifyLive || notifyAllotted || notifyNotAllotted,
            notifiedFinalResult = watch.notifiedFinalResult || notifyAllotted || notifyNotAllotted,
        )
        allotmentDao.updateWatch(updated)
        return AllotmentCheckEvent(
            watch = updated,
            shouldNotifyLive = notifyLive,
            shouldNotifyAllotted = notifyAllotted,
            shouldNotifyNotAllotted = notifyNotAllotted,
        )
    }

    private fun encryptProfile(profile: PrivateUserProfileEntity): PrivateUserProfileEntity =
        profile.copy(
            panNumber = CryptoBox.encrypt(profile.panNumber),
            dematId = CryptoBox.encrypt(profile.dematId),
            upiHandle = CryptoBox.encrypt(profile.upiHandle),
        )

    private fun decryptProfile(profile: PrivateUserProfileEntity): PrivateUserProfileEntity =
        profile.copy(
            panNumber = decryptField(profile.panNumber),
            dematId = decryptField(profile.dematId),
            upiHandle = decryptField(profile.upiHandle),
        )

    private fun decryptField(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching { CryptoBox.decrypt(stored) }.getOrDefault("")
    }

    companion object {
        fun defaultRegistrarPortalUrl(registrarName: String): String {
            val lower = registrarName.lowercase()
            return when {
                lower.contains("kfin") -> "https://ris.kfintech.com/ipostatus/"
                lower.contains("bigshare") -> "https://ipo.bigshareonline.com/IPO_Status.html"
                lower.contains("skyline") -> "https://www.skylinerta.com/ipo.php"
                lower.contains("cameo") -> "https://ipo.cameoindia.com/"
                lower.contains("maashitla") -> "https://maashitla.com/allotment-status/public-issues"
                else -> "https://in.mpms.mufg.com/Initial_Offer/public-issues.html"
            }
        }
    }
}
