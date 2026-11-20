package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The top of a bottom sheet, drawn the way each OS draws its own:
 * - **iOS**: a grabber, then a round glass close button top-left with the title centered on the same line.
 * - **Android**: Material 3's drag handle and a left-aligned title, with no close button. The sheet closes
 *   by swiping down, tapping outside, or Back, which is what Android users expect of a bottom sheet.
 *
 * [dragModifier] carries the swipe-to-close gesture, so only the chrome drags and a scrolling body never
 * fights it. [onClose] null means the sheet has no close affordance of its own (none is drawn on either OS).
 */
@Composable
internal fun EvSheetHeader(
    title: String?,
    onClose: (() -> Unit)?,
    dragModifier: Modifier,
) {
    val c = EvenlyTheme.colors
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    if (isIOS()) {
        Column(dragModifier.fillMaxWidth()) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c.borderStrong),
            )
            if (title != null || onClose != null) {
                Box(Modifier.fillMaxWidth().height(52.dp).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                    title?.let {
                        Text(
                            it,
                            Modifier.padding(horizontal = 52.dp),
                            color = c.ink,
                            style = titleStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                    if (onClose != null) {
                        Box(Modifier.align(Alignment.CenterStart)) { EvGlassCloseButton(onClose) }
                    }
                }
            }
        }
    } else {
        // The handle announces itself and closes the sheet for TalkBack, since Android draws no close button.
        Column(dragModifier.fillMaxWidth()) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 14.dp)
                    .semantics {
                        contentDescription = "Drag handle"
                        if (onClose != null) onClick(label = "Close sheet") { onClose(); true }
                    }
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

/**
 * The iOS sheet close button: a 44pt circle (Apple's minimum hit target) with a frosted fill and a light rim,
 * the closest Compose gets to the system's glass button without a live blur of what is behind it.
 */
@Composable
internal fun EvGlassCloseButton(onClose: () -> Unit) {
    val c = EvenlyTheme.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(c.ink.copy(alpha = 0.14f), c.ink.copy(alpha = 0.07f))))
            .border(1.dp, c.ink.copy(alpha = 0.12f), CircleShape)
            .clickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        EvIcon(EvIcons.Close, size = 16.dp, tint = c.ink, contentDescription = "Close")
    }
}
