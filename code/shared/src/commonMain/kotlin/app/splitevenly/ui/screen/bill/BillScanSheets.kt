package app.splitevenly.ui.screen.bill

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay

/**
 * The bottom sheet shown while OCR runs: a single centered animated scan icon, an indeterminate bar,
 * and rotating copy. Replaces the old row-of-page-tiles — [pages] is now only used to compute the
 * "page N of M" subtitle text, not rendered per-tile.
 */
@Composable
internal fun ScanProgressSheet(
    pages: List<ScanPageUi>,
    onCancel: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val phrases = remember { listOf("Reading the receipt…", "Finding the items…", "Adding up the totals…", "Almost there…") }
    var phraseIdx by remember { mutableStateOf(0) }
    // Cycle the copy on a timer. It's honest reassurance, not real progress — the round-trip is one call.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            phraseIdx = (phraseIdx + 1) % phrases.size
        }
    }
    val pageLine =
        if (pages.size == 1) {
            "1 page · this usually takes a few seconds"
        } else {
            "${pages.size} pages · this usually takes a few seconds"
        }
    EvSheetScaffold(onDismiss = onCancel, title = "Scanning your receipt", sub = pageLine) {
        Box(Modifier.fillMaxWidth().padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
            ScanPulseTile()
        }
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(99.dp)),
            color = c.blueText,
            trackColor = c.selectionTint,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvIcon(EvIcons.Sparkle, size = 15.dp, tint = c.blueText)
            Text(phrases[phraseIdx], color = c.ink2, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                "Cancel",
                color = c.ink2,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clip(
                            RoundedCornerShape(8.dp),
                        ).clickable(onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A rounded-square tile with a receipt icon and a thin blue "scan line" sweeping top-to-bottom-to-top. */
@Composable
private fun ScanPulseTile() {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val transition = rememberInfiniteTransition(label = "scanLine")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "scanLineSweep",
    )
    Box(
        Modifier
            .size(88.dp)
            .clip(shape)
            .background(c.selectionTint)
            .border(1.dp, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        EvIcon(EvIcons.Receipt, size = 34.dp, tint = c.blueText)
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .offset(y = 42.dp * (sweep * 2f - 1f))
                .background(c.blue.copy(alpha = 0.7f)),
        )
    }
}

/** The bottom sheet for a scan that couldn't finish. Copy + actions vary by [kind]; manual entry is always here. */
@Composable
internal fun ScanErrorSheet(
    kind: ScanErrorKind,
    onManual: () -> Unit,
    onRetry: () -> Unit,
    onPickAgain: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val icon =
        when (kind) {
            ScanErrorKind.Offline -> EvIcons.WifiOff
            ScanErrorKind.NoReceiptFound -> EvIcons.Receipt
            ScanErrorKind.Unavailable -> EvIcons.Info
            ScanErrorKind.Error -> EvIcons.Alert
            ScanErrorKind.Blocked -> EvIcons.Info
        }
    val tint =
        when (kind) {
            ScanErrorKind.Offline -> c.warning
            ScanErrorKind.Error -> c.danger
            else -> c.ink2
        }
    val heading =
        when (kind) {
            ScanErrorKind.Offline -> "You're offline"
            ScanErrorKind.NoReceiptFound -> "Couldn't read it"
            ScanErrorKind.Unavailable -> "Scanning isn't available"
            ScanErrorKind.Error -> "Something went wrong"
            ScanErrorKind.Blocked -> "Scanning too quickly"
        }
    val body =
        when (kind) {
            ScanErrorKind.Offline -> {
                "Scanning needs a connection. You can still type the bill in now."
            }

            ScanErrorKind.NoReceiptFound -> {
                "No items found. The photo may be blurry or not a receipt."
            }

            ScanErrorKind.Unavailable -> {
                "Receipt scanning isn't set up here. Add the bill by hand."
            }

            ScanErrorKind.Error -> {
                "The scan failed. Give it another try, or type it in."
            }

            ScanErrorKind.Blocked -> {
                "There's a short safety cooldown. Try again in a bit, or type it in."
            }
        }
    EvSheetScaffold(onDismiss = onManual) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(52.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { EvIcon(icon, size = 24.dp, tint = tint) }
        Text(
            heading,
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            color = c.ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            color = c.ink2,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        // Error → offer "Try again" as the hero; the rest lead with manual entry.
        when (kind) {
            ScanErrorKind.Error -> {
                EvButton(text = "Try again", onClick = onRetry)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    EvButton(text = "Enter manually", onClick = onManual, variant = ButtonVariant.Text)
                }
            }

            ScanErrorKind.Unavailable -> {
                EvButton(text = "Enter manually", onClick = onManual)
            }

            else -> {
                EvButton(text = "Enter manually", onClick = onManual)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    EvButton(
                        text = if (kind == ScanErrorKind.NoReceiptFound) "Try a new photo" else "Retry",
                        onClick = if (kind == ScanErrorKind.NoReceiptFound) onPickAgain else onRetry,
                        variant = ButtonVariant.Text,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScanSourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvIcon(icon, size = 20.dp, tint = c.blueText)
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
