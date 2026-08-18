package app.splitevenly.ui.screen.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * What the Export row opens on a free group (`PRO_PASS_SPEC.md` §8.1, mock frame 12).
 *
 * It replaces a real dead end: the row used to run the export anyway, take the server's 402, and settle
 * on the label "Needs Pro" with nowhere to go. The fix is to **check before spending the round trip**
 * and lead with what export is *for* rather than with the refusal, which is also why the headline is not
 * "you cannot do this".
 *
 * The server gate is unchanged and is still the only thing that decides. This only saves a person the
 * wait before a refusal it already knew was coming.
 */
@Composable
fun ExportNeedsProSheet(
    onSeePro: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    EvSheetScaffold(onDismiss = onDismiss) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(52.dp).clip(RoundedCornerShape(99.dp))
                .background(c.ink2.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { EvIcon(EvIcons.Download, size = 24.dp, tint = c.ink2) }
        Text(
            "Export needs Pro",
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Text(
            "Get every expense, who paid and who owes, as a spreadsheet you can open anywhere.",
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            color = c.ink2, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        EvButton(text = "See Pro", onClick = onSeePro)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            EvButton(text = "Not now", onClick = onDismiss, variant = ButtonVariant.Text)
        }
    }
}
