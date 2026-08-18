package app.splitevenly.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The Profile "Evenly Pro" row (`PRO_PASS_SPEC.md` §8.1, mock frame 8).
 *
 * [subtitle] is resolved by the caller and is either the **price**, taken from the store's own localized
 * string, or the live subscription's renewal line. Naming the price on the row is deliberate: a Pro entry
 * that makes you tap to find out what it costs reads as a trap. When the price cannot be read the row
 * says what Pro *does* instead of inventing a number.
 */
data class ProEntryUi(val subtitle: String, val isSubscribed: Boolean)

@Composable
fun ProEntryRow(entry: ProEntryUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(c.blueTint)
            .border(1.dp, c.blue, shape)
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.blue),
            contentAlignment = Alignment.Center,
        ) { EvIcon(EvIcons.Star, size = 17.dp, tint = c.onAccent) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Evenly Pro", color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(entry.subtitle, color = c.ink2, fontSize = 12.5.sp)
        }
        if (entry.isSubscribed) {
            Text("PRO", color = c.blueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        } else {
            EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
        }
    }
}
