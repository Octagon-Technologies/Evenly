package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.ReceiptId
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.data.upload.StagedReceipt
import app.splitevenly.domain.activity.Receipt
import app.splitevenly.domain.repository.ActivityRepository
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.PdfRasterizer
import app.splitevenly.platform.PickKind
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.screen.expense.PickedReceiptUi
import app.splitevenly.ui.screen.expense.UndoRemoval
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * The receipts of an expense being edited: the saved ones, which can now be removed from the editor, plus
 * any picked during this edit, which attach on save. A removed saved receipt disappears at once and is
 * soft-deleted only after the strip's Undo window ([DeferredRemovals]).
 */
internal class EditExpenseReceipts(
    private val saved: List<Receipt>,
    private val hidden: Set<String>,
    private val picked: List<StagedReceipt>,
    val enabled: Boolean,
    val remove: (Int) -> UndoRemoval,
    val pick: (PickSource) -> Unit,
    val attachOnSave: () -> Unit,
    val loadPdfPageCount: suspend (url: String) -> Int,
    val renderPdfPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap?,
) {
    val ui: List<PickedReceiptUi> =
        saved.filter { it.id.value !in hidden }.map {
            PickedReceiptUi(
                id = it.id.value,
                localPath = "",
                model = if (it.isPdf) null else it.url,
                isPdf = it.isPdf,
                remoteUrl = it.url,
            )
        } + picked.map { it.toUi() }
}

@Composable
internal fun rememberEditExpenseReceipts(
    eid: ExpenseId,
    gid: GroupId,
): EditExpenseReceipts {
    val activity = koinInject<ActivityRepository>()
    val filePicker = koinInject<FilePicker>()
    val http = koinInject<HttpClient>()
    val koin = getKoin()
    val uploadManager = remember { koin.getOrNull<ReceiptUploadManager>() }
    val stagedPdf = rememberStagedPdfRenderers(uploadManager)
    val rasterizer = remember { PdfRasterizer() }
    val remotePdfBytes = remember { mutableMapOf<String, ByteArray>() }
    val deferred = remember { DeferredRemovals() }
    val scope = rememberCoroutineScope()
    val saved by remember(eid) { activity.observeReceipts(eid) }.collectAsStateWithLifecycle(emptyList())
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var picked by remember { mutableStateOf(emptyList<StagedReceipt>()) }
    var attached by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (!attached) uploadManager?.discardStagedDetached(picked) }
    }

    suspend fun remotePdf(url: String): ByteArray? =
        remotePdfBytes[url] ?: runCatching { http.get(url).readRawBytes() }.getOrNull()?.also { remotePdfBytes[url] = it }

    val visibleSaved = saved.filter { it.id.value !in hidden }
    return EditExpenseReceipts(
        saved = saved,
        hidden = hidden,
        picked = picked,
        enabled = uploadManager != null,
        remove = { i ->
            if (i < visibleSaved.size) {
                val id = visibleSaved[i].id.value
                hidden = hidden + id
                deferred.schedule(id) { activity.deleteReceipt(ReceiptId(id)) }
                ({ if (deferred.cancel(id)) hidden = hidden - id })
            } else {
                val at = i - visibleSaved.size
                val dropped = picked.getOrNull(at)
                if (dropped == null) {
                    ({})
                } else {
                    picked = picked - dropped
                    deferred.schedule(dropped.id) { uploadManager?.discardStaged(listOf(dropped)) }
                    ({ if (deferred.cancel(dropped.id)) picked = picked.toMutableList().apply { add(at.coerceAtMost(size), dropped) } })
                }
            }
        },
        pick = { source ->
            scope.launch {
                val result = filePicker.pick(source, PickKind.ImageOrPdf)
                if (result is AppResult.Ok) picked = picked + uploadManager?.stage(result.value).orEmpty()
            }
        },
        attachOnSave = {
            attached = true
            val fresh = picked
            // The editor pops on save, cancelling its scope; the bookkeeping must still land.
            if (fresh.isNotEmpty()) CoroutineScope(Dispatchers.Default).launch { uploadManager?.attach(eid, gid, fresh) }
        },
        loadPdfPageCount = { url ->
            if (url.startsWith("file://")) stagedPdf.pageCount(url) else remotePdf(url)?.let { rasterizer.pageCount(it) } ?: 0
        },
        renderPdfPage = { url, page, width ->
            if (url.startsWith("file://")) stagedPdf.renderPage(url, page, width) else remotePdf(url)?.let { rasterizer.renderPage(it, page, width) }
        },
    )
}
