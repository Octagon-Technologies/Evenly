package da.chelimo.sharecost.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/**
 * `.sc-exp` expense row: 40dp category tile, title + payer/attribution sub, and a trailing
 * [ScAmountText] (or a "Settled" chip). The design's signature line — remaining over original — is
 * the trailing amount.
 */
@Composable
fun ScExpenseRow(
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = ScIcons.Receipt,
    remaining: String? = null,
    original: String? = null,
    settled: Boolean = false,
    unread: Boolean = false,
    pending: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = ShareCostTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.page)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (settled) c.settledTint else c.surface),
            contentAlignment = Alignment.Center,
        ) {
            ScIcon(if (settled) ScIcons.Check else icon, size = 20.dp, tint = if (settled) c.settled else c.ink2)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pending) {
                Box(Modifier.padding(top = 5.dp)) { ScPendingPill() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settled) {
                ScChip("Settled", variant = ChipVariant.Green, leadingIcon = ScIcons.Check)
            } else if (remaining != null) {
                ScAmountText(remaining = remaining, original = original)
            }
            if (unread) ScDot()
            ScIcon(ScIcons.ChevR, size = 16.dp, tint = c.ink3)
        }
    }
}

/** `.sc-exp` with an avatar stack: "{from} owes {to}" + a trailing amount chip. */
@Composable
fun ScDebtRow(
    from: String,
    to: String,
    amount: String,
    modifier: Modifier = Modifier,
    owedToYou: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = ShareCostTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.page)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScAvatarStack(names = listOf(from, to), size = AvatarSize.Sm)
        Column(Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(from) }
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.ink2)) { append("owes") }
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(to) }
                },
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (owedToYou) "You're owed this" else "Tap to settle",
                style = MaterialTheme.typography.bodyMedium,
                color = if (owedToYou) c.credit else c.ink2,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Group-balance color code: you're owed / in credit → amber (attention, in your favor);
            // you owe → blue (the action). Mirrors the home group card.
            ScChip(amount, variant = if (owedToYou) ChipVariant.Credit else ChipVariant.Blue, mono = true)
            ScIcon(ScIcons.ChevR, size = 16.dp, tint = c.ink3)
        }
    }
}
