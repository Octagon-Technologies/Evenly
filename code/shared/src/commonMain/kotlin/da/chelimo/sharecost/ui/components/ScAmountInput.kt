package da.chelimo.sharecost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlin.math.roundToLong

/**
 * An editable money field (major units, 2dp) with a currency symbol prefix and a pencil affordance so
 * it reads as *editable* — used for partial-settle amounts where the value defaults to the balance but
 * the user may pay less. Keep the text in caller state; convert with [amountTextToSubunits].
 */
@Composable
fun ScAmountInput(
    text: String,
    onTextChange: (String) -> Unit,
    currency: String,
    modifier: Modifier = Modifier,
    amountFontSize: TextUnit = 26.sp,
    helper: String? = null,
    helperColor: androidx.compose.ui.graphics.Color = ShareCostTheme.colors.ink3,
) {
    val c = ShareCostTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { onTextChange(sanitizeAmount(it)) },
            singleLine = true,
            textStyle = TextStyle(color = c.ink, fontSize = amountFontSize, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily),
            cursorBrush = SolidColor(c.blue),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(c.page)
                        .border(if (focused) 2.dp else 1.dp, if (focused) c.blue else c.borderStrong, shape)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(currencySymbol(currency), color = c.ink3, fontSize = amountFontSize, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text("0.00", color = c.ink3, fontSize = amountFontSize, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                        inner()
                    }
                    ScIcon(ScIcons.Edit, size = 18.dp, tint = if (focused) c.blue else c.ink3)
                }
            },
        )
        helper?.let { Text(it, color = helperColor, fontSize = 12.sp) }
    }
}

/** Keep only digits + one decimal point, capped at two fractional digits. */
private fun sanitizeAmount(text: String): String {
    val filtered = text.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    val oneDot = if (dot < 0) filtered else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
    val parts = oneDot.split(".")
    return if (parts.size == 2) parts[0] + "." + parts[1].take(2) else oneDot
}

/** Parse a major-unit amount string (e.g. "12.50") into minor units (subunits). Blank/invalid → 0. */
fun amountTextToSubunits(text: String): Long {
    val cleaned = text.replace(",", "").trim()
    if (cleaned.isEmpty()) return 0
    val value = cleaned.toDoubleOrNull() ?: return 0
    return (value * 100.0).roundToLong()
}
