package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** Which half of the camera gate to show: the primer before the OS prompt, or the recovery after a refusal. */
enum class CameraPromptKind { Primer, Blocked }

/**
 * The camera gate's sheet, in both states.
 *
 * **No scrim, by design.** The scan card behind stays at full brightness so the sheet reads as part of the
 * screen the user was already on, not a trap door. It separates itself with a hairline and a rounded lift
 * instead, the same call [EvDragSheet] makes. Only an invisible tap catcher above it dismisses on an
 * outside tap.
 *
 * [photosLabel] differs per platform because the two OSes name the app the picker opens differently.
 */
@Composable
fun CameraPermissionSheet(
    kind: CameraPromptKind,
    photosLabel: String,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onUsePhotos: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Set when the OS refused to open its own settings screen. The written path stays on screen instead of
    // being replaced by a screen that never arrived.
    settingsFailed: Boolean = false,
) {
    val c = EvenlyTheme.colors
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(sheetShape)
                .background(c.page)
                .border(1.dp, c.border, sheetShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .padding(vertical = 6.dp)
                        .size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.borderStrong),
                )
            }

            when (kind) {
                CameraPromptKind.Primer -> PrimerBody(onAllow, onDismiss)
                CameraPromptKind.Blocked -> BlockedBody(settingsFailed)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (kind) {
                    CameraPromptKind.Primer -> EvButton("Allow", onAllow)
                    CameraPromptKind.Blocked -> EvButton("Open Settings", onOpenSettings)
                }
                EvButton(
                    photosLabel,
                    onUsePhotos,
                    // The Text variant sizes to its content, so without this it hugs the left edge.
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    variant = ButtonVariant.Text,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.PrimerBody(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Turn on camera", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "Grant Evenly access to your camera to take a picture of your receipt.",
            color = c.ink2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
    SystemAlertPreview(onAllow, onDismiss)
}

@Composable
private fun ColumnScope.BlockedBody(settingsFailed: Boolean) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Camera is off for Evenly", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            if (settingsFailed) {
                "Settings didn't open. Find Evenly in your device settings and turn Camera on."
            } else {
                "Your device only asks once, and it already did. Turn it back on to scan with the camera."
            },
            color = c.ink2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Step(1, "Tap Open Settings below, then Evenly")
        Step(2, "Turn on Camera")
    }
    SettingsRowPreview()
}

@Composable
private fun Step(number: Int, text: String) {
    val c = EvenlyTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(c.blueTint),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = c.blueText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = c.ink2, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

/**
 * A small drawing of the OS permission dialog the user is one tap away from, with Allow emphasised.
 *
 * Both halves are tappable and route where they say they do, so the drawing is a shortcut rather than
 * a decoration that swallows taps.
 *
 * Recognising the shape beats reading a sentence about permissions: nobody has to work out what happens
 * next or which side to tap. The body sentence mirrors `NSCameraUsageDescription` in
 * `iosApp/iosApp/Info.plist`, which is what iOS actually renders. **Change both or neither**, or the
 * rehearsal starts lying about the real thing.
 */
@Composable
private fun SystemAlertPreview(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(232.dp)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.border, shape),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "\"Evenly\" Would Like to Access the Camera",
                    color = c.ink,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Evenly uses your camera to snap receipts, so you can split a bill by scanning it. " +
                        "Photos stay on your device and are only attached to the expense you add them to.",
                    color = c.ink2,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Row(Modifier.fillMaxWidth()) {
                // Both halves are live and do what they depict. A drawing of a button that ignores a tap
                // is the dead end this whole gate exists to remove.
                AlertAction("Don't Allow", Modifier.weight(1f), emphasised = false, onClick = onDismiss)
                Box(Modifier.width(1.dp).height(38.dp).background(c.border))
                AlertAction("Allow", Modifier.weight(1f), emphasised = true, onClick = onAllow)
            }
        }
    }
}

@Composable
private fun AlertAction(label: String, modifier: Modifier, emphasised: Boolean, onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    Box(
        modifier
            .height(38.dp)
            .then(if (emphasised) Modifier.background(c.blueTint) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (emphasised) c.blueText else c.ink3,
            fontSize = 12.5.sp,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** The settings row the user is about to go looking for, with the Camera switch drawn in its off state. */
@Composable
private fun SettingsRowPreview() {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
            .padding(vertical = 4.dp),
    ) {
        // iOS renders this header on the app's own settings page, and Open Settings does not always land
        // there (a simulator, and some OS versions, drop you at the root). Without it the drawing is two
        // unlabelled switches and "turn on Camera" has no findable home.
        Text(
            "ALLOW EVENLY TO ACCESS",
            color = c.ink3,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
        )
        SettingsToggleRow(EvIcons.Camera, "Camera", on = false)
        SettingsToggleRow(EvIcons.Image, "Photos", on = true)
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    label: String,
    on: Boolean,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) c.ink3 else c.blue),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(icon, size = 13.dp, tint = c.onAccent)
        }
        Text(label, color = c.ink, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) c.settled else c.borderStrong),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.page),
            )
        }
    }
}
