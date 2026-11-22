package app.splitevenly.ui.screen.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAmountInput
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.amountTextToSubunits
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * 16 · Deep-link confirmation sheet (design/src/screens-settle.jsx; one-time-splits mockup 7). The
 * amount is pre-filled with what was being settled and stays editable, so a partial payment is recorded
 * honestly in one step. "Yes, I paid" records exactly what was entered; it is not pending on anyone.
 */
@Composable
fun DeepLinkConfirmSheet(
    amountSubunits: Long = 2105,
    maxSubunits: Long = amountSubunits,
    currency: String = "USD",
    payeeName: String = "Bob",
    handle: String = "@andrew-p",
    app: String = "Venmo",
    // Set once the payment is recorded, so the sheet slides out instead of vanishing mid-frame.
    dismissRequested: Boolean = false,
    onDismiss: () -> Unit = {},
    onYes: (paidSubunits: Long) -> Unit = {},
    onCopy: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    // "Not yet" closes through the animation too, rather than calling onDismiss straight through.
    var closing by remember { mutableStateOf(false) }
    var amountText by remember(amountSubunits) { mutableStateOf(format2dp(amountSubunits / 100.0)) }
    val entered = amountTextToSubunits(amountText)
    var showProblem by remember { mutableStateOf(false) }
    val problem =
        when {
            entered <= 0L -> "Enter what you paid."
            entered > maxSubunits -> "It can't be more than the ${moneySubunits(maxSubunits, currency)} you owe."
            else -> null
        }
    EvSheetScaffold(onDismiss, dismissRequested = dismissRequested || closing) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                EvIcon(EvIcons.Link, size = 28.dp, tint = c.blueText)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Did the payment go through?",
                    color = c.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "We opened $app for $handle.",
                    color = c.ink2,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("You paid $payeeName", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                EvAmountInput(
                    text = amountText,
                    onTextChange = {
                        amountText = it
                        showProblem = false
                    },
                    currency = currency,
                    helper = if (showProblem && problem != null) problem else "of ${moneySubunits(maxSubunits, currency)}",
                    helperColor = if (showProblem && problem != null) c.danger else c.ink2,
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Live, validated on tap: a bad amount says why rather than greying the button out.
                EvButton("Yes, I paid", { if (problem == null) onYes(entered) else showProblem = true })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface)
                        .clickable(onClick = onCopy),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EvIcon(EvIcons.Copy, size = 18.dp, tint = c.ink2)
                    Text("Copy handle", color = c.ink, fontWeight = FontWeight.SemiBold)
                }
                EvButton("Not yet", { closing = true }, variant = ButtonVariant.Tonal)
            }
        }
    }
}

@Preview
@Composable
private fun DeepLinkConfirmPreview() {
    EvenlyTheme { DeepLinkConfirmSheet() }
}
