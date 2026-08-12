package app.splitevenly.ui.screen.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.theme.EvenlyTheme
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.StoreTransaction
import com.revenuecat.purchases.kmp.ui.revenuecatui.Paywall
import com.revenuecat.purchases.kmp.ui.revenuecatui.PaywallListener
import com.revenuecat.purchases.kmp.ui.revenuecatui.PaywallOptions

/**
 * The subscription paywall (`PRO_PASS_SPEC.md` §8.2) — **RevenueCat's, not ours**.
 *
 * We deliberately do not write this screen. It is built in the RevenueCat dashboard and rendered by the
 * `Paywall()` composable, so layout, copy and price mix become a dashboard change and Experiments can
 * test them without an app release. That is the entire reason Evenly grew a subscription at all (§2.1),
 * so re-implementing it in Compose would throw away the thing it was added for.
 *
 * Two requirements ride on the dashboard's own configuration rather than on this file: the **renewal
 * date stated before the charge**, and Terms / Privacy / Restore, which App Review rejects a paywall
 * without.
 *
 * The one piece Evenly owns is the **escape hatch beneath it**. §8.2 prefers it as a paywall footer
 * link so the whole surface stays remotely editable; the v2 editor's footer cannot express a navigation
 * action into an app-owned sheet, so it sits here instead, directly under the composable and inside the
 * same scroll surface. It is not optional: it is the mitigation for the exact risk that kept v1
 * subscription-free, which is a friend buying a $1 unlock for a ski weekend and being billed for two
 * years.
 */
@Composable
fun ProPaywallScreen(
    offering: Offering?,
    onDismiss: () -> Unit,
    onPurchased: (StoreTransaction) -> Unit,
    onRestored: () -> Unit,
    onWantsOneTrip: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val options = PaywallOptions(dismissRequest = onDismiss) {
        this.offering = offering
        shouldDisplayDismissButton = true
        listener = object : PaywallListener {
            override fun onPurchaseCompleted(customerInfo: CustomerInfo, storeTransaction: StoreTransaction) {
                // RevenueCat says a purchase happened; our server still decides who is Pro (§5), so this
                // hands off to `sync-subscriber` rather than flipping anything on locally.
                onPurchased(storeTransaction)
            }

            override fun onRestoreCompleted(customerInfo: CustomerInfo) = onRestored()
        }
    }
    Column(Modifier.fillMaxSize().background(c.page)) {
        Box(Modifier.weight(1f)) { Paywall(options) }
        Box(
            Modifier.fillMaxWidth().background(c.page).navigationBarsPadding()
                .clickable(onClick = onWantsOneTrip)
                .padding(vertical = 14.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Only need it for one trip?",
                color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
