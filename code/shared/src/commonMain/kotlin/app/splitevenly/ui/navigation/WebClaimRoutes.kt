package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.remote.supabase.SyncEngine
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.expense.ClaimProgressState
import app.splitevenly.domain.expense.PendingEditDecision
import app.splitevenly.domain.expense.WebBillLinkState
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.WebBillLinkRepository
import app.splitevenly.platform.PlatformShare
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.screen.bill.BillClaimProgressScreen
import app.splitevenly.ui.screen.bill.BillClaimProgressState
import app.splitevenly.ui.screen.bill.BillReviewEditsScreen
import app.splitevenly.ui.screen.bill.BillShareLinkScreen
import app.splitevenly.ui.screen.bill.ClaimProgressPersonUi
import app.splitevenly.ui.screen.bill.ReviewEditUi
import app.splitevenly.ui.screen.bill.ReviewEditsState
import app.splitevenly.ui.screen.bill.ShareBillLinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The payer's three web-claim screens (WEB_CLAIM_SPEC.md §3.9), wired.
 *
 * All three are **payer-side, in-app only**. Nothing here is reachable from the web bundle, and nothing
 * here talks to the `web-claim` edge function: that function reads links and never writes them. This
 * file is the other half of that boundary.
 *
 * Undo exists on both sides — anyone on the bill may take a change back (§2.7) — but the two halves get
 * there differently and deliberately so. A guest's undo goes through `undo_web_bill_edit`, because the
 * edge function has a service key and no `auth.uid()`. The payer's is a local-first Room write that
 * reaches the server through `merge_expense`, like every other in-app money edit.
 */

/** Poll cadence for the payer's watch screen (§3.9.2). Matches the guest's 5s so the two stay in step. */
private const val PROGRESS_POLL_MS = 5_000L

/**
 * Device-local marker for "this device has seen the bill's changes up to here" (`WEB_CLAIM_PATCH_PLAN`
 * §3 P4). Keyed by expense, in [app.splitevenly.platform.SecureStorage], exactly like
 * `CLAIM_GUIDE_OPENS_KEY` in `BillRoutes.kt`.
 *
 * Chosen over a synced column deliberately: a new column on a synced entity is the heaviest change in
 * this codebase (`AGENTS.md` §4.4) and the cost of getting it wrong is that whole table's sync, for a
 * banner. The tradeoff is that the payer's second device nags again about changes they have already
 * seen. That is acceptable for a payer, and the decision is reversible later.
 */
internal fun changesSeenKey(expenseId: String) = "web_changes_seen_at__$expenseId"

// ── 1. What changed ──────────────────────────────────────────────────────────────────────────────

