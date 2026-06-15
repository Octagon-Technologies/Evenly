package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.ui.screen.settings.GroupSettingsScreen
import da.chelimo.sharecost.ui.screen.settings.MemberRowUi
import org.koin.compose.koinInject

/** Group settings, wired: streams the group + its members (read-only roster) into the screen. */
@Composable
fun GroupSettingsRoute(groupId: String, onBack: () -> Unit) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()

    val rows = members.map { m ->
        MemberRowUi(
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
        onBack = onBack,
    )
}
