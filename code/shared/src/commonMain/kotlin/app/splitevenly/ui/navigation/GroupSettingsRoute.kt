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
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.shortDate
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.export.ExportOutcome
import app.splitevenly.domain.export.GroupExporter
import app.splitevenly.domain.pro.ProSource
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProRepository
import app.splitevenly.platform.PlatformShare
import app.splitevenly.platform.ProTriggers
import app.splitevenly.ui.screen.pro.ExportNeedsProSheet
import app.splitevenly.ui.screen.settings.GroupDeleteImpactUi
import app.splitevenly.ui.screen.settings.GroupSettingsScreen
import app.splitevenly.ui.screen.settings.MemberRowUi
import app.splitevenly.ui.screen.settings.ProStatusUi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Group settings, wired: streams the group + members; renames, copies the invite, and leaves (F4). */
@Composable
fun GroupSettingsRoute(
    groupId: String,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onReconcile: () -> Unit,
    onEditCategories: () -> Unit = {},
    onOpenPro: (trigger: String) -> Unit = {},
    /** Set by the pass group picker: that person already chose this group, so do not ask again. */
    openPassSheet: Boolean = false,
) {
    val groups = koinInject<GroupRepository>()
    val pro = koinInject<ProRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val storageUsedBytes by remember(gid) { groups.observeStorageUsedBytes(gid) }.collectAsStateWithLifecycle(0L)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val share = koinInject<PlatformShare>()
    val inviteToken = group?.inviteToken
    val inviteLink = inviteToken?.let { "split-evenly.app/j/$it" } ?: "Generating link…"

    // The delete confirm sheet's facts. Loaded when the screen opens rather than when the sheet does,
    // so the sheet never renders half-empty; five indexed counts, re-read whenever the roster or the
    // expense list changes underneath so a stale "24 expenses" can't outlive the group it describes.
    var deleteImpact by remember(gid) { mutableStateOf(GroupDeleteImpactUi()) }
    LaunchedEffect(gid, members.size, storageUsedBytes) {
        val impact = groups.deleteImpact(gid)
        deleteImpact =
            GroupDeleteImpactUi(
                memberCount = impact.memberCount,
                expenseCount = impact.expenseCount,
                receiptCount = impact.receiptCount,
                unsettledCount = impact.unsettledCount,
                // The pass is a purchase: it deliberately survives the purge, and it is not refunded.
                // Whoever is about to delete hears that before, not after.
                proPassNote =
                    impact.proPassExpiresAt?.let {
                        "This group has Evenly Pro until ${shortDate(it)}. Deleting it does not refund that."
                    },
            )
    }

    // Drives the claim row inside the Members card: hidden when there is nothing left to answer, so it
    // never sends anyone to an empty screen.
    val unclaimed by remember(gid, userId) {
        userId?.let { groups.observeUnclaimedNames(gid, it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())

    // Evenly Pro (PRO_PASS_SPEC.md §8.3). The buyer's name is resolved from the roster the screen already
    // streams, so a pass bought by someone who has since left the group simply drops the attribution line
    // rather than showing a raw id.
    val proState by remember(gid) { pro.observe(gid.value) }.collectAsStateWithLifecycle(null)
    // `freeUsed` lives in a local cache that only `refresh` fills, and every existing caller is on the
    // expense/bill side. This screen renders the count ("3 of 5 free scans left") and is the door to the
    // pass sheet, which now states it too, so without this the label sat on "Checking free scans…"
    // forever on any group whose expense screens had not been opened. A no-op offline.
    LaunchedEffect(gid) { pro.refresh(gid.value) }
    val proUi =
        proState?.let { state ->
            val status = state.status
            val payerName = members.firstOrNull { it.userId.value == status.purchasedBy }?.displayName
            when {
                status.source == ProSource.Subscription -> {
                    ProStatusUi.ViaSubscription(
                        subscriberName = payerName,
                        isMe = status.purchasedBy == userId?.value,
                    )
                }

                status.isPro -> {
                    ProStatusUi.ViaPass(
                        expiresOn = status.expiresAt?.let { shortDate(it) } ?: "",
                        purchasedByName = payerName,
                    )
                }

                // Only say "Pro ended" to a group that actually had it. A group that never did is not in an
                // ended state, it is just a normal free group, and telling it otherwise invents a loss that
                // never happened.
                state.everHadPro -> {
                    ProStatusUi.Ended
                }

                // The row that used to be missing entirely: a free group renders the door, not nothing.
                else -> {
                    ProStatusUi.Free(
                        groupName = group?.name ?: "this group",
                        scansLeft = state.freeUsed?.let { (state.freeLimit - it).coerceAtLeast(0) },
                        freeLimit = state.freeLimit,
                    )
                }
            }
        }

    // The pass sheet is an overlay on this screen rather than a route, so buying never takes the person
    // off the settings screen they were on. The picker can ask for it directly (openPassSheet).
    var showPassSheet by remember { mutableStateOf(openPassSheet) }
    // Export used to run the round trip, take the server's 402, and settle on the label "Needs Pro" with
    // nowhere to go. Checking the (locally mirrored) state first turns that dead end into a door; the
    // server gate is unchanged and still the only thing that decides.
    var showExportPaywall by remember { mutableStateOf(false) }

    // Export (PRO_PASS_SPEC.md §3). The gate is the server's; this only reports what it said.
    val exporter = koinInject<GroupExporter>()
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    // The trailing hint before anyone taps: says Pro is needed without disabling the row, so the
    // explanation is available by tapping rather than by guessing (the guide-when-blocked rule).
    val exportHint = if (proState?.status?.isPro == false) "Pro" else null

    val rows =
        members.map { m ->
            MemberRowUi(
                userId = m.userId.value,
                name = m.displayName ?: "Someone",
                role =
                    when {
                        m.isAdmin -> "Admin"
                        m.isPlaceholder -> "No account"
                        else -> ""
                    },
                isMe = m.userId == userId,
            )
        }

    GroupSettingsScreen(
        groupName = group?.name ?: "",
        groupEmoji = group?.emoji ?: "💸",
        baseCurrency = group?.baseCurrency ?: "USD",
        members = rows,
        inviteLink = inviteLink,
        storageUsedBytes = storageUsedBytes,
        proStatus = proUi,
        exporting = exporting,
        exportNote = exportNote ?: exportHint,
        onProClick = {
            // Always the pass sheet, never the subscription paywall. The row says "Get Pro for Ski
            // Trip", and a group-named action that opens an all-groups recurring subscription is the
            // pay/renew direction being ambiguous at the exact moment of commitment. The subscription
            // is one named tap away inside the sheet, the same way the pass is from the paywall.
            showPassSheet = true
        },
        onExportCsv = {
            // Checked against the local mirror BEFORE spending the round trip. The server gate is
            // unchanged and still the only thing that decides; this just saves a person the wait before
            // a refusal we already knew was coming, and gives it somewhere to go.
            if (proState?.status?.isPro == false) {
                showExportPaywall = true
            } else {
                exporting = true
                exportNote = null
                scope.launch {
                    val name = (group?.name ?: "group").replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { "group" }
                    when (val outcome = exporter.exportCsv(gid.value)) {
                        is ExportOutcome.Success -> {
                            // Shared as a FILE, not text: a group's ledger pasted into a message body is
                            // unreadable and no spreadsheet app can open it.
                            share.shareFile(
                                fileName = "$name-evenly.csv",
                                mimeType = "text/csv",
                                content = outcome.csv,
                                subject = "${group?.name ?: "Group"} expenses",
                            )
                            exportNote = null
                        }

                        // The server disagreed with our local mirror (a pass that expired mid-session, say).
                        // No retry offered: waiting does not buy Pro. Opens the same door instead of leaving
                        // the old dead-end label.
                        ExportOutcome.NeedsPro -> {
                            showExportPaywall = true
                        }

                        ExportOutcome.Offline -> {
                            exportNote = "You're offline"
                        }

                        ExportOutcome.Unavailable -> {
                            exportNote = "Not available"
                        }

                        is ExportOutcome.Failed -> {
                            exportNote = "Didn't work, try again"
                        }
                    }
                    exporting = false
                }
            }
        },
        onBack = onBack,
        onAddMember = { name, addToPast ->
            scope.launch {
                // Adds the member as a placeholder; "all past" sweeps existing expenses (03 §8.1).
                val added = groups.addPlaceholder(gid, name, createdBy = userId)
                if (added is AppResult.Ok && addToPast) {
                    userId?.let { me -> groups.addMemberToPastExpenses(gid, added.value.userId, me) }
                }
            }
        },
        onRename = { name, emoji -> scope.launch { groups.renameGroup(gid, name, emoji) } },
        onCopyInvite = { inviteToken?.let { clipboard.setText(AnnotatedString("split-evenly.app/j/$it")) } },
        onShareInvite = { inviteToken?.let { share.shareText("Join my group on Evenly: split-evenly.app/j/$it", "Join my Evenly group") } },
        onRotateInvite = { scope.launch { groups.rotateInviteToken(gid) } },
        onRemoveMember = { row -> scope.launch { groups.removeMember(gid, UserId(row.userId)) } },
        onReconcile = onReconcile,
        unclaimedNameCount = unclaimed.size,
        onEditCategories = onEditCategories,
        onArchive = {
            userId?.let { me -> scope.launch { if (groups.setArchived(gid, me, archived = true) is AppResult.Ok) onLeft() } }
        },
        onLeave = {
            userId?.let { me -> scope.launch { if (groups.leaveGroup(gid, me) is AppResult.Ok) onLeft() } }
        },
        deleteImpact = deleteImpact,
        onDelete = {
            // Same exit as leaving: the group is gone from this device's list, so staying on its
            // settings screen would leave the user inside something that no longer exists.
            userId?.let { me -> scope.launch { if (groups.deleteGroup(gid, me) is AppResult.Ok) onLeft() } }
        },
    )

    if (showPassSheet) {
        PassSheetHost(
            groupId = gid.value,
            groupName = group?.name ?: "this group",
            trigger = ProTriggers.GROUP_SETTINGS,
            onSeeSubscription = { onOpenPro(ProTriggers.GROUP_SETTINGS) },
            onDismiss = { showPassSheet = false },
        )
    }
    if (showExportPaywall) {
        ExportNeedsProSheet(
            onSeePro = {
                showExportPaywall = false
                onOpenPro(ProTriggers.EXPORT)
            },
            onDismiss = { showExportPaywall = false },
        )
    }
}
