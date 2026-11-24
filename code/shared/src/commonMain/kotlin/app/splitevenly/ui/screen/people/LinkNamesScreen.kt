package app.splitevenly.ui.screen.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.splitevenly.domain.people.LinkCandidate
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvEmptyState
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "Link other names" for one person: names typed by hand that owe you, aren't linked to anyone, and live
 * in groups this person isn't in. Grouped under their group. Tick and save. [leftOut] names the groups
 * left out because the person is already there, so an expected name's absence is explained.
 */
@Composable
fun LinkNamesScreen(
    personName: String,
    leftOut: List<String>,
    candidates: List<LinkCandidate>,
    onBack: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim(c.page)
        EvTopBar(title = "More names for $personName", navIcon = { EvIconButton(EvIcons.Back, onClick = onBack) }, showDivider = false)
        if (candidates.isEmpty()) {
            EvEmptyState(
                title = "No other names to link",
                text = "Only names you typed in yourself, that owe you, and that are in a group $personName isn't in can be linked.",
                icon = EvIcons.Users,
            )
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leftOut.isNotEmpty()) {
                item(key = "left-out") {
                    Text(
                        "Leaving out ${leftOut.joinToString(", ")}. $personName is already in ${if (leftOut.size == 1) "that group" else "those"}.",
                        style = t.caption.copy(color = c.ink2),
                        modifier = Modifier.fillMaxWidth().background(c.blueTint, RoundedCornerShape(12.dp)).padding(12.dp),
                    )
                }
            }
            candidates.groupBy { it.groupId }.forEach { (gid, inGroup) ->
                val head = inGroup.first()
                item(key = "g-$gid") {
                    Text(
                        "${head.groupEmoji}  ${head.groupName}".uppercase(),
                        style = t.sectionLabel.copy(color = c.ink3),
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
                items(inGroup, key = { "c-" + it.userId }) { cand ->
                    val on = cand.userId in picked
                    EvCard(padded = true, onClick = { picked = if (on) picked - cand.userId else picked + cand.userId }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EvAvatar(cand.name, size = AvatarSize.Md)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cand.name, style = t.rowTitle)
                                Text("Owes you ${totalsText(cand.owedByCurrency)}", style = t.caption.copy(color = c.ink3))
                            }
                            Box(
                                Modifier.size(26.dp).background(if (on) c.blue else c.blueTint, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                EvIcon(if (on) EvIcons.Check else EvIcons.Plus, size = 16.dp, tint = if (on) c.onAccent else c.blueText)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            if (tried && picked.isEmpty()) {
                Text("Tap a name to pick it first.", style = t.caption.copy(color = c.danger), modifier = Modifier.padding(bottom = 8.dp))
            }
            EvButton(
                variant = ButtonVariant.PrimarySolid,
                onClick = {
                    tried = true
                    if (picked.isNotEmpty()) onSave(picked.toList())
                },
            ) {
                Text(
                    when (picked.size) {
                        0 -> "Link to $personName"
                        1 -> "Link 1 name to $personName"
                        else -> "Link ${picked.size} names to $personName"
                    },
                )
            }
        }
    }
}
