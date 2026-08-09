package app.splitevenly.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** What the Pro row says, resolved by the caller so this stays DI-free and previewable. */
data class ProStatusUi(
    val isPro: Boolean,
    /** Already formatted ("Aug 16"). Null while Pro state is still loading, or when never Pro. */
    val expiresOn: String? = null,
    /** Display name of whoever paid. Null if they have since left the group or are not resolvable. */
    val purchasedByName: String? = null,
)

/**
 * "Pro until Aug 16 / Andrew got this for the group" (`PRO_PASS_SPEC.md` §8.3).
 *
 * **Naming the buyer is the feature, not decoration.** It turns a $0.99 charge into a visible favour to
 * the group instead of an invisible tax, and answers "who paid for this?" before anyone has to ask. When
 * the name cannot be resolved the line is dropped rather than replaced with "someone", which would read
 * as the app having lost track of a payment.
 *
 * The expired state leads with the fact that nothing was taken away. In a money app the gap between
 * "your pass ended" and "your data is gone" is the whole difference between a lapsed purchase and a
 * support ticket, so it gets said rather than assumed.
 */
@Composable
fun ProStatusRow(
    status: ProStatusUi,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(if (status.isPro) c.page else c.surface)
                .then(if (status.isPro) Modifier.border(1.dp, c.border, shape) else Modifier)
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (status.isPro) c.blueTint else c.page),
                contentAlignment = Alignment.Center,
            ) {
                EvIcon(EvIcons.Star, size = 17.dp, tint = if (status.isPro) c.blueText else c.ink3)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (status.isPro && status.expiresOn != null) "Pro until ${status.expiresOn}" else "Pro ended",
                    color = c.ink,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val sub = when {
                    status.isPro && status.purchasedByName != null -> "${status.purchasedByName} got this for the group"
                    status.isPro -> "Unlimited receipt scans for everyone here"
                    else -> "Your bills and receipts are all still here."
                }
                Text(sub, color = c.ink2, fontSize = 12.5.sp)
            }
        }
    }
}
