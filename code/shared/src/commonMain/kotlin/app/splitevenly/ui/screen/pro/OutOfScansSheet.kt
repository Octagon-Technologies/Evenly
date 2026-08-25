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
 * What a scan tap opens once the group's free scans are spent (`PRO_PASS_SPEC.md` §8.1).
 *
 * **It fires before the file picker, not after the upload.** The refusal used to arrive as the server's
 * 402 (`ScanErrorKind.OutOfScans`), which meant framing a receipt in a restaurant, waiting through an
 * upload and only then being told no. `scansExhausted` reads the cached count and opens this instead;
 * the server gate is unchanged and still the only thing that decides, including for the race this cannot
 * see (another member spending the last scan while yours is in flight).
 *
 * Both products are named because both genuinely fit: a pass covers this group for one trip, a
 * subscription covers every group the buyer is in. [onSeeSubscription] is null when RevenueCat is
 * unconfigured, and the sheet then offers only the doors that open.
 *
 * Manual entry is named in the same breath, deliberately: the refusal has to read as "want the fast
 * way?" and never as "you cannot use the app". Nothing has been picked or staged at this point, so
 * unlike the 402 sheet there is no photo to reassure anyone about, and the reassurance that *is* needed
 * is that the half-typed expense behind the sheet survives a trip to the paywall.
 */
@Composable
fun OutOfScansSheet(
    groupName: String?,
    onGetPass: () -> Unit,
    onSeeSubscription: (() -> Unit)?,
    onManual: () -> Unit,
) {
    val c = EvenlyTheme.colors
    EvSheetScaffold(onDismiss = onManual) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(52.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(c.credit.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { EvIcon(EvIcons.Receipt, size = 24.dp, tint = c.credit) }
        Text(
            groupName?.let { "$it is out of free scans" } ?: "Out of free scans",
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            color = c.ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            "A pass covers everyone in this group. A subscription covers every group you're in.",
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            color = c.ink2,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        // The pass leads because this door names a group, and a group-named door has to open the
        // group-scoped product (ui/AGENTS.md). The subscription sits under it as the honest alternative
        // for someone who splits in more than one place.
        EvButton(text = groupName?.let { "Get a pass for $it" } ?: "Get a group pass", onClick = onGetPass)
        if (onSeeSubscription != null) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                EvButton(text = "See subscription", onClick = onSeeSubscription, variant = ButtonVariant.Secondary)
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            EvButton(text = "Type the bill in instead", onClick = onManual, variant = ButtonVariant.Text)
        }
        Text(
            "Your expense stays exactly as you left it.",
            Modifier.fillMaxWidth().padding(top = 2.dp),
            color = c.ink3,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}
