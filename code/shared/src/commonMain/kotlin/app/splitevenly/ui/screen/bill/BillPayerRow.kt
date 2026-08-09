package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.EvSelectField
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.screen.expense.AddParticipantUi
import app.splitevenly.ui.screen.expense.PayerSheet
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "Paid by" for the bill editor, reusing the add-expense editor's `PayerSheet` so both editors offer the
 * same choice (a member, a brand-new placeholder, or someone outside the group). Changing the payer saves
 * with everything else and is never confirmed: it corrects a fact about the bill, and settlement
 * allocations stay attached to the shares they were applied to.
 *
 * [payerUserId] is blank when an outside payer holds the bill, which is what [outsidePayerName] carries.
 */
@Composable
internal fun BillPaidByRow(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    payerUserId: String,
    outsidePayerName: String?,
    onOpenSheet: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val isOutside = !outsidePayerName.isNullOrBlank()
    val payer = participants.firstOrNull { it.userId == payerUserId }
    val display = when {
        isOutside -> outsidePayerName
        payer == null -> "Choose"
        payer.isMe -> "You"
        else -> payer.name
    }

    EvField("Paid by") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EvSelectField(
                display,
                onOpenSheet,
                leading = {
                    if (isOutside || payer == null) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                            EvIcon(EvIcons.User, size = 15.dp, tint = c.blueText)
                        }
                    } else {
                        EvAvatar(display, me = payer.isMe, size = AvatarSize.Sm)
                    }
                },
            )
            // Paying for a table you didn't eat at is legitimate, and it is also what an accidental
            // removal looks like. Saying it beats leaving the reader to spot the mismatch themselves.
            if (payer != null && payer.userId !in selected) {
                Text(
                    if (payer.isMe) "You paid but aren't splitting this bill."
                    else "${payer.name} paid but isn't splitting this bill.",
                    color = c.ink3, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

/** The picker itself. Rendered by the screen, outside its scrolling column. */
@Composable
internal fun BillPayerSheet(
    participants: List<ParticipantChipUi>,
    payerUserId: String,
    outsidePayerName: String?,
    onPickMember: (String) -> Unit,
    onPickOutside: (String) -> Unit,
    onAddSomeoneNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    PayerSheet(
        participants = participants.map { AddParticipantUi(it.userId, if (it.isMe) "You" else it.name, it.isMe) },
        effectivePayerId = payerUserId,
        isOutsidePayer = !outsidePayerName.isNullOrBlank(),
        outsidePayerName = outsidePayerName,
        onPickMember = onPickMember,
        onPickOutside = onPickOutside,
        onAddSomeoneNew = onAddSomeoneNew,
        onDismiss = onDismiss,
    )
}
