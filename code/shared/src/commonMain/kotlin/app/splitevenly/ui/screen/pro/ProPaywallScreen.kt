@file:Suppress("FunctionNaming") // Composables are PascalCase, as everywhere else in ui/.

package app.splitevenly.ui.screen.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme
import app.splitevenly.ui.theme.ForceNativeDarkMode
import app.splitevenly.ui.theme.LocalIsDarkTheme
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.StoreTransaction
import com.revenuecat.purchases.kmp.ui.revenuecatui.Paywall
import com.revenuecat.purchases.kmp.ui.revenuecatui.PaywallListener
import com.revenuecat.purchases.kmp.ui.revenuecatui.PaywallOptions

/**
 * Gap between the legal row and the bottom of the screen.
 *
 * This footer deliberately does NOT take `navigationBarsPadding()`, unlike the rest of the app's
 * bottom chrome: at 12dp it clears the screen edge but sits inside the iOS home-indicator band, which
 * on Android with three-button navigation means the system bar overlaps it. Owner's call, made looking
 * at the iOS simulator. If the Android build ever shows the nav bar sitting on Terms, the fix is
 * `.padding(bottom = max(FOOTER_BOTTOM_MARGIN, navigation-bar inset))`, not reverting the intent.
 */
private val FOOTER_BOTTOM_MARGIN = 12.dp

/**
 * The subscription paywall (`PRO_PASS_SPEC.md` §8.2) — **RevenueCat's, not ours**.
 *
 * We deliberately do not write this screen. It is built in the RevenueCat dashboard and rendered by the
 * `Paywall()` composable, so layout, copy and price mix become a dashboard change and Experiments can
 * test them without an app release. That is the entire reason Evenly grew a subscription at all (§2.1),
 * so re-implementing it in Compose would throw away the thing it was added for.
 *
 * The **renewal date stated before the charge** still rides on the dashboard's own configuration.
 * Terms and Privacy used to as well, and no longer do: the dashboard renders them immediately under the
 * purchase button, mid-page once Evenly's own footer sits below, where two rows of small print read as
 * one interrupted thought. They are [LegalFooter] here instead, at the very bottom.
 *
 * **This only works if the dashboard's own Terms / Privacy links are turned off.** Leave them on and the
 * page shows both. App Review rejects a paywall carrying neither, so the two changes go together.
 *
 * Evenly owns the **chrome around it**: the back affordance is our `EvTopBar` rather than the
 * dashboard's floating dismiss button, because this paywall is reached by a push from Profile, Group
 * settings and the export row, and a close control that matches no other screen reads as a different app.
 * The bar needs [StatusBarScrim] above it, same as every other pushed screen: `EvTopBar` carries no
 * inset of its own, so without the scrim the back arrow drew level with the clock.
 *
 * The other piece Evenly owns is the **escape hatch beneath it**. §8.2 prefers it as a paywall footer
 * link so the whole surface stays remotely editable; the v2 editor's footer cannot express a navigation
 * action into an app-owned sheet, so it sits here instead, directly under the composable and inside the
 * same scroll surface. It is not optional: it is the mitigation for the exact risk that kept v1
 * subscription-free, which is a friend buying a $1 unlock for a ski weekend and being billed for two
 * years.
 */
@Suppress("LongParameterList") // Screens stay DI-free, so every collaborator arrives as a callback.
@Composable
fun ProPaywallScreen(
    offering: Offering?,
    onDismiss: () -> Unit,
    onPurchased: (StoreTransaction) -> Unit,
    onRestored: () -> Unit,
    onWantsOneTrip: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val options =
        PaywallOptions(dismissRequest = onDismiss) {
            this.offering = offering
            // RevenueCat's own dismiss control is a floating close button styled by the dashboard, so it
            // lands on a paywall reached by a push looking nothing like the back affordance on every other
            // screen. Ours goes in the `EvTopBar` below instead, which inherits the theme.
            shouldDisplayDismissButton = false
            listener =
                object : PaywallListener {
                    override fun onPurchaseCompleted(
                        customerInfo: CustomerInfo,
                        storeTransaction: StoreTransaction,
                    ) {
                        // RevenueCat says a purchase happened; our server still decides who is Pro (§5), so this
                        // hands off to `sync-subscriber` rather than flipping anything on locally.
                        onPurchased(storeTransaction)
                    }

                    override fun onRestoreCompleted(customerInfo: CustomerInfo) = onRestored()
                }
        }
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(
            title = "",
            navIcon = { EvIconButton(EvIcons.Back, onClick = onDismiss) },
            showDivider = false,
        )
        Box(Modifier.weight(1f)) {
            // RevenueCat's Paywall() reads the raw OS dark-mode setting itself, ignoring EvenlyTheme's
            // own darkTheme (see ForceNativeDarkMode's doc), so it must be told explicitly.
            ForceNativeDarkMode(darkTheme = LocalIsDarkTheme.current) { Paywall(options) }
        }
        // Measured from the bottom of the screen, not from the navigation-bar inset: the footer is meant
        // to sit tight to the edge. See the note on FOOTER_BOTTOM_MARGIN.
        Column(Modifier.fillMaxWidth().background(c.page).padding(bottom = FOOTER_BOTTOM_MARGIN)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onWantsOneTrip)
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Only need it for one trip? Get a group pass",
                    color = c.blueText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            LegalFooter(onTerms = onTerms, onPrivacy = onPrivacy)
        }
    }
}

/**
 * Terms and Privacy, last thing on the page.
 *
 * Deliberately quieter than the escape hatch above it: this is the row App Review looks for, not one the
 * buyer is being sent to. Sizing it like the offer would put three competing links under the price.
 */
@Composable
private fun LegalFooter(
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegalLink("Terms", onTerms)
        Text("·", color = c.ink3, fontSize = 12.sp)
        LegalLink("Privacy", onPrivacy)
    }
}

@Composable
private fun LegalLink(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = EvenlyTheme.colors.ink3,
        fontSize = 12.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
