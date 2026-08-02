package app.splitevenly.ui.screen.reconcile

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.money
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * 21 · Reconcile — confirmation modal (design/src/screens-misc.jsx · ReconcileConfirm).
 *
 * Recaps the expenses about to be claimed (the demo set logged under the claimed [names]) before they
 * move onto the user's balance. Centered `.sc-modal` dialog; blue swap glyph, blue "Yes, claim them"
 * primary + a text cancel.
 */
@Composable
fun ReconcileConfirmModal(
    names: List<String>,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val c = EvenlyTheme.colors

    // Demo placeholder data (ported 1:1 from the web design).
    val claimed = listOf(
        "Dinner at La Negra" to 24.0,
        "Airport taxi" to 14.5,
        "Cenote day trip" to 18.0,
        "Supermarket run" to 9.4,
        "Beach drinks" to 12.0,
    )

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
                    "Claim ${claimed.size} expenses?",
                    color = c.ink,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "These will move onto your balance in Tulum Trip.",
                    color = c.ink2,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            EvCard {
                claimed.forEachIndexed { i, (title, amount) ->
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
                            title,
                            color = c.ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            money(amount),
                            color = c.ink3,
                            fontSize = 12.sp,
                            fontFamily = EvenlyTheme.monoFamily,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvButton("Yes, claim them", onConfirm, leadingIcon = EvIcons.Check)
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

@Preview
@Composable
private fun ReconcileConfirmPreview() {
    EvenlyTheme {
        ReconcileConfirmModal(names = listOf("Alex R.", "A. Rivera"))
    }
}
