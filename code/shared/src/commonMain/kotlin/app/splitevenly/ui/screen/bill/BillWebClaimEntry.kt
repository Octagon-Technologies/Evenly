package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.icon.EvIcons

/**
 * Guest edits the payer has not looked at yet, above everything else on the assign screen.
 *
 * It is a **notification, not a call to action**: the changes have already applied, and the payer's
 * amounts and the guests' amounts already agree. So it says what happened rather than asking for a
 * decision, and it clears by being *read* (opening the screen marks them seen), not by anyone deciding
 * anything. [unseenCount] is what drives it, so the list of past changes can outlive the banner.
 */
@Composable
internal fun PendingEditsBanner(
    unseenCount: Int,
    onReviewEdits: () -> Unit,
) {
    if (unseenCount <= 0) return
    Row(Modifier.fillMaxWidth().clickable(onClick = onReviewEdits)) {
        EvBanner(
            if (unseenCount == 1) "1 change to the bill" else "$unseenCount changes to the bill",
            variant = BannerVariant.Amber,
            leadingIcon = EvIcons.Edit,
        )
    }
}