/**
 * "N changes to the bill" — what web guests changed, and the way to take any of it back (§3.9.1).
 *
 * Two things happen here. The screen **marks the changes seen**, which is what clears the banner on the
 * assign screen: the changes have already applied, so there is nothing to decide and reading is the only
 * completion there is. And an undo goes through the repository, which restores the line, advances
 * `split_version` and re-derives; the Composable only ever names an id.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun BillReviewEditsRoute(groupId: String, expenseId: String, onBack: () -> Unit) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val storage = koinInject<SecureStorage>()
    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val edits by remember(eid) { bills.observePendingEdits(eid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }

    // Being on this screen IS having seen them, so the marker moves as the list does rather than on the
    // way out. `SecureStorage.putString` suspends, and `onDispose` cannot; doing it here also means a
    // change that lands while the payer is reading is marked seen the moment it renders, which is the
    // truth. Worst case the marker is missed and the banner shows once more, which is the cheap
    // direction to fail in.
    val newest = edits.maxOfOrNull { it.proposedAt } ?: 0L
    LaunchedEffect(eid, newest) {
        if (newest > 0L) storage.putString(changesSeenKey(eid.value), newest.toString())
    }

    val view = bill ?: return
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    val labelByItem = view.items.associate { it.id to it.label }

    val state = ReviewEditsState(
        billTitle = view.expense.title,
        currency = view.expense.currency,
        currentTotalSubunits = view.expense.amountSubunits,
        edits = edits.map { edit ->
            ReviewEditUi(
                id = edit.id,
                proposerName = nameByUser[edit.proposedBy.value] ?: "Someone",
                kind = edit.kind,
                // An ADD has no line yet, so its label is the proposal itself; every other kind names an
                // existing line, and falls back to the label captured at proposal time if the payer has
                // since removed it.
                itemLabel = edit.proposedLabel
                    ?: edit.itemId?.let { labelByItem[it] }
                    ?: edit.previousLabel
                    ?: "an item",
                previousLabel = edit.previousLabel ?: edit.itemId?.let { labelByItem[it] },
                previousQuantity = edit.previousQuantity,
                previousUnitPriceSubunits = edit.previousUnitPriceSubunits,
                proposedQuantity = edit.proposedQuantity,
                proposedUnitPriceSubunits = edit.proposedUnitPriceSubunits,
                deltaSubunits = edit.totalDeltaSubunits,
                decision = edit.decision,
                // `decided_by` is set on an APPLIED row too (it is the guest who made the change), so
                // this is gated on the decision rather than on the column being present.
                undoneByName = edit.decidedBy
                    ?.takeIf { edit.decision == PendingEditDecision.UNDONE }
                    ?.let { nameByUser[it.value] },
            )
        },
    )

    fun undo(editId: String) {
        val me = userId ?: return
        scope.launch {
            // An undo that didn't save has to say so: the card would otherwise stay put with no reason,
            // which reads as a broken button rather than a failed write.
            if (bills.undoPendingEdit(editId, me) is AppResult.Err) {
                notice = "Couldn't save that. Check your connection and tap again."
            }
        }
    }

    BillReviewEditsScreen(
        state = state,
        onBack = onBack,
        onUndo = ::undo,
        notice = notice,
        onDismissNotice = { notice = null },
    )
}

// ── 2. Who's still to claim ──────────────────────────────────────────────────────────────────────

/**
 * The payer's watch screen (§3.9.2). Two things are polled and they are not the same thing: a `syncNow`
 * pulls the claims themselves, and `WebBillLinkRepository.status` supplies the one fact Room cannot hold
 * (whether a guest's browser has a live session), because `web_sessions` is service-key-only by design.
 *
 * Polling stops when the screen leaves composition. It is deliberately not wired to the realtime
 * doorbell: the doorbell only says "pull", and this screen additionally needs the session read.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun BillClaimProgressRoute(
    groupId: String,
    expenseId: String,
    onBack: () -> Unit,
    onShareLink: () -> Unit,
) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val links = koinInject<WebBillLinkRepository>()
    val auth = koinInject<AuthSession>()
    val koin = getKoin()
    val syncEngine = remember { koin.getOrNull<SyncEngine>() }
    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var linkState by remember { mutableStateOf(WebBillLinkState.None) }
    var lastUpdatedAt by remember { mutableStateOf<Long?>(null) }
    var nowMs by remember { mutableStateOf(Clock.System.nowEpochMillis()) }
    var notice by remember { mutableStateOf<String?>(null) }

    val me = userId
    LaunchedEffect(eid, me) {
        val actor = me ?: return@LaunchedEffect
        while (true) {
            syncEngine?.syncNow(actor.value)
            when (val result = links.status(eid, actor)) {
                is AppResult.Ok -> {
                    linkState = result.value
                    lastUpdatedAt = Clock.System.nowEpochMillis()
                }
                // Don't blank the list on a blip: the numbers on screen are still the last true ones, and
                // the "updated N ago" line is what tells the payer they're going stale.
                is AppResult.Err -> Unit
            }
            nowMs = Clock.System.nowEpochMillis()
            delay(PROGRESS_POLL_MS)
        }
    }

    val view = bill ?: return
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    val placeholderIds = members.filter { it.isPlaceholder }.mapTo(HashSet()) { it.userId.value }
    val claimedUserIds = (view.claims.map { it.userId.value } + view.shares.map { it.userId.value }).toHashSet()

    val claimed = view.tabByUser.values.sum()
    val total = view.expense.amountSubunits
    val outstanding = view.participants
        .map { it.userId }
        .filter { it.value !in claimedUserIds }
        .map { uid ->
            ClaimProgressPersonUi(
                userId = uid.value,
                name = nameByUser[uid.value] ?: "Someone",
                state = when {
                    linkState.openedThisLink(uid) -> ClaimProgressState.OPENED_NOTHING_CLAIMED
                    // "Hasn't opened the link" is only honest about someone who has no other way in.
                    uid.value in placeholderIds -> ClaimProgressState.NOT_OPENED
                    else -> ClaimProgressState.APP_MEMBER_NOT_CLAIMED
                },
            )
        }

    val state = BillClaimProgressState(
        billTitle = view.expense.title,
        currency = view.expense.currency,
        billTotalSubunits = total,
        claimedSubunits = claimed,
        outstanding = outstanding,
        unclaimedSubunits = (total - claimed).coerceAtLeast(0L),
        unclaimedItemCount = view.unclaimedCount,
        lastUpdatedAgoMs = lastUpdatedAt?.let { (nowMs - it).coerceAtLeast(0L) },
    )

    fun assign(memberIds: List<UserId>) {
        val actor = me ?: return
        if (memberIds.isEmpty()) {
            notice = "There's nobody left to give it to."
            return
        }
        scope.launch {
            if (bills.assignRemainder(eid, memberIds, actor) is AppResult.Err) {
                notice = "Couldn't save that. Check your connection and tap again."
            }
        }
    }

    BillClaimProgressScreen(
        state = state,
        onBack = onBack,
        onSplitBetweenOutstanding = { assign(outstanding.map { UserId(it.userId) }) },
        onSplitAcrossEveryone = { assign(view.participants.map { it.userId }) },
        onShareLink = onShareLink,
        notice = notice,
        onDismissNotice = { notice = null },
    )
}

// ── 3. Share the link ────────────────────────────────────────────────────────────────────────────

/** Milliseconds in an hour, for the "open for N hours" line. */
private const val HOUR_MS = 3_600_000L

