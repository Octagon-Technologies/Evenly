package app.splitevenly.ui.screen.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** One expense a claim would move, already formatted for display. */
data class ClaimLineUi(val title: String, val amountLabel: String)

/**
 * The confirm sheet for claiming a name. Every "That's me" lands here first.
 *
 * The card never merges directly, and this sheet's whole job is to **show the money before it moves**:
 * the expenses the claimer takes on, what that adds to what they owe, and anything the name paid for
 * that they will be recorded as having paid. Balances change for everyone in the group, so the number
 * has to be visible before the tap, not discoverable after it.
 *
 * A name with no history at all is still claimable (it is a candidate identity), and the sheet says so
 * plainly rather than showing an empty box.
 */
@Composable
fun ReconcileConfirmModal(
    name: String,
    owed: List<ClaimLineUi>,
    paid: List<ClaimLineUi>,
    owedTotalLabel: String?,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val c = EvenlyTheme.colors

    EvModalScaffold(onDismiss = onCancel) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.blueTint),
                    contentAlignment = Alignment.Center,
                ) {
                    EvIcon(EvIcons.Swap, size = 26.dp, tint = c.blueText)
                }
                Text(
                    "Move $name's history to you?",
                    color = c.ink,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (owed.isEmpty() && paid.isEmpty()) {
                        "$name stops being a separate person in this group. Nothing moves, because nothing has been logged under that name yet."
                    } else {
                        "$name stops being a separate person in this group. Everyone's balances change."
                    },
                    color = c.ink2,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                // Long histories scroll inside the sheet instead of pushing the buttons off screen.
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (owed.isNotEmpty()) {
                    LineSection(
                        heading = "YOU'LL TAKE ON",
                        lines = owed,
                        totalLabel = "Added to what you owe",
                        totalAmount = owedTotalLabel,
                    )
                }
                if (paid.isNotEmpty()) {
                    LineSection(
                        heading = "YOU'LL BE RECORDED AS HAVING PAID",
                        lines = paid,
                        totalLabel = null,
                        totalAmount = null,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvButton("Yes, that's me", onConfirm)
                EvButton(
                    "Cancel",
                    onCancel,
                    variant = ButtonVariant.Text,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun LineSection(
    heading: String,
    lines: List<ClaimLineUi>,
    totalLabel: String?,
    totalAmount: String?,
) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            heading,
            color = c.ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        EvCard {
            lines.forEachIndexed { i, line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        EvIcon(EvIcons.Receipt, size = 16.dp, tint = c.ink2)
                    }
                    Text(
                        line.title,
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        line.amountLabel,
                        color = c.ink3,
                        fontSize = 12.sp,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                }
            }
            // The total is the number that actually changes a balance, so it is never left to be
            // added up by eye. Absent when the lines span currencies, where no single total exists.
            if (totalLabel != null && totalAmount != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().topHairline(c.border)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        totalLabel,
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        totalAmount,
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun ReconcileConfirmPreview() {
    EvenlyTheme {
        ReconcileConfirmModal(
            name = "Chelimo",
            owed = listOf(
                ClaimLineUi("Dinner at La Negra", "$24.00"),
                ClaimLineUi("Airport taxi", "$14.50"),
                ClaimLineUi("Cenote trip", "$18.00"),
            ),
            paid = listOf(ClaimLineUi("Beach drinks", "$36.00")),
            owedTotalLabel = "$56.50",
        )
    }
}
