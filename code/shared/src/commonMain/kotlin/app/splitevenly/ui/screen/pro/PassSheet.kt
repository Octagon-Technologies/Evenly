package app.splitevenly.ui.screen.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.pro.PassOffer
import app.splitevenly.domain.pro.PassTier
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.theme.EvenlyTheme

/** What the pass sheet is being opened onto: a free group, or one that is already covered. */
sealed interface PassSheetMode {
    /**
     * The ordinary case. The button reads "Get Pro for <group>".
     *
     * Carries the group's scan count because **this sheet has two doors** and only one of them means
     * the scans ran out: the out-of-scans gate, and the Group settings row someone taps to look. The
     * subtitle used to state "used all 5 free scans" for both, so a group that had used none was told
     * it was out — a false scarcity claim on a payment screen, which is a far worse thing to ship than
     * the vague line it replaced. Same three-way split as `ProStatusRow`, for the same reason.
     *
     * [scansLeft] is null when the count has not been fetched yet, and then the copy claims nothing.
     */
    data class Fresh(
        val scansLeft: Int?,
        val freeLimit: Int,
    ) : PassSheetMode

    /**
     * The group already holds a live pass, so this purchase **extends** it (`PRO_PASS_SPEC.md` §5.4).
     * Said before the charge, with the resulting date on the button, so "what am I actually buying"
     * needs no arithmetic.
     */
    data class Extend(
        val currentExpiresOn: String,
        val holderName: String?,
        /** The viewer bought the pass being extended. Without it the footer told Bob about "Bob's pass". */
        val isMe: Boolean,
    ) : PassSheetMode

    /**
     * This group is already Pro because **someone's** subscription covers it. No purchase is offered at
     * all: selling someone something they already have is how a money app loses trust (§5.4).
     *
     * It is not only the viewer's own subscription that has to be caught here. A pass bought for a group
     * a flatmate already covers buys nothing — and unlike a subscription it cannot be cancelled or
     * refunded on the way out, so nothing later corrects the mistake. [subscriberName] is null when the
     * payer has left the group or cannot be resolved.
     */
    data class AlreadySubscribed(
        val subscriberName: String?,
        val isMe: Boolean,
    ) : PassSheetMode
}

/** Where the sheet is in the buy-then-activate round trip. */
sealed interface PassSheetPhase {
    data object Idle : PassSheetPhase

    /** The store sheet is up, or the server is turning the pass on. Both are "do not tap again". */
    data object Working : PassSheetPhase

    /**
     * The store charged and our activation call did not land. Recoverable by design (§6.2): retrying
     * with the same transaction id is free, because activation is idempotent. Never a silent loss.
     */
    data object Charged : PassSheetPhase

    /**
     * The store charged and the server **refused** to turn it into a pass, rather than not answering.
     *
     * Separate from [Charged] because [Charged]'s copy promises that tapping again will fix it, and
     * here it cannot: the same transaction gets the same refusal on every launch. The money is not
     * lost (the RevenueCat webhook plus `pro_orphan_purchases` catches it server-side) and this state
     * exists to say that out loud and put a human in reach, instead of a button that does nothing.
     */
    data object ChargedRefused : PassSheetPhase

    data class Failed(
        val message: String?,
    ) : PassSheetPhase
}

/**
 * The group-pass sheet (`PRO_PASS_SPEC.md` §8.3) — ours, not RevenueCat's, because RevenueCat's paywall
 * editor cannot render consumables (§2.1).
 *
 * Two copy points here are load-bearing rather than decorative:
 *  - **The group name is in the headline and on the button.** Buying for the wrong group is the single
 *    mistake this design can produce, so the group is named at the moment of the tap, not just above it.
 *  - **The button carries the price, not just the verb.** "Get 1 Month Pass $3.99" needs no glance back
 *    up the screen; a bare "Get Pro" does.
 *
 * The rest of the footer is deliberately light, per owner UX review: a pricing screen with three tiers
 * already asks for one decision, and stacking renewal-terms/coverage paragraphs under it is a worse
 * trade than leaving them out. [PassSheetMode.Fresh] renders no disclaimer line at all; [Extend] still
 * states what it stacks onto, because that is information a buyer needs, not boilerplate. The
 * subscription cross-link ([onSeeSubscription]) is a full row rather than a footer link so it does not
 * read as buried fine print, since it is a real fork for anyone in more than one group.
 *
 * Prices are the store's own localized strings ([PassOffer.price]), never assembled here.
 */
