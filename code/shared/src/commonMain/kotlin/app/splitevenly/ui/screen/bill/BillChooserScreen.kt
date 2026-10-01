package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvProgress
import app.splitevenly.ui.components.EvSkeleton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme

/** The saved bill the chooser is about. [returning] = reopened from home or details, not just saved. */
data class BillChooserState(
    val title: String,
    val currency: String,
    val totalSubunits: Long,
    val itemCount: Int,
    val peopleCount: Int,
    val resolvedCount: Int,
    val returning: Boolean,
)

/**
 * "Finish splitting" — how the people on a saved bill will pick its items (frames 4 and 10). Every
 * saved-bill entry lands here: just saved, the unresolved row on group home, and "Pick items" on
 * details. Neither choice erases or restarts claims, which is why a returning visitor is told their
 * earlier choices are still there. The phone option says others can pick later, so the creator does not
 * read it as "fill in the whole bill now". [state] null paints the shaped loading state. DI-free.
 */
@Composable
fun BillChooserScreen(
    state: BillChooserState?,
    onBack: () -> Unit = {},
    onAssignOnPhone: () -> Unit = {},
    onShowQr: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(
            title = "Finish splitting",
            subtitle = if (state?.returning == false) "Expense saved" else "Saved bill",
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
            showDivider = false,
        )
        if (state == null) {
            ChooserSkeleton()
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.returning) SavedNote(state.title)
            Hero(state.returning)
            SummaryCard(state)
            ModeCard(
                icon = EvIcons.User,
                title = if (state.returning) "Pick on this phone" else "Assign on this phone",
                body =
                    if (state.returning) {
                        "Pick yours, or choose for anyone. Others can pick later."
                    } else {
                        "Assign yours now, or choose for anyone. Others in this group can pick their items later."
                    },
                action = if (state.returning) "Continue on this phone" else "Assign on this phone",
                primary = true,
                onClick = onAssignOnPhone,
            )
            ModeCard(
                icon = EvIcons.Qr,
                title = if (state.returning) "Share this bill" else "Everyone picks their own",
                body =
                    if (state.returning) {
                        "Friends can choose what they had in their browser."
                    } else {
                        "Show this bill's QR code. Friends can choose in a browser, with no app needed."
                    },
                action = "Show the QR code",
                primary = false,
                onClick = onShowQr,
            )
        }
    }
}

@Composable
private fun SavedNote(title: String) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.settledTint)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.settled), contentAlignment = Alignment.Center) {
            EvIcon(EvIcons.Check, size = 13.dp, tint = c.onAccent)
        }
        Column {
            Text("${title.ifBlank { "Your bill" }} is saved", color = c.settled, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("People can pick their items now or later.", color = c.settled, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun Hero(returning: Boolean) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            EvIcon(if (returning) EvIcons.Reload else EvIcons.Split, size = 26.dp, tint = c.blueText)
        }
        Text(
            if (returning) "Continue picking items" else "How should everyone pick their items?",
            color = c.ink,
            fontSize = 22.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
        )
        if (returning) {
            Text("Your earlier choices are still here.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SummaryCard(state: BillChooserState) {
    val c = EvenlyTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val counts = "${plural(state.itemCount, "item")} · ${plural(state.peopleCount, "person", "people")}"
                Text(
                    if (state.returning) counts else "$counts · ${state.resolvedCount} assigned",
                    color = c.ink3,
                    fontSize = 12.sp,
                )
            }
            Text(
                moneySubunits(state.totalSubunits, state.currency),
                color = c.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = EvenlyTheme.monoFamily,
            )
        }
        if (state.returning) {
            AssignProgressHeader(state.resolvedCount, state.itemCount)
        }
    }
}

/** "3 of 5 assigned · 2 left" with its bar. Shared by the chooser, the accordion and the review. */
@Composable
internal fun AssignProgressHeader(
    resolved: Int,
    total: Int,
    noun: String = "assigned",
) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$resolved of $total $noun",
                color = c.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            val left = total - resolved
            if (left > 0) Text("$left left", color = c.ink3, fontSize = 12.sp)
        }
        EvProgress(if (total == 0) 0f else resolved.toFloat() / total)
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (primary) c.blueTint else c.page)
            .border(if (primary) 1.5.dp else 1.dp, if (primary) c.blue else c.border, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.page), contentAlignment = Alignment.Center) {
                EvIcon(icon, size = 18.dp, tint = c.blueText)
            }
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp)
        EvButton(action, onClick, variant = if (primary) ButtonVariant.PrimarySolid else ButtonVariant.Tonal)
    }
}

@Composable
private fun ChooserSkeleton() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        EvSkeleton(Modifier.fillMaxWidth(), height = 56.dp, radius = 12.dp)
        EvSkeleton(Modifier.fillMaxWidth(), height = 120.dp, radius = 14.dp)
        EvSkeleton(Modifier.fillMaxWidth(), height = 120.dp, radius = 14.dp)
    }
}

internal fun plural(
    n: Int,
    one: String,
    many: String = one + "s",
): String = "$n ${if (n == 1) one else many}"

@Preview
@Composable
private fun BillChooserPreview() {
    EvenlyTheme {
        BillChooserScreen(BillChooserState("Friday dinner", "USD", 6000, 3, 2, 0, returning = false))
    }
}
