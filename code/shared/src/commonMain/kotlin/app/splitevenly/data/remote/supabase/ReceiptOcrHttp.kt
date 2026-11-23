package app.splitevenly.data.remote.supabase

import app.splitevenly.data.upload.AccessTokenProvider
import app.splitevenly.domain.receipt.ReceiptDraft
import app.splitevenly.domain.receipt.ReceiptDraftItem
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.receipt.ReceiptOcrFile
import app.splitevenly.domain.receipt.ReceiptSource
import app.splitevenly.domain.receipt.ReceiptSummary
import app.splitevenly.domain.receipt.ScanOutcome
import app.splitevenly.domain.receipt.venueKindOf
import app.splitevenly.platform.ConnectivityObserver
import app.splitevenly.platform.ImageProcessor
import app.splitevenly.platform.NetworkStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Calls the `extract-receipt` edge function (Claude vision OCR) over HTTP with the anon key. Every failure
 * mode is a distinct [ScanOutcome] the editor can surface: it pre-checks connectivity (so an offline scan
 * short-circuits to [ScanOutcome.Offline] without a doomed upload), reports [ScanOutcome.Unavailable] when
 * Supabase isn't configured, [ScanOutcome.NoReceiptFound] when the vision pass yields nothing usable, and
 * [ScanOutcome.Failed] on a network/server error. The manual-entry path stays reachable in every case.
 */
