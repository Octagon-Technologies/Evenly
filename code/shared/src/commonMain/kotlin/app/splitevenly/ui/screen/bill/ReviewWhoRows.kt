package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvAvatarStack
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "Who's splitting this?" and "Paid by" on the receipt review. A scan jumps straight here, often before
 * anyone picked people on the editor, so both answers are shown and editable in place instead of only
 * being reachable by going back.
 */
@Composable
internal fun ReviewWhoRows(
    people: List<PickerPersonUi>,
    payerName: String,
    payerIsMe: Boolean,
    showErrors: Boolean,
    onEditPeople: () -> Unit,
    onChangePayer: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val missing = people.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Label("Who's splitting this?")
        WhoRow(error = showErrors && missing, action = "Edit", onClick = onEditPeople) {
            if (!missing) {
                // Real names, so "You" shows your initials like everywhere else.
                val shown = people.take(4)
                EvAvatarStack(names = shown.map { it.name }, size = AvatarSize.Sm, me = { n -> shown.any { it.isMe && it.name == n } })
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (missing) "No one picked yet" else if (people.size == 1) "1 person" else "${people.size} people",
                    color = if (missing) c.ink2 else c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!missing) {
                    Text(
                        people.joinToString { if (it.isMe) "You" else it.name },
                        color = c.ink2,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showErrors && missing) Text("Pick who is splitting this", color = c.danger, fontSize = 12.sp)
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Label("Paid by")
        WhoRow(error = false, action = "Change", onClick = onChangePayer) {
            EvAvatar(payerName, me = payerIsMe, size = AvatarSize.Sm)
            Text(payerName, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = EvenlyTheme.colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WhoRow(
    error: Boolean,
    action: String,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(if (error) 1.5.dp else 1.dp, if (error) c.danger else c.borderStrong, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
        Text(action, color = c.blueText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
