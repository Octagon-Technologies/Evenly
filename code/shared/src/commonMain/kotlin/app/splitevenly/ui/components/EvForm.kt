package app.splitevenly.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** `.sc-field` — a label over its content. */
@Composable
fun EvField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = EvenlyTheme.colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
        content()
    }
}

/** `.sc-input` — editable text field. Strong hairline → 2dp blue ring on focus. */
@Suppress("LongParameterList") // One styled field stands in for every input in the app.
@Composable
fun EvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 52.dp,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    // Set for a field holding an identifier rather than prose (a payment handle, a code). The keyboard
    // capitalizes the first letter and autocorrects by default, silently rewriting what was typed.
    identifier: Boolean = false,
) {
    val c = EvenlyTheme.colors
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    // Focus wins (blue ring); otherwise a required-but-empty field reads red so the gap is obvious.
    val borderColor =
        if (focused) {
            c.blue
        } else if (isError) {
            c.danger
        } else {
            c.borderStrong
        }
    val borderWidth =
        if (focused) {
            2.dp
        } else if (isError) {
            1.5.dp
        } else {
            1.dp
        }
    // A single-line field's Enter should complete + collapse the keyboard, never insert a newline.
    val effectiveImeAction =
        when {
            imeAction != ImeAction.Default -> imeAction
            singleLine -> ImeAction.Done
            else -> ImeAction.Default
        }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        textStyle = TextStyle(color = c.ink, fontSize = 16.sp, fontFamily = if (mono) EvenlyTheme.monoFamily else null),
        cursorBrush = SolidColor(c.blue),
        interactionSource = interaction,
        visualTransformation = visualTransformation,
        keyboardOptions =
            KeyboardOptions(
                capitalization = if (identifier) KeyboardCapitalization.None else KeyboardCapitalization.Unspecified,
                autoCorrectEnabled = if (identifier) false else null,
                keyboardType = keyboardType,
                imeAction = effectiveImeAction,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = { focusManager.clearFocus() },
                onGo = { focusManager.clearFocus() },
                onSend = { focusManager.clearFocus() },
                onSearch = { focusManager.clearFocus() },
            ),
        decorationBox = { inner ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .clip(shape)
                        .background(c.page)
                        .border(borderWidth, borderColor, shape)
                        .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 12.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = c.ink3, fontSize = 16.sp, fontFamily = if (mono) EvenlyTheme.monoFamily else null)
                    }
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

/** A read-only `.sc-input` styled as a tappable picker (value + chevron). */
@Composable
fun EvSelectField(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailingIcon: ImageVector = EvIcons.ChevD,
    valueColor: androidx.compose.ui.graphics.Color = EvenlyTheme.colors.ink,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(c.page)
                .border(1.dp, c.borderStrong, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Text(value, color = valueColor, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        EvIcon(trailingIcon, size = 16.dp, tint = c.ink3)
    }
}

/** `.sc-toggle` — 48x30 switch. */
@Composable
fun EvToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val knobX by animateDpAsState(if (checked) 21.dp else 3.dp)
    Box(
        modifier =
            modifier
                .size(width = 48.dp, height = 30.dp)
                .clip(CircleShape)
                .background(if (checked) c.blue else c.borderStrong)
                .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(start = knobX, top = 3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color.White),
        )
    }
}

/** `.sc-radio` — 22dp; selected fills blue. */
@Composable
fun EvRadio(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    Box(
        modifier =
            modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(if (selected) Modifier.background(c.blue) else Modifier.border(2.dp, c.borderStrong, CircleShape))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.page))
    }
}

/** `.sc-check` — 24dp rounded checkbox; checked fills blue with a white tick. */
@Composable
fun EvCheck(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier =
            modifier
                .size(24.dp)
                .clip(shape)
                .then(if (checked) Modifier.background(c.blue) else Modifier.border(2.dp, c.borderStrong, shape))
                .then(if (onCheckedChange != null) Modifier.clickable { onCheckedChange(!checked) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) EvIcon(EvIcons.Check, size = 16.dp, tint = c.onAccent)
    }
}
