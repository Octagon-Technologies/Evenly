package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.splitevenly.domain.expense.GroupCategory
import app.splitevenly.domain.receipt.ReceiptSummary
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvSelectField
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.screen.group.CategoryCatalog
import app.splitevenly.ui.theme.EvenlyTheme

/** The add-expense editor's half-width Category picker row: the category's colored icon, or a tag icon. */
@Composable
internal fun CategorySelectField(
    selected: GroupCategory?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    EvField("Category", modifier = modifier) {
        EvSelectField(
            selected?.label ?: "Add category",
            onClick,
            valueColor = if (selected != null) c.ink else c.ink3,
            leading = {
                val tint = selected?.let { Color(it.colorHex) } ?: c.blueText
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(99.dp))
                        .background(if (selected != null) tint.copy(alpha = 0.16f) else c.blueTint),
                    contentAlignment = Alignment.Center,
                ) {
                    EvIcon(selected?.let { CategoryCatalog.icon(it.iconToken) } ?: EvIcons.Tag, size = 15.dp, tint = tint)
                }
            },
        )
    }
}

/** What a receipt quick read fills in. Each field is null where the editor must leave its value alone. */
internal data class ReceiptFill(
    val title: String?,
    val amountText: String?,
    val currency: String?,
)

/**
 * Nothing the user typed is ever overwritten: the title fills only a blank title, and the amount (with its
 * currency, which only means anything beside that amount) fills only an empty amount box.
 */
internal fun receiptFill(
    summary: ReceiptSummary,
    suggestedTitle: String?,
    currentTitle: String,
    currentAmountSubunits: Long,
): ReceiptFill {
    val fillAmount = currentAmountSubunits == 0L && summary.totalSubunits > 0
    return ReceiptFill(
        title = suggestedTitle?.takeIf { currentTitle.isBlank() && it.isNotBlank() },
        amountText = if (fillAmount) format2dp(summary.totalSubunits / 100.0) else null,
        currency = if (fillAmount) summary.currency else null,
    )
}
