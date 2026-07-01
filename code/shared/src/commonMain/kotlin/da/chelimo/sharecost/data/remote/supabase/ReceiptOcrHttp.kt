package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.domain.receipt.ReceiptDraft
import da.chelimo.sharecost.domain.receipt.ReceiptDraftItem
import da.chelimo.sharecost.domain.receipt.ReceiptOcr
import da.chelimo.sharecost.domain.receipt.ReceiptOcrFile
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Calls the `extract-receipt` edge function (Claude vision OCR) over HTTP with the anon key. Degrades
 * gracefully: when Supabase isn't configured, or the function is inert / errors, it returns `Ok(null)`
 * so the bill editor simply falls back to manual entry rather than surfacing a failure.
 */
class ReceiptOcrHttp(private val http: HttpClient) : ReceiptOcr {

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun extract(files: List<ReceiptOcrFile>): AppResult<ReceiptDraft?> {
        if (!SupabaseConfig.isConfigured || files.isEmpty()) return AppResult.Ok(null)
        return try {
            val parts = files.map { ExtractPart(Base64.encode(it.bytes), it.mimeType) }
            val body = json.encodeToString(ExtractReq(files = parts))
            val raw = http.post("${SupabaseConfig.URL}/functions/v1/extract-receipt") {
                header("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()
            val resp = json.decodeFromString<ExtractResp>(raw)
            AppResult.Ok(resp.receipt?.toDraft())
        } catch (_: Throwable) {
            AppResult.Ok(null) // never block the manual path on an OCR hiccup
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
        items = items.map { ReceiptDraftItem(it.label, it.quantity.coerceAtLeast(1), it.unitPrice) },
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
    @SerialName("unit_price_subunits") val unitPrice: Long = 0,
)
