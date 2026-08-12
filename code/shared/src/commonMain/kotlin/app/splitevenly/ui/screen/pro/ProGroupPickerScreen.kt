package app.splitevenly.ui.screen.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** One row of the pass group picker, with its Pro state already resolved to a sentence. */
data class PickableGroupUi(
    val groupId: String,
    val emoji: String,
    val name: String,
    /** "No free scans left" | "2 of 5 free scans left" | "Pro until 16 Aug". */
    val state: String,
    val isPro: Boolean,
)

/**
 * "Which group is this pass for?" (`PRO_PASS_SPEC.md` §8.4).
 *
 * Reached **only** from the Profile entry point, and **only** for a pass: a subscription needs no group,
 * which is the thing that makes it simpler to explain than the pass.
 *
 * A group that is already Pro is **dimmed, not hidden**. Hiding it would read as the group having gone
 * missing; dimming it says "this one is already covered", which is the fact that stops someone buying a
 * second pass for it. It stays tappable, because the sheet behind it has a real thing to say (already
 * covered, or extend from the current expiry) and a dead row would be the silent dead end.
 */
@Composable
fun ProGroupPickerScreen(
    groups: List<PickableGroupUi>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "Pick a group", navIcon = { EvIconButton(EvIcons.Back, onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "A pass covers one group, and everyone in it.",
                Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                color = c.ink2, fontSize = 13.5.sp,
            )
            if (groups.isEmpty()) {
                Text(
                    "You're not in any groups yet. Make one first, then a pass has something to cover.",
                    Modifier.padding(horizontal = 2.dp),
                    color = c.ink2, fontSize = 13.5.sp,
                )
            }
            groups.forEach { g ->
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(c.page)
                        .border(1.dp, c.border, shape)
                        .clickable { onPick(g.groupId) }
                        .padding(13.dp)
                        .alpha(if (g.isPro) 0.55f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface).padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(g.emoji, fontSize = 16.sp) }
                    Column(Modifier.weight(1f)) {
                        Text(g.name, color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(g.state, color = c.ink2, fontSize = 12.5.sp)
                    }
                    if (g.isPro) {
                        Text("PRO", color = c.blueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    } else {
                        EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
                    }
                }
            }
        }
    }
}
