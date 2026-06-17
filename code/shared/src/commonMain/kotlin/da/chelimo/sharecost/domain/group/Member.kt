package da.chelimo.sharecost.domain.group

import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.settlement.PaymentApp

/**
 * A group member joined to its user for display (02 §3.5 + §3.2). [displayName] is null only when
 * the user row has not synced yet (the join is tolerant so members never silently vanish from a
 * roster). Placeholders ([isPlaceholder]) participate in splits but never sign in.
 *
 * [paymentHandles] is the member's own per-payee handles (03 §5.1) — only the apps they've set. The
 * settle screen reads the *payee's* map to deep-link into the right app; it's empty for placeholders.
 */
data class Member(
    val userId: UserId,
    val displayName: String?,
    val isPlaceholder: Boolean,
    val isAdmin: Boolean,
    val joinedAt: Long,
    val paymentHandles: Map<PaymentApp, String> = emptyMap(),
)
