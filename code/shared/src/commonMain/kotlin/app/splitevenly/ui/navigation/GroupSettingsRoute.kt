package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.core.time.shortDate
import app.splitevenly.domain.export.ExportOutcome
import app.splitevenly.domain.export.GroupExporter
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProRepository
import app.splitevenly.ui.screen.settings.ProStatusUi
import app.splitevenly.platform.PlatformShare
import app.splitevenly.ui.screen.settings.GroupSettingsScreen
import app.splitevenly.ui.screen.settings.MemberRowUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.koinInject

/** Group settings, wired: streams the group + members; renames, copies the invite, and leaves (F4). */
@Composable
fun GroupSettingsRoute(
    groupId: String,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onReconcile: () -> Unit,
    onEditCategories: () -> Unit = {},
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

    // Drives the claim row inside the Members card: hidden when there is nothing left to answer, so it
    // never sends anyone to an empty screen.
    val unclaimed by remember(gid, userId) {
        userId?.let { groups.observeUnclaimedNames(gid, it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())

    // Evenly Pro (PRO_PASS_SPEC.md §8.3). The buyer's name is resolved from the roster the screen already
    // streams, so a pass bought by someone who has since left the group simply drops the attribution line
    // rather than showing a raw id.
    val proState by remember(gid) { pro.observe(gid.value) }.collectAsStateWithLifecycle(null)
    val proUi = proState?.let { state ->
        when {
            state.status.isPro -> ProStatusUi(
                isPro = true,
                expiresOn = state.status.expiresAt?.let { shortDate(it) },
                purchasedByName = members.firstOrNull { it.userId.value == state.status.purchasedBy }?.displayName,
            )
            // Only say "Pro ended" to a group that actually had one. A group that never bought a pass is
            // not in an ended state, it is just a normal free group, and telling it otherwise invents a
            // loss that never happened.
            state.everHadPro -> ProStatusUi(isPro = false)
            else -> null
        }
    }

    // Export (PRO_PASS_SPEC.md §3). The gate is the server's; this only reports what it said.
    val exporter = koinInject<GroupExporter>()
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    // The trailing hint before anyone taps: says Pro is needed without disabling the row, so the
    // explanation is available by tapping rather than by guessing (the guide-when-blocked rule).
    val exportHint = if (proState?.status?.isPro == false) "Pro" else null

    val rows = members.map { m ->
        MemberRowUi(
            userId = m.userId.value,
            name = m.displayName ?: "Someone",
            role = when {
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
        onExportCsv = {
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
                    // No retry offered: waiting does not buy a pass. Step 5 turns this line into the
                    // paywall; until then it says the true thing instead of naming a door that is not built.
                    ExportOutcome.NeedsPro -> exportNote = "Needs Pro"
                    ExportOutcome.Offline -> exportNote = "You're offline"
                    ExportOutcome.Unavailable -> exportNote = "Not available"
                    is ExportOutcome.Failed -> exportNote = "Didn't work, try again"
                }
                exporting = false
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
        onRename = { name -> scope.launch { groups.renameGroup(gid, name) } },
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
    )
}
