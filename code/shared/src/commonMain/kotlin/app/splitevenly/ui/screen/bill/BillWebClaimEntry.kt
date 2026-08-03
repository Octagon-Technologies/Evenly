package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The payer's way in to the three web-claim screens, from the assign screen (WEB_CLAIM_SPEC.md §3.9).
 *
 * Extracted rather than left inline: `BillClaimScreen` was at 573 lines and this surface would have
 * pushed it past the 600-line ceiling in `ui/AGENTS.md`. It is also a genuinely separate concern, since
 * none of it is about assigning items.
 */

/**
 * Unreviewed guest edits, above everything else on the assign screen. Until they are decided, the
 * amounts the payer is looking at are not the amounts the guests are looking at, so this is not a
 * dismissible notice: it clears when the cards are decided and not before.
 */
@Composable
internal fun PendingEditsBanner(pendingEditCount: Int, onReviewEdits: () -> Unit) {
    if (pendingEditCount <= 0) return
    Row(Modifier.fillMaxWidth().clickable(onClick = onReviewEdits)) {
        EvBanner(
            if (pendingEditCount == 1) "1 change to review" else "$pendingEditCount changes to review",
            variant = BannerVariant.Amber,
            leadingIcon = EvIcons.Edit,
        )
    }
}

/**
 * The two web-claim doors, as a card rather than a bare icon (WEB_CLAIM_SPEC.md §3.9.2, §3.9.3).
 *
 * "Let everyone claim" leads, because handing the QR round is the thing that turns a twelve-person bill
 * from an evening of typing into two minutes. It is worded as the outcome, not the mechanism: a payer
 * looking for a way to stop assigning by hand will not search for "QR code".
 */
@Composable
internal fun WebClaimActions(
    onShareLink: () -> Unit,
    onWhoIsLeft: () -> Unit,
    stillToClaimCount: Int,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.page)
            .border(1.dp, c.border, RoundedCornerShape(12.dp)),
    ) {
        WebClaimRow(
            icon = EvIcons.Qr,
            title = "Let everyone claim",
            sub = "Hold up a code. They pick what they had, no app needed.",
            onClick = onShareLink,
            first = true,
        )
        if (stillToClaimCount > 0) {
            WebClaimRow(
                icon = EvIcons.Users,
                title = "Who's still to claim",
                sub = if (stillToClaimCount == 1) "1 person hasn't claimed yet" else "$stillToClaimCount people haven't claimed yet",
                onClick = onWhoIsLeft,
                first = false,
            )
        }
    }
}

@Composable
private fun WebClaimRow(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    first: Boolean,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.topHairline(c.border))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(icon, size = 18.dp, tint = c.blueText)
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = c.ink2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.ink3)
    }
}
