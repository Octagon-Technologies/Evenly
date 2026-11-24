package app.splitevenly.ui.screen.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme
import coil3.compose.AsyncImage

/**
 * "Add a screenshot" on a payment sheet (docs/features/payment-proof.md). A big labelled tile rather than
 * an icon, so nobody misses it; optional, so the record button never waits on it. [imageModel] is the
 * staged copy once one is picked (a `file://` path), which turns the tile into the thumbnail with a remove.
 */
@Composable
fun PaymentProofTile(
    otherName: String,
    imageModel: String?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val has = imageModel != null
    val dash = c.border
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (has) {
                    Modifier.background(c.page).border(1.dp, c.border, shape)
                } else {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = dash,
                            cornerRadius = CornerRadius(14.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    }
                },
            ).clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (imageModel != null) {
            Box {
                AsyncImage(
                    model = imageModel,
                    contentDescription = "Payment screenshot",
                    modifier = Modifier.size(width = 40.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.blueTint),
                contentAlignment = Alignment.Center,
            ) { EvIcon(EvIcons.Image, size = 19.dp, tint = c.blueText) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (has) "Screenshot attached" else "Add a screenshot",
                color = c.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (has) "$otherName sees it with the payment. Tap to change it." else "Optional. $otherName sees it with the payment.",
                color = c.ink2,
                fontSize = 12.sp,
            )
        }
        if (has) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .semantics {
                        contentDescription = "Remove screenshot"
                        role = Role.Button
                    }.clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { EvIcon(EvIcons.Close, size = 16.dp, tint = c.ink2) }
        }
    }
}

/**
 * A payment's screenshot as a small thumbnail in a list: the image, a spinner over it while it uploads, or
 * a dashed "+" slot when [onAdd] is set and there is none yet (only the two people in a payment get one).
 */
@Composable
fun PaymentProofThumb(
    url: String?,
    uploadingModel: String?,
    onOpen: () -> Unit,
    onAdd: (() -> Unit)?,
    width: Dp = 30.dp,
    height: Dp = 40.dp,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(7.dp)
    val box = Modifier.size(width = width, height = height).clip(shape)
    when {
        uploadingModel != null -> {
            Box(box.border(1.dp, c.border, shape), contentAlignment = Alignment.Center) {
                AsyncImage(uploadingModel, "Payment screenshot uploading", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.35f)))
                CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }

        url != null -> {
            AsyncImage(
                model = url,
                contentDescription = "Payment screenshot",
                modifier = box.border(1.dp, c.border, shape).clickable(onClick = onOpen),
                contentScale = ContentScale.Crop,
            )
        }

        onAdd != null -> {
            val dash = c.blueText
            Box(
                box
                    .drawBehind {
                        drawRoundRect(
                            color = dash,
                            cornerRadius = CornerRadius(7.dp.toPx()),
                            style = Stroke(width = 1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                        )
                    }.semantics {
                        contentDescription = "Add a screenshot"
                        role = Role.Button
                    }.clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { EvIcon(EvIcons.Plus, size = 14.dp, tint = c.blueText) }
        }
    }
}

@Preview
@Composable
private fun PaymentProofTilePreview() {
    EvenlyTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaymentProofTile("Andrew", null, {}, {})
            PaymentProofTile("Andrew", "file:///x.jpg", {}, {})
        }
    }
}
