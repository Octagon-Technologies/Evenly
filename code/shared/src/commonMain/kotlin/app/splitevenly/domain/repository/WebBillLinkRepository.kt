package app.splitevenly.domain.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.expense.WebBillLinkState

/**
 * The payer's control over a bill's web claim link (WEB_CLAIM_SPEC.md §3.9.3).
 *
 * **Deliberately not local-first, unlike every other repository here.** A link is an authorisation that
 * lives on the server: minting one offline would hand out a QR that nothing can validate, and revoking
 * one offline would tell the payer a link is dead while guests keep writing to it. Both are worse than a
 * spinner, so every call here is a live round trip and reports its failure (§6's "no optimistic writes"
 * applied to the app side). SecureStorage caches the token; the server can recover it for another group
 * member through the membership-checked RPC.
 */
interface WebBillLinkRepository {

    /** The current link plus per-person web-session activity. Poll this while the payer is watching. */
    suspend fun status(expenseId: ExpenseId, actor: UserId): AppResult<WebBillLinkState>

    /** Return the existing permanent token, or mint one if this bill has no active link. */
    suspend fun create(expenseId: ExpenseId, actor: UserId): AppResult<WebBillLinkState>

    /** Kill the link now (E28). Guests see "Link no longer works", with no bill data. */
    suspend fun revoke(expenseId: ExpenseId, actor: UserId): AppResult<WebBillLinkState>
}
