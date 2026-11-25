package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The top of a bottom sheet, drawn the way each OS draws its own:
 * - **iOS**: a grabber and a centered title, like a system sheet at a partial height (Maps, Find My).
 * - **Android**: Material 3's drag handle and a left-aligned title.
 *
 * Neither OS draws a close button: a partial-height sheet closes by swiping down, tapping outside, or Back,
 * and the HIG and Material 3 keep an explicit Cancel for full-height tasks only (owner call, 2026-11-24,
 * `design/brainstorm/close-button-audit.html`). The handle carries a "Close sheet" accessibility action
 * so VoiceOver and TalkBack users still have a named way out. Swipe-to-close lives on the whole sheet
 * ([rememberSheetSwipe]), not here.
 */
@Composable
internal fun EvSheetHeader(
    title: String?,
    onClose: (() -> Unit)?,
) {
    val c = EvenlyTheme.colors
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    if (isIOS()) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 4.dp)
                    .closeSemantics(onClose)
                    .size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c.borderStrong),
            )
            title?.let {
                Box(Modifier.fillMaxWidth().height(52.dp).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp),
                        color = c.ink,
                        style = titleStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 14.dp)
                    .closeSemantics(onClose)
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.ink3.copy(alpha = 0.6f)),
            )
            title?.let {
                Text(it, Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 14.dp), color = c.ink, style = titleStyle)
            }
        }
    }
}

/** The sheet's outer shape: a floating card on iOS (it is inset from the screen edges), top-rounded on Android. */
internal fun evSheetShape(): Shape = if (isIOS()) RoundedCornerShape(32.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Names the handle for screen readers and gives it the sheet's close action, since no close button is drawn. */
private fun Modifier.closeSemantics(onClose: (() -> Unit)?): Modifier =
    semantics {
        contentDescription = "Drag handle"
        if (onClose != null) {
            onClick(label = "Close sheet") {
                onClose()
                true
            }
        }
    }
