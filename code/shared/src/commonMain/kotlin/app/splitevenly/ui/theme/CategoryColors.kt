package app.splitevenly.ui.theme

import androidx.compose.ui.graphics.Color
import app.splitevenly.domain.expense.ExpenseCategory

/**
 * Categorical palette for the spending-tracker donut + legend (UI concern only). The app is
 * intentionally near-monochrome (blue is the only chroma elsewhere), but a pie needs slices that read
 * apart at a glance — so this is a curated, muted blue→indigo→teal family with a couple of warm
 * accents, ordered to keep adjacent common categories distinct. Stable per category so the same slice
 * keeps its colour across Personal/Group toggles.
 */
fun categoryColor(category: ExpenseCategory): Color = when (category) {
    ExpenseCategory.LODGING -> Color(0xFF2563EB)        // blue (primary chroma)
    ExpenseCategory.FOOD -> Color(0xFF38BDF8)           // sky
    ExpenseCategory.TRANSPORT -> Color(0xFF818CF8)      // indigo
    ExpenseCategory.ENTERTAINMENT -> Color(0xFFA78BFA)  // violet
    ExpenseCategory.GROCERIES -> Color(0xFF2DD4BF)      // teal
    ExpenseCategory.SHOPPING -> Color(0xFFF59E0B)       // amber accent
    ExpenseCategory.UTILITIES -> Color(0xFF60A5FA)      // light blue
    ExpenseCategory.HEALTH -> Color(0xFFF472B6)         // pink accent
    ExpenseCategory.OTHER -> Color(0xFF94A3B8)          // slate
}
