package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.theme.EvenlyTheme

/** Avatar sizes (`.sc-av--lg/--sm/--xs`; default md). */
enum class AvatarSize(val dp: Dp, val font: TextUnit) {
    Lg(56.dp, 20.sp),
    Md(36.dp, 13.sp),
    Sm(28.dp, 11.sp),
    Xs(22.dp, 9.sp),
}

private fun initialsOf(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    return parts.take(2).joinToString("") { it.first().uppercaseChar().toString() }
}

/** `.sc-av` — circular initials avatar. `me` → blue fill / white; others → surface + hairline. */
@Composable
fun EvAvatar(
    name: String,
    modifier: Modifier = Modifier,
    me: Boolean = false,
    size: AvatarSize = AvatarSize.Md,
    onClick: (() -> Unit)? = null,
) {
    val colors = EvenlyTheme.colors
    Box(
        // clip BEFORE clickable so the press ripple is bounded to the circle, not a square.
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (me) colors.blue else colors.surface)
            .then(if (me) Modifier else Modifier.border(1.dp, colors.border, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = if (me) colors.onAccent else colors.ink2,
            fontSize = size.font,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** `.sc-av-stack` — overlapping avatars, each with a page-colored ring separating them. */
@Composable
fun EvAvatarStack(
    names: List<String>,
    modifier: Modifier = Modifier,
    size: AvatarSize = AvatarSize.Sm,
    me: (String) -> Boolean = { it == "You" },
) {
    val colors = EvenlyTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        names.forEach { n ->
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.page)
                    .padding(2.dp),
            ) {
                EvAvatar(name = n, me = me(n), size = size)
            }
        }
    }
}
