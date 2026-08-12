package app.splitevenly.domain.pro

import app.splitevenly.core.id.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One buyable group pass, with the store's own localized price string (`PRO_PASS_SPEC.md` §2.3).
 *
 * **[price] is never built in Kotlin.** App Review rejects a hardcoded price, and a Kenyan or European
 * buyer must see their own currency, so this is always the `StoreProduct`'s formatted price verbatim.
 */
data class PassOffer(
    /** The RevenueCat package identifier (`pass_week_1`…), which is what a purchase is started from. */
    val packageId: String,
    val productId: String,
    /** "1 week" | "2 weeks" | "1 month", from the product's store title. */
    val title: String,
    /** Localized and store-formatted: "$0.99", "KSh 129", "1,99 €". */
    val price: String,
    /**
     * The same price as a number, for arithmetic the label cannot do — the sheet's "best value" flag is
     * cents per day, not invented popularity, and that has to keep working in every currency and after
     * any dashboard price change. Never rendered.
     */
    val priceMicros: Long,
    /** ISO 4217, from the store. Lets the funnel compare a $0.99 pass with a KSh 129 one instead of
     *  silently averaging two different units into one meaningless number. */
    val currency: String,
)

/** What a pass purchase produced, or why it did not. */
sealed interface PassPurchaseOutcome {
    /**
     * The store charged. [storeTxnId] is what `activate-pass` deduplicates on, so a client that dies
     * before activating can retry with the same id and get the same one pass.
     */
    data class Bought(val storeTxnId: String, val productId: String) : PassPurchaseOutcome

    /** The buyer backed out. Not an error, and nothing is shown for it. */
    data object Cancelled : PassPurchaseOutcome

    /** The store or RevenueCat refused. [message] is theirs, not ours. */
    data class Failed(val message: String?) : PassPurchaseOutcome
}

/**
 * The app's whole surface onto RevenueCat (`PRO_PASS_SPEC.md` §9).
 *
 * **Everything here is inert when RevenueCat is unconfigured** — [isAvailable] is false, no call
 * throws, and nothing renders. Same contract as the app being fully usable with Supabase unconfigured,
 * and it is what lets the entitlement work ship before the owner's console work does.
 *
 * Note what is *not* here: any notion of "am I Pro". RevenueCat says a purchase happened; our database
 * decides who is Pro (§5), because the other five people in the group bought nothing and still need it.
 * `CustomerInfo` never reaches a caller of this interface.
 */
interface ProBilling {

    /** False until [app.splitevenly.data.remote.revenuecat.ProConfig] carries real keys. */
    val isAvailable: Boolean

    /**
     * Tie the RevenueCat subscriber to whoever is signed in, for as long as [userId] emits.
     *
     * Called once from the auth session, next to the sync and push binds, rather than from each of the
     * five sign-in paths. Signing out must reach `logOut()` or the next person to sign in on this phone
     * inherits the previous one's subscription.
     */
    fun bind(scope: CoroutineScope, userId: StateFlow<UserId?>)

    /** The three passes, in the dashboard's order, with localized prices. Empty if unconfigured. */
    suspend fun passOffers(): List<PassOffer>

    /**
     * The cheapest subscription price, localized and store-formatted ("$2.99 a month"), for the Profile
     * row. Null when unconfigured or unreachable, in which case the row says what Pro *does* rather than
     * inventing a number.
     */
    suspend fun subscriptionPriceLabel(): String?

    /**
     * Buy [packageId] for [groupId].
     *
     * The group id is written as a RevenueCat subscriber attribute immediately before the purchase,
     * because the store's data model has nowhere to carry it and the webhook backstop has no other way
     * back to the group (§6.3).
     */
    suspend fun buyPass(packageId: String, groupId: String): PassPurchaseOutcome

    /**
     * Re-ask the store what this account owns. App Review requires the control, and a reinstalling
     * subscriber needs it. Returns false when there was nothing to restore or the call failed.
     */
    suspend fun restore(): Boolean
}
