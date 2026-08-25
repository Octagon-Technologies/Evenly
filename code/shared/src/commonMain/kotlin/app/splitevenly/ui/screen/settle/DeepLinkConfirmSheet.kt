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
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.money
import app.splitevenly.ui.theme.EvenlyTheme

/** 16 · Deep-link confirmation sheet (design/src/screens-settle.jsx). */
@Composable
fun DeepLinkConfirmSheet(
    amount: Double = 24.0,
    handle: String = "@andrew-p",
    app: String = "Venmo",
    // Set once the payment is recorded, so the sheet slides out instead of vanishing mid-frame.
    dismissRequested: Boolean = false,
    onDismiss: () -> Unit = {},
    onYes: () -> Unit = {},
    onCopy: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    // "Not yet" closes through the animation too, rather than calling onDismiss straight through.
    var closing by remember { mutableStateOf(false) }
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
                    "Settled ${money(amount)} with $handle?",
                    color = c.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "We opened $app. Did the payment go through?",
                    color = c.ink2,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvButton("Yes, mark paid", onYes, leadingIcon = EvIcons.Check)
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