@Composable
fun PassSheet(
    groupName: String,
    offers: List<PassOffer>,
    selectedPackageId: String?,
    mode: PassSheetMode,
    phase: PassSheetPhase,
    /** "Extend to 23 Aug" needs the resulting date; null in [PassSheetMode.Fresh]. */
    extendToLabel: String?,
    onSelect: (String) -> Unit,
    onBuy: () -> Unit,
    onRetryActivation: () -> Unit,
    /** Opens the feedback route for [PassSheetPhase.ChargedRefused]. Null only where there is nowhere
     *  to send someone; the button then closes the sheet rather than sitting there dead. */
    onContactSupport: (() -> Unit)?,
    onRetryOffers: () -> Unit,
    /** The mirror of the paywall's "Only need it for one trip?": whichever door someone came through,
     *  the other one is one tap away and named. Null where there is nowhere to send them without
     *  losing work (mid-bill in an editor), and then the line is absent rather than dead. */
    onSeeSubscription: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val selectedOffer = offers.firstOrNull { it.packageId == selectedPackageId }
    EvSheetScaffold(onDismiss = onDismiss) {
        if (mode is PassSheetMode.AlreadySubscribed) {
            AlreadyCoveredBody(groupName, mode, onDismiss)
            return@EvSheetScaffold
        }
        Text(
            when (mode) {
                is PassSheetMode.Extend -> "Add to $groupName's pass"
                else -> "Unlimited scans for $groupName"
            },
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            style = t.sheetTitle,
        )
        Text(
            when (mode) {
                is PassSheetMode.Extend -> {
                    "$groupName is Pro until ${mode.currentExpiresOn}. Buying now adds to the end, it does not start over."
                }

                is PassSheetMode.Fresh -> {
                    when {
                        // Not fetched yet. Sell the pass on what it does, and claim nothing about a number
                        // we do not have.
                        mode.scansLeft == null -> {
                            "Get a pass and everyone in the group can scan as many receipts as they want."
                        }

                        mode.scansLeft <= 0 -> {
                            "This group has used all ${mode.freeLimit} free scans. Get a pass and everyone in " +
                                "the group can scan as many receipts as they want."
                        }

                        else -> {
                            "${mode.scansLeft} of ${mode.freeLimit} free scans left. A pass makes them unlimited " +
                                "for everyone in the group."
                        }
                    }
                }

                else -> {
                    ""
                }
            },
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            style = t.description,
        )

        if (offers.isEmpty()) {
            // A real state, not a spinner to hide behind: with no prices there is nothing honest to put
            // on a button, so the sheet says so and leaves the free path in front of the user.
            Text(
                "Prices aren't loading right now. You can still add bills by hand for free.",
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                style = t.description,
                textAlign = TextAlign.Center,
            )
            // A transient price fetch, so the retry is real rather than a Close dressed up as one.
            EvButton(text = "Try again", onClick = onRetryOffers, variant = ButtonVariant.Secondary)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                EvButton(text = "Close", onClick = onDismiss, variant = ButtonVariant.Text)
            }
            return@EvSheetScaffold
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            offers.forEach { offer ->
                TierCard(
                    offer = offer,
                    selected = offer.packageId == selectedPackageId,
                    // "Best value" is arithmetic (cents per day), never invented popularity. It is
                    // suppressed while extending: the honest recommendation when you are already covered
                    // is the smallest one.
                    flag = if (mode is PassSheetMode.Fresh && offer == bestValue(offers)) "BEST VALUE" else null,
                    onClick = { onSelect(offer.packageId) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Box(Modifier.padding(top = 18.dp)) {
            when (phase) {
                PassSheetPhase.Charged -> {
                    EvButton(text = "Turn on Pro", onClick = onRetryActivation)
                }

                // No retry here, on purpose: the server has already given its verdict on this exact
                // transaction and will give it again. The only live action left is reaching a person.
                PassSheetPhase.ChargedRefused -> {
                    EvButton(text = "Get help with this", onClick = onContactSupport ?: onDismiss)
                }

                // The button carries the AMOUNT, not just the verb. Three tiers four times apart, a
                // noisy restaurant and no confirmation step after this: a verb with no price on it is
                // the one control here that must not make someone look back up the screen.
                else -> {
                    EvButton(
                        text =
                            when {
                                phase == PassSheetPhase.Working -> "Working…"
                                mode is PassSheetMode.Extend && extendToLabel != null -> "Extend to $extendToLabel"
                                selectedOffer != null -> "Get ${selectedOffer.title} for ${selectedOffer.price}"
                                else -> "Get Pro for $groupName"
                            },
                        onClick = onBuy,
                        // Deliberately live even with nothing selected: tapping picks the highlighted tier
                        // rather than doing nothing, so the control is never a silent dead end.
                        enabled = phase != PassSheetPhase.Working,
                    )
                }
            }
        }

        when (phase) {
            // The buyer must never be left wondering whether the money went somewhere. Says the charge
            // landed, and offers the retry that idempotency makes free.
            // "You will not be charged again" is the half of this the buyer actually needs. Without it
            // the sentence creates exactly the fear that stops them tapping, and they end up having paid
            // for nothing. It is true by construction: activation is idempotent on the transaction id.
            PassSheetPhase.Charged -> {
                Note("Payment went through. Turning on Pro didn't finish. Tap again, you won't be charged twice.", c.warning)
            }

            // Says the two things a person who has paid and not received actually needs: the money is
            // accounted for, and tapping again is not the answer. Never "try again later" here, which is
            // what the old shared state said and what kept saying it on every launch.
            PassSheetPhase.ChargedRefused -> {
                Note(
                    "Payment went through, but we couldn't turn on Pro for this group. " +
                        "Your payment is recorded and we'll sort it out. Trying again won't change it.",
                    c.warning,
                )
            }

            is PassSheetPhase.Failed -> {
                Note(phase.message ?: "That didn't go through. Nothing was charged.", c.danger)
            }

            else -> {
                Unit
            }
        }

        // Stacking needs saying (the new pass adds to an existing one rather than starting over); a
        // fresh purchase does not, so [Fresh] renders no footer line here at all.
        if (mode is PassSheetMode.Extend) {
            Text(
                when {
                    // Naming the viewer back at themselves ("Bob's pass runs to...") reads as a
                    // second person's purchase and makes the stacking question harder, not easier.
                    mode.isMe -> {
                        "Your pass runs to ${mode.currentExpiresOn}. The new one picks up from there."
                    }

                    mode.holderName != null -> {
                        "${mode.holderName}'s pass runs to ${mode.currentExpiresOn}. Yours picks up from there."
                    }

                    else -> {
                        "The current pass runs to ${mode.currentExpiresOn}. Yours picks up from there."
                    }
                },
                Modifier.fillMaxWidth().padding(top = 12.dp),
                style = t.micro,
                textAlign = TextAlign.Center,
            )
        }
        onSeeSubscription?.let { seePro ->
            ProUpsellRow(onClick = seePro, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/**
 * The mirror of the paywall's "Only need it for one trip?": whichever door someone came through, the
 * other product is one tap away and named. A tinted, bordered row rather than a footer link so it reads
 * as a real second option, not fine print.
 */
@Composable
private fun ProUpsellRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.blueTint)
            .border(1.dp, c.blueTint2, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("In more than one group?", style = t.itemTitle.copy(fontWeight = FontWeight.Bold))
            Text(
                "Unlimited scans in every group you're in",
                Modifier.padding(top = 2.dp),
                style = t.caption,
            )
        }
        Text("›", color = c.blueText, fontSize = 18.sp)
    }
}

@Composable
private fun Note(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = 8.dp),
        style = EvenlyTheme.text.caption.copy(color = color),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun AlreadyCoveredBody(
    groupName: String,
    mode: PassSheetMode.AlreadySubscribed,
    onDismiss: () -> Unit,
) {
    Text(
        "$groupName is already Pro",
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        style = EvenlyTheme.text.sheetTitle,
    )
    Text(
        when {
            mode.isMe -> {
                "Your Evenly Pro subscription covers this group, so there is nothing to buy here."
            }

            mode.subscriberName != null -> {
                "${mode.subscriberName}'s Evenly Pro subscription covers this group, so there is nothing to buy here."
            }

            else -> {
                "Someone here subscribes to Evenly Pro, so this group is covered and there is nothing to buy."
            }
        },
        Modifier.fillMaxWidth().padding(bottom = 20.dp),
        style = EvenlyTheme.text.description,
    )
    EvButton(text = "Back to $groupName", onClick = onDismiss, variant = ButtonVariant.Secondary)
}

/**
 * The 1 Week, 2 Week and 1 Month cards are the same length and height as each other by construction:
 * [flag] renders as a chip pinned to the top border, outside the card's own padding, rather than a row
 * reserved inside every card. Only the flagged card grows for it, which is deliberate (owner UX review)
 * rather than a byproduct to fix.
 */
@Composable
private fun TierCard(
    offer: PassOffer,
    selected: Boolean,
    flag: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) c.blueTint else c.page)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) c.blue else c.border, shape)
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(offer.title, style = EvenlyTheme.text.caption.copy(color = c.ink2), textAlign = TextAlign.Center)
            Text(offer.price, style = EvenlyTheme.text.sectionTitle.copy(fontWeight = FontWeight.Bold))
        }
        flag?.let {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-8).dp)
                    .clip(RoundedCornerShape(50))
                    .background(c.blue)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(it, style = EvenlyTheme.text.badge.copy(color = c.onAccent))
            }
        }
    }
}

/**
 * The cheapest per day. Real arithmetic on the store's own minor units, so it stays true in every
 * currency and after any dashboard price change, which a "most popular" badge would not. An offer whose
 * package id we do not recognise has no known duration and simply cannot win the flag.
 */
private fun bestValue(offers: List<PassOffer>): PassOffer? =
    offers
        .mapNotNull { offer -> PassTier.byPackageId(offer.packageId)?.let { offer to offer.priceMicros / it.days } }
        .minByOrNull { it.second }
        ?.first
