package app.splitevenly.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** 19 · Archived groups (design/src/screens-home.jsx). */
@Composable
fun ArchivedScreen(
    onBack: () -> Unit = {},
    groups: List<GroupCardUi> = ArchivedSamples.groups,
    onUnarchive: (String) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar("Archived", subtitle = "${groups.size} groups", navIcon = { EvIconButton(EvIcons.Back, onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EvCard {
                groups.forEachIndexed { i, g ->
                    Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                        GroupRow(g, archived = true, onClick = { onUnarchive(g.id) })
                    }
                }
            }
            Text(
                "Archived groups stay read-only until you unarchive them.",
                Modifier.fillMaxWidth(),
                color = c.ink2,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal object ArchivedSamples {
    val groups = listOf(
        GroupCardUi("5", "⛷️", "Tahoe 2025", 5, GroupBalanceStatus.Settled, null, "Settled · Mar 2025"),
        GroupCardUi("6", "🍝", "Supper Club", 8, GroupBalanceStatus.Settled, null, "Settled · Jan 2025"),
        GroupCardUi("7", "🏕️", "Big Sur camping", 4, GroupBalanceStatus.Settled, null, "Settled · Aug 2024"),
    )
}

@Preview
@Composable
private fun ArchivedPreview() {
    EvenlyTheme { ArchivedScreen() }
}
