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
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.export.ExportOutcome
import app.splitevenly.domain.export.GroupExporter
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.PlatformShare
import app.splitevenly.ui.screen.settings.GroupDeleteImpactUi
import app.splitevenly.ui.screen.settings.GroupSettingsScreen
import app.splitevenly.ui.screen.settings.MemberRowUi
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
) {
    val groups = koinInject<GroupRepository>()
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
            )
    }

    // Drives the claim row inside the Members card: hidden when there is nothing left to answer, so it
    // never sends anyone to an empty screen.
    val unclaimed by remember(gid, userId) {
        userId?.let { groups.observeUnclaimedNames(gid, it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())

    // Export is free for every group; the server still enforces membership authorization.
    val exporter = koinInject<GroupExporter>()
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }

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
        exporting = exporting,
        exportNote = exportNote,
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
}
