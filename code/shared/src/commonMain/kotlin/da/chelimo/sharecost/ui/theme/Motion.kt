package da.chelimo.sharecost.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

/**
 * Shared timing for the app's animations, so a sheet, a tab switch, and a nav transition all move at
 * the same tempo instead of each call site inventing its own. Durations are short on purpose: these are
 * "this UI has focus now" cues, not decoration — a sheet that takes too long to arrive reads as lag.
 */
object ScMotion {
    const val Quick = 160
    const val Standard = 220

    fun <T> quick() = tween<T>(Quick, easing = FastOutSlowInEasing)
    fun <T> standard() = tween<T>(Standard, easing = FastOutSlowInEasing)
}
