package app.splitevenly.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

enum class BannerVariant { Offline, Amber, Blue }

/** `.sc-banner` — a full-width status strip (offline / conflict / info). */
@Composable
fun EvBanner(
    text: String,
    modifier: Modifier = Modifier,
    variant: BannerVariant = BannerVariant.Offline,
    leadingIcon: ImageVector? = EvIcons.WifiOff,
) {
    val c = EvenlyTheme.colors
    val (bg, fg) = when (variant) {
        BannerVariant.Offline -> c.bannerOffline to c.bannerOfflineInk
        BannerVariant.Amber -> c.warningTint to c.warning
        BannerVariant.Blue -> c.blueTint to c.bluePressed
    }
    Row(
        modifier = modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leadingIcon?.let { EvIcon(it, size = 15.dp, tint = fg) }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** `.sc-pending` — the "Pending sync" pill shown under unsynced rows. */
@Composable
fun EvPendingPill(text: String = "Pending sync", modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    Row(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(c.warningTint).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        EvIcon(EvIcons.Reload, size = 11.dp, tint = c.warning)
        Text(text, color = c.warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** `.sc-empty` — centered empty/error state with a recolorable icon tile + optional CTA. */
@Composable
fun EvEmptyState(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = EvIcons.Receipt,
    iconTint: Color = EvenlyTheme.colors.ink3,
    iconBackground: Color = EvenlyTheme.colors.surface,
    ctaText: String? = null,
    onCta: (() -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(iconBackground), contentAlignment = Alignment.Center) {
            EvIcon(icon, size = 30.dp, tint = iconTint)
        }
        Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(text, color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 240.dp))
        if (ctaText != null && onCta != null) {
            EvButton(ctaText, onCta, modifier = Modifier.widthIn(max = 240.dp).padding(top = 6.dp))
        }
    }
}

/** `.sc-progress` — track + fill. [fraction] in 0..1. Defaults to the blue tint track + blue fill (so
 *  the bar reads at a glance even at low fill); pass [fill]/[track] to recolor (e.g. green when settled). */
@Composable
fun EvProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    fill: Color = EvenlyTheme.colors.blue,
    track: Color = EvenlyTheme.colors.blueTint2,
) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(fill))
    }
}

/** `.sc-skel` — shimmer placeholder block. */
@Composable
fun EvSkeleton(modifier: Modifier = Modifier, width: Dp? = null, height: Dp = 12.dp, radius: Dp = 8.dp) {
    val c = EvenlyTheme.colors
    val transition = rememberInfiniteTransition()
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
    )
    val brush = Brush.linearGradient(
        colors = listOf(c.skeleton1, c.skeleton2, c.skeleton1),
        start = Offset(x - 300f, 0f),
        end = Offset(x, 0f),
    )
    Box((if (width != null) modifier.width(width) else modifier.fillMaxWidth()).height(height).clip(RoundedCornerShape(radius)).background(brush))
}

/** A skeleton mirroring [EvExpenseRow] for list loading states. */
@Composable
fun EvSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvSkeleton(width = 40.dp, height = 40.dp, radius = 11.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EvSkeleton(width = 160.dp, height = 13.dp)
            EvSkeleton(width = 110.dp, height = 11.dp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EvSkeleton(width = 54.dp, height = 15.dp)
            EvSkeleton(width = 38.dp, height = 10.dp)
        }
    }
}

/** Small indeterminate spinner (e.g. inside a loading button). */
@Composable
fun EvSpinner(modifier: Modifier = Modifier, color: Color = EvenlyTheme.colors.onAccent, size: Dp = 18.dp) {
    CircularProgressIndicator(modifier = modifier.size(size), color = color, strokeWidth = 2.5.dp)
}
