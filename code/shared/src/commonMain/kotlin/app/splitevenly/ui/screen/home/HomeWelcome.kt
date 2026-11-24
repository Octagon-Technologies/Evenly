package app.splitevenly.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * First-run welcome — no template chips, no numbered primer. One hero, one headline, one job: get
 * the first bill split, with "Start a trip group" and "Join a friend's group" under it. The quick split leads
 * because it needs no decision about who counts as a group (docs/features/one-time-splits.md).
 */
@Composable
internal fun HomeWelcome(
    onSplitBill: () -> Unit,
    onNewGroup: () -> Unit,
    onJoin: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(c.blue, c.bluePressed))),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(EvIcons.Users, size = 44.dp, tint = c.onAccent)
        }
        Spacer(Modifier.height(24.dp))
        Text("Split your first bill", style = t.screenTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Split costs and settle up with friends, no spreadsheets.",
            style = t.description,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 280.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Two equally valid starts, so the second keeps an outlined shape (Secondary) rather than
            // going plain; the plain link below is the third, lighter level.
            EvButton(
                "Split a one-time bill",
                onSplitBill,
                leadingIcon = EvIcons.Receipt,
            )
            EvButton(
                "Start a trip group",
                onNewGroup,
                variant = ButtonVariant.Secondary,
                leadingIcon = EvIcons.Users,
            )
            EvButton(
                "Join a friend's group",
                onJoin,
                variant = ButtonVariant.Text,
                leadingIcon = EvIcons.Link,
            )
        }
    }
}

/** The tinted rounded panel that holds one or more [ActionRow]s in the home Create sheet. */
@Composable
internal fun ActionPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(EvenlyTheme.colors.surface)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        content = content,
    )
}

/** A tappable "icon tile + title + subtitle" row used in the home Create sheet. */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            EvIcon(icon, size = 22.dp, tint = c.blueText)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = t.itemTitle)
            Text(sub, style = t.description)
        }
        EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3)
    }
}

/** One undoable Home action: the toast's text and what Undo does. */
internal class HomeUndo(
    val text: String,
    val undo: () -> Unit,
)

internal const val UNDO_WINDOW_MS = 5_000L

/** An empty tab is never a dead end: it says what goes there and offers the one way to add it. */
@Composable
internal fun HomeTabEmpty(
    filter: HomeFilter,
    onSplitBill: () -> Unit,
    onNewGroup: () -> Unit,
) {
    val t = EvenlyTheme.text
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (filter) {
                HomeFilter.OneTime -> "No one-time bills right now"
                HomeFilter.Groups -> "No groups right now"
                HomeFilter.All -> "Nothing active right now"
            },
            style = t.description,
            textAlign = TextAlign.Center,
        )
        if (filter == HomeFilter.OneTime) {
            EvButton("Split a one-time bill", onSplitBill, variant = ButtonVariant.Secondary, leadingIcon = EvIcons.Receipt)
        } else {
            EvButton("Start a trip group", onNewGroup, variant = ButtonVariant.Secondary, leadingIcon = EvIcons.Users)
        }
    }
}
