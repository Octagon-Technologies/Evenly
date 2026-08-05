package app.splitevenly.ui.screen.expense

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** Which way the creator is splitting: divide one total, or itemize (claim by item). */
enum class SplitApproach { Divide, ByItem }

/**
 * The up-front split-type question, shown before the editor: "Divide the total" vs "By what each had".
 * Choosing one opens a focused editor for that mode — no in-editor toggle to flip by accident. Itemize
 * sits a level *above* Even/Shares/%/Exact on purpose: those divide a known total; this derives the total
 * from items, so it isn't a peer of them.
 */
@Composable
internal fun SplitApproachChooser(onChoose: (SplitApproach) -> Unit) {
    val c = EvenlyTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("How are you splitting this?", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        }
        ApproachChoiceCard(
            icon = EvIcons.Wallet,
            title = "Split one amount",
            subtitle = "One total that you can split evenly, by percentages, or by typing everyone's exact share.",
            onClick = { onChoose(SplitApproach.Divide) },
        )
        ApproachChoiceCard(
            icon = EvIcons.Food,
            title = "Splitting a restaurant bill",
            subtitle = "Scan the receipt or list items, everyone pays for what they had.",
            onClick = { onChoose(SplitApproach.ByItem) },
        )
    }
}

@Composable
private fun ApproachChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.page).border(1.dp, c.borderStrong, shape)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            EvIcon(icon, size = 24.dp, tint = c.blueText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = c.ink2, fontSize = 13.sp, lineHeight = 17.sp)
        }
        EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3)
    }
}
