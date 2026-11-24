package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.data.upload.StagedReceipt
import app.splitevenly.domain.activity.PaymentProof
import app.splitevenly.domain.activity.ReceiptUpload
import app.splitevenly.domain.repository.ActivityRepository
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.PickKind
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.screen.settle.PaymentUi
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * Payment proof for one group (docs/features/payment-proof.md), as a Route wrapper needs it: the live
 * screenshots and in-flight uploads keyed by payment chain id, and the pick / attach / remove actions.
 * [available] is false on the offline build, where there is no upload pipeline to carry the bytes.
 */
class PaymentProofs internal constructor(
    private val picker: FilePicker,
    private val uploads: ReceiptUploadManager?,
    private val activity: ActivityRepository,
    private val groupId: GroupId,
    private val viewer: UserId?,
    private val proofs: Map<String, PaymentProof>,
    private val pending: Map<String, ReceiptUpload>,
) {
    val available: Boolean get() = uploads != null

    /** [ui] with its screenshot, or the local copy while that is still uploading. */
    fun decorate(ui: PaymentUi): PaymentUi {
        val proof = proofs[ui.chainId]
        val up = pending[ui.chainId]
        return ui.copy(
            proofId = proof?.id,
            proofUrl = proof?.url,
            proofMine = proof != null && proof.uploadedBy == viewer,
            // A failed upload keeps showing as in flight: the queue retries it on the next reconnect.
            proofUploadingModel = up?.let { "file://${it.localPath}" },
        )
    }

    /** Opens the Photos picker and compresses the pick into the sandbox; null when cancelled. */
    suspend fun pick(): StagedReceipt? {
        val manager = uploads ?: return null
        val picked = (picker.pick(PickSource.Photos, PickKind.Image) as? AppResult.Ok)?.value.orEmpty()
        if (picked.isEmpty()) return null
        return manager.stage(picked.take(1)).firstOrNull()
    }

    /** Queues [staged] as the screenshot of the payment chain [chainId]. */
    suspend fun attach(
        chainId: String,
        staged: StagedReceipt,
    ) {
        uploads?.attachProof(chainId, groupId, listOf(staged))
    }

    /** Pick, then attach straight away: for a payment that is already recorded. */
    suspend fun pickAndAttach(chainId: String) {
        pick()?.let { attach(chainId, it) }
    }

    suspend fun remove(proofId: String) {
        activity.removePaymentProof(proofId)
    }

    fun discard(staged: StagedReceipt) {
        uploads?.discardStagedDetached(listOf(staged))
    }
}

@Composable
fun rememberPaymentProofs(
    groupId: GroupId,
    viewer: UserId?,
): PaymentProofs {
    val picker = koinInject<FilePicker>()
    val activity = koinInject<ActivityRepository>()
    val koin = getKoin()
    val uploads = remember { koin.getOrNull<ReceiptUploadManager>() }
    val proofs by remember(groupId) { activity.observePaymentProofs(groupId) }.collectAsStateWithLifecycle(emptyMap())
    val pending by remember(groupId) {
        uploads?.observeProofUploads(groupId) ?: flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
    return PaymentProofs(picker, uploads, activity, groupId, viewer, proofs, pending)
}

/**
 * A screenshot picked on a payment sheet before the payment exists. It is the sheet's to clean up until
 * [StagedProof.consume] hands it to the upload queue, including when the sheet is left by system back
 * (data/AGENTS.md: these editors have no BackHandler, so cleanup hangs off disposal).
 */
class StagedProof internal constructor(
    private val get: () -> StagedReceipt?,
    private val set: (StagedReceipt?) -> Unit,
) {
    val current: StagedReceipt? get() = get()

    /** The thumbnail model for the sheet's tile. */
    val model: String? get() = current?.localPath?.let { "file://$it" }

    fun replace(
        next: StagedReceipt?,
        proofs: PaymentProofs,
    ) {
        current?.let { proofs.discard(it) }
        set(next)
    }

    /** Hand the staged copy over (it now belongs to the queue) and forget it. */
    fun consume(): StagedReceipt? = current.also { set(null) }
}

@Composable
fun rememberStagedProof(proofs: PaymentProofs): StagedProof {
    var staged by remember { mutableStateOf<StagedReceipt?>(null) }
    val holder = remember { StagedProof({ staged }, { staged = it }) }
    DisposableEffect(Unit) {
        onDispose { holder.current?.let { proofs.discard(it) } }
    }
    return holder
}