class ReceiptOcrHttp(
    private val http: HttpClient,
    private val connectivity: ConnectivityObserver,
    // Optional-ctor-dep pattern: production DI passes the real compressor; tests may omit it (raw bytes).
    private val imageProcessor: ImageProcessor? = null,
    // Supplies the signed-in user's access token so the server can identify the caller and enforce the
    // per-user OCR rate limit (P1 #11). Without it the server saw only the anon key, resolved no user, and
    // skipped the limit entirely — anyone with the shipped anon key got unlimited paid Claude-vision calls.
    private val accessTokenProvider: AccessTokenProvider? = null,
) : ReceiptOcr {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun extract(
        files: List<ReceiptOcrFile>,
        groupId: String?,
    ): ScanOutcome {
        if (files.isEmpty()) return ScanOutcome.NoReceiptFound
        if (!SupabaseConfig.isConfigured) return ScanOutcome.Unavailable
        // Don't burn a doomed round-trip (or leave the user staring at a spinner) when there's no network.
        if (connectivity.status.first() == NetworkStatus.Offline) return ScanOutcome.Offline
        // Authenticate AS THE USER (not the anon key) so the server-side rate limit engages. No session ⇒
        // the server can't attribute the scan, so don't spend a paid call — surface Unavailable (P1 #11).
        val userToken = accessTokenProvider?.token() ?: return ScanOutcome.Unavailable
        return try {
            val response = post(ExtractReq(files = encodeParts(files), groupId = groupId), userToken)
            val raw = response.bodyAsText()
            // The rate limit (P1 #11) turns the request away with a 429 BEFORE any vision call — a
            // distinct "you're not allowed right now" outcome, not a generic failure. Any other non-2xx
            // (400/502/503) is a real failure: the body is `{error: "..."}`, which doesn't match
            // ExtractResp's shape, so decode it separately rather than let ExtractResp's all-optional
            // fields silently default to a fake "no receipt found" success shape.
            if (!response.status.isSuccess()) {
                if (response.status == HttpStatusCode.TooManyRequests) return ScanOutcome.Blocked(reason = "rate_limited")
                val message = runCatching { json.decodeFromString<ErrorResp>(raw).error }.getOrNull()
                return ScanOutcome.Failed(message)
            }
            val resp = json.decodeFromString<ExtractResp>(raw)
            when {
                !resp.configured -> ScanOutcome.Unavailable

                // The server's own is_receipt classifier decided this photo isn't a receipt at all (a
                // selfie, a ride-share summary, an unrelated screenshot) — short-circuited before any
                // Sonnet/Opus escalation, so this is always a single cheap call regardless of retries.
                resp.noReceipt -> ScanOutcome.NoReceiptFound

                resp.receipt == null || resp.receipt.items.isEmpty() -> ScanOutcome.NoReceiptFound

                else -> ScanOutcome.Success(resp.receipt.toDraft(resp.verified), scanId = resp.scanId)
            }
        } catch (e: CancellationException) {
            // Never swallow structured-concurrency cancellation. The user tapping Cancel cancels
            // `scanJob` mid-request; catching it here let the (already cancelled) coroutine carry on
            // through the non-suspending tail — flipping the sheet from Idle to an error the user did
            // not cause, and counting every cancellation as a scan failure in the OCR funnel.
            throw e
        } catch (t: Throwable) {
            ScanOutcome.Failed(t.message)
        }
    }

    override suspend fun summarize(
        files: List<ReceiptOcrFile>,
        groupId: String?,
    ): ReceiptSummary? {
        if (files.isEmpty() || !SupabaseConfig.isConfigured) return null
        if (connectivity.status.first() == NetworkStatus.Offline) return null
        val userToken = accessTokenProvider?.token() ?: return null
        return try {
            val response = post(ExtractReq(files = encodeParts(files), groupId = groupId, mode = "summary"), userToken)
            if (!response.status.isSuccess()) return null
            json.decodeFromString<SummaryResp>(response.bodyAsText()).summary?.toDomain()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    // Downscale + re-encode each image BEFORE base64 (PDFs pass through). A raw phone-camera photo is several
    // MB and, once base64-inflated ~33%, blows past Anthropic vision's 5 MB/image limit, so the scan "just
    // fails" on real devices and never on the emulator. 2048px rather than the 1600 upload default keeps faint
    // thermal-receipt print legible.
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun encodeParts(files: List<ReceiptOcrFile>): List<ExtractPart> =
        files.map { file ->
            val processed = imageProcessor?.compress(file.bytes, file.mimeType, maxDimension = 2048)
            if (processed != null) {
                ExtractPart(Base64.encode(processed.bytes), processed.mimeType)
            } else {
                ExtractPart(Base64.encode(file.bytes), file.mimeType)
            }
        }

    // Bearer = the USER's token (so auth.getUser() resolves them and the per-user rate limit engages); apikey
    // stays the anon key, which is what the Supabase gateway checks to admit the request at all.
    private suspend fun post(
        req: ExtractReq,
        userToken: String,
    ) = http.post("${SupabaseConfig.URL}/functions/v1/extract-receipt") {
        header("Authorization", "Bearer $userToken")
        header("apikey", SupabaseConfig.ANON_KEY)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(req))
    }
}

/** One receipt page sent to the edge function: base64 bytes + its MIME type (an image type or application/pdf). */
@Serializable
private class ExtractPart(
    val data: String,
    val mediaType: String,
)

/** The multi-page request; the edge function reads every part together as one bill. [groupId] is
 *  analytics-only (Plan A attributes the scan to a group) — the OCR itself doesn't need it. */
@Serializable
private class ExtractReq(
    val files: List<ExtractPart>,
    val groupId: String? = null,
    /** "summary" asks for the quick read; null (omitted from the JSON) is the itemized scan. */
    val mode: String? = null,
)

@Serializable
private class SummaryResp(
    val summary: SummaryDto? = null,
)

@Serializable
private class SummaryDto(
    val source: String? = null,
    @SerialName("merchant_name") val merchantName: String? = null,
    @SerialName("expense_kind") val expenseKind: String? = null,
    val category: String? = null,
    @SerialName("printed_time") val printedTime: String? = null,
    @SerialName("printed_date") val printedDate: String? = null,
    @SerialName("total_subunits") val totalSubunits: Long = 0,
    val currency: String? = null,
    @SerialName("short_title") val shortTitle: String? = null,
) {
    fun toDomain() =
        ReceiptSummary(
            source = ReceiptSource.entries.firstOrNull { it.name.equals(source, ignoreCase = true) } ?: ReceiptSource.OTHER,
            merchantName = merchantName,
            kind = venueKindOf(expenseKind),
            categoryKey = category,
            printedTime = printedTime,
            printedDate = printedDate,
            totalSubunits = totalSubunits.coerceAtLeast(0),
            currency = currency,
            shortTitle = shortTitle,
        )
}

@Serializable
private class ExtractResp(
    val configured: Boolean = true,
    val receipt: RcptDto? = null,
    // True unless no tier produced a draft whose server-side sum matched the receipt's printed total —
    // absent (defaults true) on a normal single-tier success response.
    val verified: Boolean = true,
    // The server's is_receipt classifier decided this isn't a receipt photo at all — short-circuited
    // before any escalation, so `receipt` is absent on this response.
    val noReceipt: Boolean = false,
    // The server's id for this scan (Plan A). Nullable/absent until that side deploys; attached to
    // scan_completed as an analytics property only when present.
    val scanId: String? = null,
)

/** The shape of a non-2xx response body — `{"error": "..."}` per the edge function's `json()` helper. */
@Serializable
private class ErrorResp(
    val error: String? = null,
)

@Serializable
private class RcptDto(
    val currency: String = "USD",
    val items: List<ItemDto> = emptyList(),
    @SerialName("tax_subunits") val tax: Long = 0,
    @SerialName("gratuity_subunits") val gratuity: Long = 0,
    @SerialName("tip_subunits") val tip: Long = 0,
    @SerialName("discount_subunits") val discount: Long = 0,
    @SerialName("other_charges_subunits") val otherCharges: Long = 0,
    @SerialName("detected_total_subunits") val detectedTotal: Long = 0,
    @SerialName("merchant_name") val merchantName: String? = null,
    @SerialName("venue_kind") val venueKind: String? = null,
    @SerialName("printed_time") val printedTime: String? = null,
    @SerialName("printed_date") val printedDate: String? = null,
) {
    fun toDraft(verified: Boolean) =
        ReceiptDraft(
            currency = currency,
            items = items.map { ReceiptDraftItem(it.label, it.quantity.coerceAtLeast(1), it.lineTotal) },
            taxSubunits = tax,
            gratuitySubunits = gratuity,
            tipSubunits = tip,
            discountSubunits = discount,
            otherChargesSubunits = otherCharges,
            detectedTotalSubunits = detectedTotal,
            verified = verified,
            merchantName = merchantName,
            venueKind = venueKindOf(venueKind),
            printedTime = printedTime,
            printedDate = printedDate,
        )
}

@Serializable
private class ItemDto(
    val label: String = "",
    val quantity: Int = 1,
    @SerialName("line_total_subunits") val lineTotal: Long = 0,
)
