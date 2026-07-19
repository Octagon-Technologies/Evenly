package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.domain.receipt.ReceiptDraft
import da.chelimo.sharecost.domain.receipt.ReceiptDraftItem
import da.chelimo.sharecost.domain.receipt.ReceiptOcr
import da.chelimo.sharecost.domain.receipt.ReceiptOcrFile
import da.chelimo.sharecost.domain.receipt.ScanOutcome
import da.chelimo.sharecost.data.upload.AccessTokenProvider
import da.chelimo.sharecost.platform.ConnectivityObserver
import da.chelimo.sharecost.platform.ImageProcessor
import da.chelimo.sharecost.platform.NetworkStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun extract(files: List<ReceiptOcrFile>): ScanOutcome {
        if (files.isEmpty()) return ScanOutcome.NoReceiptFound
        if (!SupabaseConfig.isConfigured) return ScanOutcome.Unavailable
        // Don't burn a doomed round-trip (or leave the user staring at a spinner) when there's no network.
        if (connectivity.status.first() == NetworkStatus.Offline) return ScanOutcome.Offline
        // Authenticate AS THE USER (not the anon key) so the server-side rate limit engages. No session ⇒
        // the server can't attribute the scan, so don't spend a paid call — surface Unavailable (P1 #11).
        val userToken = accessTokenProvider?.token() ?: return ScanOutcome.Unavailable
        return try {
            // Downscale + re-encode each image BEFORE base64 (PDFs pass through). A raw phone-camera photo is
            // several MB and, once base64-inflated ~33%, blows past Anthropic vision's 5 MB/image limit — the
            // edge function then relays a 400 as a 502 and the scan "just fails" on real devices (never on the
            // emulator, whose synthetic image is tiny). Compressing here keeps every caller under the limit.
            val parts = files.map { file ->
                // OCR needs legible text, not a small upload: downscale to 2048px (not the 1600 upload
                // default) so faint thermal-receipt print survives. Still well under Anthropic vision's
                // 5 MB/image limit once base64-inflated, so it never trips the 400-relayed-as-502 path.
                val processed = imageProcessor?.compress(file.bytes, file.mimeType, maxDimension = 2048)
                if (processed != null) ExtractPart(Base64.encode(processed.bytes), processed.mimeType)
                else ExtractPart(Base64.encode(file.bytes), file.mimeType)
            }
            val body = json.encodeToString(ExtractReq(files = parts))
            val raw = http.post("${SupabaseConfig.URL}/functions/v1/extract-receipt") {
                // Bearer = the USER's token (so auth.getUser() resolves them); apikey stays the anon key,
                // which is what the Supabase gateway checks to admit the request at all.
                header("Authorization", "Bearer $userToken")
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()
            val resp = json.decodeFromString<ExtractResp>(raw)
            when {
                !resp.configured -> ScanOutcome.Unavailable
                resp.receipt == null || resp.receipt.items.isEmpty() -> ScanOutcome.NoReceiptFound
                else -> ScanOutcome.Success(resp.receipt.toDraft())
            }
        } catch (t: Throwable) {
            ScanOutcome.Failed(t.message)
        }
    }
}

/** One receipt page sent to the edge function: base64 bytes + its MIME type (an image type or application/pdf). */
@Serializable
private class ExtractPart(val data: String, val mediaType: String)

/** The multi-page request; the edge function reads every part together as one bill. */
@Serializable
private class ExtractReq(val files: List<ExtractPart>)

@Serializable
private class ExtractResp(val configured: Boolean = true, val receipt: RcptDto? = null)

@Serializable
private class RcptDto(
    val currency: String = "USD",
    val items: List<ItemDto> = emptyList(),
    @SerialName("tax_subunits") val tax: Long = 0,
    @SerialName("gratuity_subunits") val gratuity: Long = 0,
    @SerialName("tip_subunits") val tip: Long = 0,
    @SerialName("discount_subunits") val discount: Long = 0,
    @SerialName("detected_total_subunits") val detectedTotal: Long = 0,
) {
    fun toDraft() = ReceiptDraft(
        currency = currency,
        items = items.map { ReceiptDraftItem(it.label, it.quantity.coerceAtLeast(1), it.lineTotal) },
        taxSubunits = tax,
        gratuitySubunits = gratuity,
        tipSubunits = tip,
        discountSubunits = discount,
        detectedTotalSubunits = detectedTotal,
    )
}

@Serializable
private class ItemDto(
    val label: String = "",
    val quantity: Int = 1,
    @SerialName("line_total_subunits") val lineTotal: Long = 0,
)
