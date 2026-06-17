package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.ui.screen.settings.GroupSettingsScreen
import da.chelimo.sharecost.ui.screen.settings.MemberRowUi
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Group settings, wired: streams the group + members; renames, copies the invite, and leaves (F4). */
@Composable
fun GroupSettingsRoute(groupId: String, onBack: () -> Unit, onLeft: () -> Unit, onReconcile: () -> Unit) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val inviteToken = group?.inviteToken
    val inviteLink = inviteToken?.let { "sharecost.app/j/$it" } ?: "Generating link…"

    val rows = members.map { m ->
        MemberRowUi(
            userId = m.userId.value,
            name = m.displayName ?: "Someone",
            role = when {
                m.isAdmin -> "Admin"
                m.isPlaceholder -> "Placeholder"
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
        onBack = onBack,
        onAddMember = { name, addToPast ->
            scope.launch {
                // Adds the member as a placeholder; "all past" sweeps existing expenses (03 §8.1).
                val added = groups.addPlaceholder(gid, name)
                if (added is AppResult.Ok && addToPast) {
                    userId?.let { me -> groups.addMemberToPastExpenses(gid, added.value.userId, me) }
                }
            }
        },
        onRename = { name -> scope.launch { groups.renameGroup(gid, name) } },
        onCopyInvite = { inviteToken?.let { clipboard.setText(AnnotatedString("sharecost.app/j/$it")) } },
        onRotateInvite = { scope.launch { groups.rotateInviteToken(gid) } },
        onRemoveMember = { row -> scope.launch { groups.removeMember(gid, UserId(row.userId)) } },
        onReconcile = onReconcile,
        onArchive = {
            userId?.let { me -> scope.launch { if (groups.setArchived(gid, me, archived = true) is AppResult.Ok) onLeft() } }
        },
        onLeave = {
            userId?.let { me -> scope.launch { if (groups.leaveGroup(gid, me) is AppResult.Ok) onLeft() } }
        },
    )
}
