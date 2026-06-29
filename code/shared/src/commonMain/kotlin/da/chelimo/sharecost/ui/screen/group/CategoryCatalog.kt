package da.chelimo.sharecost.ui.screen.group

import androidx.compose.ui.graphics.vector.ImageVector
import da.chelimo.sharecost.ui.components.icon.ScIcons

/**
 * UI-layer resolution of a category's [iconToken][da.chelimo.sharecost.domain.expense.GroupCategory.iconToken]
 * to a vector, plus the palettes the Edit-Categories editor offers for custom categories. Icons live in
 * the UI layer (the domain stores only an opaque token string), mirroring the default-category mapping in
 * `GroupBalancesMapping.categoryIcon`.
 */
object CategoryCatalog {
    data class IconOption(val token: String, val icon: ImageVector)

    /** Selectable icons for a custom category (token == lowercase name; resolved by [icon]). */
    val icons: List<IconOption> = listOf(
        IconOption("food", ScIcons.Food),
        IconOption("groceries", ScIcons.Cart),
        IconOption("transport", ScIcons.Car),
        IconOption("lodging", ScIcons.Bed),
        IconOption("entertainment", ScIcons.Ticket),
        IconOption("shopping", ScIcons.Tag),
        IconOption("utilities", ScIcons.Bolt),
        IconOption("health", ScIcons.Sparkle),
        IconOption("gift", ScIcons.Gift),
        IconOption("home", ScIcons.Home),
        IconOption("receipt", ScIcons.Receipt),
        IconOption("calendar", ScIcons.Calendar),
        IconOption("globe", ScIcons.Globe),
        IconOption("star", ScIcons.Star),
        IconOption("camera", ScIcons.Camera),
        IconOption("other", ScIcons.Wallet),
    )

    /** Selectable colors (ARGB longs) — the spending-tracker palette plus a couple of extra accents. */
    val colors: List<Long> = listOf(
        0xFF2563EB, // blue
        0xFF38BDF8, // sky
        0xFF818CF8, // indigo
        0xFFA78BFA, // violet
        0xFF2DD4BF, // teal
        0xFF60A5FA, // light blue
        0xFFF59E0B, // amber
        0xFFF472B6, // pink
        0xFF34D399, // emerald
        0xFFFB7185, // rose
        0xFF94A3B8, // slate
    )

    private val byToken: Map<String, ImageVector> = icons.associate { it.token to it.icon }

    /** Resolve any token (default or custom) to a vector; falls back to the wallet glyph. */
    fun icon(token: String): ImageVector = byToken[token] ?: ScIcons.Wallet
}
