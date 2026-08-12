package app.splitevenly.ui.screen.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** One group the viewer's subscription covers, for the "what am I actually paying for" list. */
data class CoveredGroupUi(val emoji: String, val name: String)

/** The viewer's own live subscription, already formatted. Null when they hold none. */
data class MySubscriptionUi(
    /** "Yearly" | "Monthly". */
    val periodLabel: String,
    /** "renews 11 Aug 2027" or "ends 11 Aug 2027" — [app.splitevenly.data.db.entity.UserSubscriptionEntity.willRenew]
     *  decides the verb and nothing else; it never decides entitlement. */
    val renewalLine: String,
)

/**
 * The subscribed state of the Evenly Pro screen (`PRO_PASS_SPEC.md` §8.6, mock frame 13).
 *
 * **"Manage or cancel" opens the store's own screen, never an in-app imitation.** Cancelling has to be
 * exactly as easy as buying; a subscriber who cannot find the cancel button leaves the one-star review
 * this whole design exists to avoid.
 *
 * Listing the groups it covers is what makes a personal subscription legible in a group app, and is the
 * honest answer to "what am I paying for?".
 */
@Composable
fun ProSubscribedScreen(
    subscription: MySubscriptionUi,
    coveredGroups: List<CoveredGroupUi>,
    restoring: Boolean,
    restoreNote: String?,
    onManage: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "Evenly Pro", navIcon = { EvIconButton(EvIcons.Back, onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EvCard(padded = true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.blueTint),
                        contentAlignment = Alignment.Center,
                    ) { EvIcon(EvIcons.Star, size = 17.dp, tint = c.blueText) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${subscription.periodLabel}, ${subscription.renewalLine}",
                            color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text("Unlimited scans in every group you're in", color = c.ink2, fontSize = 12.5.sp)
                    }
                }
            }

            if (coveredGroups.isNotEmpty()) {
                Column {
                    EvSectionLabel("Covers these groups")
                    EvCard {
                        coveredGroups.forEach { g ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(g.emoji, fontSize = 16.sp)
                                Text(g.name, color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            EvButton(text = "Manage or cancel", onClick = onManage, variant = ButtonVariant.Secondary)
            // Says it leaves the app, because it does. Being thrown into a system screen you did not
            // expect is how someone cancels twice, or believes the cancel did not take.
            Text(
                if (isIOS()) "Opens your App Store subscriptions." else "Opens your Google Play subscriptions.",
                Modifier.fillMaxWidth(), color = c.ink3, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                EvButton(
                    text = if (restoring) "Restoring…" else "Restore purchases",
                    onClick = onRestore,
                    variant = ButtonVariant.Text,
                    enabled = !restoring,
                )
            }
            restoreNote?.let {
                Text(it, Modifier.fillMaxWidth(), color = c.ink2, fontSize = 12.5.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * What stands in for the RevenueCat paywall when RevenueCat is unconfigured or its offering cannot be
 * read (`PRO_PASS_SPEC.md` §9: unconfigured must be inert).
 *
 * It is not a "coming soon" wall. The one thing a person on this screen actually needs is that nothing
 * they use every day is behind it, so that is what it says, and the free path stays a tap away.
 */
@Composable
fun ProUnavailableScreen(onBack: () -> Unit) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "Evenly Pro", navIcon = { EvIconButton(EvIcons.Back, onBack) })
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(99.dp)).background(c.surface),
                contentAlignment = Alignment.Center,
            ) { EvIcon(EvIcons.Star, size = 24.dp, tint = c.ink2) }
            Text(
                "Pro isn't available yet",
                color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(
                "Everything you use to split a bill is free and stays free. Pro only adds unlimited receipt scanning and spreadsheet export.",
                color = c.ink2, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            EvButton(text = "Back", onClick = onBack, variant = ButtonVariant.Secondary, fillMaxWidth = false)
        }
    }
}
