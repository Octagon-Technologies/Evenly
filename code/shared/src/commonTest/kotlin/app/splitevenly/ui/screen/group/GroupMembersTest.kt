package app.splitevenly.ui.screen.group

import app.splitevenly.core.id.UserId
import app.splitevenly.domain.group.Member
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupMembersTest {
    private fun member(
        id: String,
        name: String?,
        admin: Boolean = false,
        placeholder: Boolean = false,
    ) = Member(UserId(id), name, isPlaceholder = placeholder, isAdmin = admin, joinedAt = 0)

    @Test
    fun soloGroupReadsJustYou() {
        assertEquals("Just you", membersStripLabel(1))
        assertEquals("6 members", membersStripLabel(6))
    }

    @Test
    fun rowsCarryRoleAndViewer() {
        val rows =
            listOf(member("a", "Andrew", admin = true), member("d", "Diego", placeholder = true), member("m", null))
                .toMemberRows(UserId("a"))
        assertEquals(listOf("Admin", "No account", ""), rows.map { it.role })
        assertEquals(listOf(true, false, false), rows.map { it.isMe })
        assertEquals("Someone", rows[2].name)
    }
}