/**
 * "Let everyone claim" — the QR, the link, and the link's lifecycle (§3.9.3, E26–E30).
 *
 * Every action here is a live server round trip and reports its own failure. A link is an
 * authorisation: minting one offline hands out a QR nothing can validate, and revoking one offline tells
 * the payer a link is dead while guests keep writing to it. Both are worse than a spinner.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ShareBillLinkRoute(
    groupId: String,
    expenseId: String,
    onBack: () -> Unit,
    onInviteToGroup: () -> Unit,
) {
    val bills = koinInject<BillRepository>()
    val links = koinInject<WebBillLinkRepository>()
    val auth = koinInject<AuthSession>()
    val share = koinInject<PlatformShare>()
    val clipboard = LocalClipboardManager.current
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var linkState by remember { mutableStateOf(WebBillLinkState.None) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val me = userId
    LaunchedEffect(eid, me) {
        val actor = me ?: return@LaunchedEffect
        busy = true
        when (val result = links.status(eid, actor)) {
            is AppResult.Ok -> linkState = result.value
            is AppResult.Err -> notice = "Couldn't check the link. Check your connection."
        }
        busy = false
    }

    val view = bill ?: return
    val now = Clock.System.nowEpochMillis()
    val state = ShareBillLinkState(
        billTitle = view.expense.title,
        url = linkState.url,
        exists = linkState.exists,
        revoked = linkState.revokedAt != null,
        hoursLeft = linkState.expiresAt?.let { ((it - now) / HOUR_MS).coerceAtLeast(0L) },
        busy = busy,
    )

    /** Every lifecycle action is the same shape: try it, adopt the returned state, or say it failed. */
    fun act(failure: String, block: suspend (UserId) -> AppResult<WebBillLinkState>) {
        val actor = me ?: return
        busy = true
        scope.launch {
            when (val result = block(actor)) {
                is AppResult.Ok -> {
                    linkState = result.value
                    notice = null
                }
                is AppResult.Err -> notice = failure
            }
            busy = false
        }
    }

    BillShareLinkScreen(
        state = state,
        onBack = onBack,
        onCreate = { act("Couldn't make the link. Check your connection and try again.") { links.create(eid, it) } },
        onExtend = { act("Couldn't extend the link. Check your connection and try again.") { links.extend(eid, it) } },
        onRevoke = { act("Couldn't turn the link off. Check your connection and try again.") { links.revoke(eid, it) } },
        onShare = {
            linkState.url?.let {
                share.shareText("Claim what you had on ${view.expense.title}: $it", view.expense.title)
            }
        },
        onCopy = { linkState.url?.let { clipboard.setText(AnnotatedString(it)) } },
        onInviteToGroup = onInviteToGroup,
        notice = notice,
        onDismissNotice = { notice = null },
    )
}
