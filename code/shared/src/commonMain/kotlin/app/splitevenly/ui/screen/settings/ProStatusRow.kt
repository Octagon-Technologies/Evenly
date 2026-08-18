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
 * What the group's Pro row says, resolved by the caller so this stays DI-free and previewable.
 *
 * There is deliberately **no null state**: a free group used to render nothing here, which meant a group
 * that had never bought a pass saw no Pro surface at all and had no door to one. That was a bug in its
 * own right, separate from the missing paywall (`PRO_PASS_SPEC.md` §8.1).
 */
sealed interface ProStatusUi {

    /** Pro through a pass bought for this group. Names the buyer and the date it runs to. */
    data class ViaPass(val expiresOn: String, val purchasedByName: String?) : ProStatusUi

    /**
     * Pro because an active member subscribes. **No date is shown**, unlike [ViaPass]: their renewal
     * date is their business, and putting it on everyone else's screen invites the group to plan around
     * a charge they have no control over.
     */
    data class ViaSubscription(val subscriberName: String?, val isMe: Boolean) : ProStatusUi

    /** Never been Pro. The row is the door, and the subtitle is the reason to care, not a slogan. */
    data class Free(val groupName: String, val scansLeft: Int?, val freeLimit: Int) : ProStatusUi

    /** Was Pro, isn't now. Leads with the fact that nothing was taken away. */
    data object Ended : ProStatusUi
}

/**
 * The group's Evenly Pro row (`PRO_PASS_SPEC.md` §8.5).
 *
 * **Naming the buyer is the feature, not decoration.** It turns a charge into a visible favour to the
 * group instead of an invisible tax, and answers "who paid for this?" before anyone has to ask. When the
 * name cannot be resolved the line degrades rather than saying "someone", which would read as the app
 * having lost track of a payment.
 *
 * The expired state leads with the fact that nothing was taken away. In a money app the gap between
 * "your pass ended" and "your data is gone" is the whole difference between a lapsed purchase and a
 * support ticket, so it gets said rather than assumed.
 */
@Composable
fun ProStatusRow(
    status: ProStatusUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val isPro = status is ProStatusUi.ViaPass || status is ProStatusUi.ViaSubscription
    val title = when (status) {
        is ProStatusUi.ViaPass -> "Pro until ${status.expiresOn}"
        is ProStatusUi.ViaSubscription -> "Pro, unlimited scans"
        is ProStatusUi.Free -> "Get Pro for ${status.groupName}"
        ProStatusUi.Ended -> "Pro ended"
    }
    val sub = when (status) {
        is ProStatusUi.ViaPass ->
            status.purchasedByName?.let { "$it got this for the group" } ?: "Unlimited receipt scans for everyone here"
        is ProStatusUi.ViaSubscription -> when {
            status.isMe -> "Your Evenly Pro subscription covers this group"
            status.subscriberName != null -> "${status.subscriberName} subscribes to Evenly Pro"
            else -> "Someone here subscribes to Evenly Pro"
        }
        // The reason to care, said as a fact about this group rather than as a pitch. A group with scans
        // left is told that instead, because "no free scans left" would be a lie to it.
        is ProStatusUi.Free -> when {
            // The count is not known yet (never fetched for this group). Says so, rather than a benefit
            // line that reads as if the group already has it.
            status.scansLeft == null -> "Checking free scans…"
            // Never "No free scans left" on its own. Standing alone under a buy prompt it reads as the
            // group having broken, which is exactly the false loss the "Pro ended" line was written to
            // avoid. Typing a bill in stays free forever and that is the counterweight.
            status.scansLeft <= 0 -> "No free scans left. You can still type bills in."
            else -> "${status.scansLeft} of ${status.freeLimit} free scans left"
        }
        ProStatusUi.Ended -> "Your bills and receipts are all still here."
    }
    Row(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(if (isPro) c.page else c.surface)
            .then(if (isPro) Modifier.border(1.dp, c.blue, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(if (isPro) c.blueTint else c.page),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(EvIcons.Star, size = 17.dp, tint = if (isPro) c.blueText else c.ink3)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = c.ink2, fontSize = 12.5.sp)
        }
        if (isPro) {
            Text("PRO", color = c.blueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        } else {
            EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
        }
    }
}
