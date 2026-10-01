package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.remote.supabase.WebBillLinkGateway
import app.splitevenly.data.remote.supabase.WebBillLinkStatusDto
import app.splitevenly.domain.expense.WebBillLinkState
import app.splitevenly.domain.expense.billClaimUrl
import app.splitevenly.domain.repository.WebBillLinkRepository
import app.splitevenly.platform.SecureStorage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * [WebBillLinkRepository] over the `security definer` link RPCs.
 *
 * The server stores a stable token and its hash behind an RLS-closed table plus membership-checked RPCs.
 * [SecureStorage] is a local cache for quick rendering, not the source of truth, and the plaintext never
 * enters Room or the sync payload.
 *
 * Losing the cache is a normal state. `create` recovers the same token from the guarded RPC.
 */
@OptIn(ExperimentalTime::class)
class WebBillLinkRepositoryImpl(
    private val gateway: WebBillLinkGateway?,
    private val storage: SecureStorage,
    private val clock: Clock = Clock.System,
) : WebBillLinkRepository {

    private fun tokenKey(expenseId: ExpenseId) = "$TOKEN_KEY_PREFIX${expenseId.value}"

    override suspend fun status(expenseId: ExpenseId, actor: UserId): AppResult<WebBillLinkState> =
        call { gw -> gw.status(expenseId.value, actor.value).toState(expenseId) }

    override suspend fun create(expenseId: ExpenseId, actor: UserId): AppResult<WebBillLinkState> =
        call { gw ->
            val created = gw.create(expenseId.value, actor.value, clock.nowEpochMillis())
            // Store BEFORE reporting success. A crash between the two would leave a live link this device
            // can never render, which is exactly the state the token cache exists to prevent.
            storage.putString(tokenKey(expenseId), created.token)
            gw.status(expenseId.value, actor.value).toState(expenseId)
        }

    override suspend fun revoke(expenseId: ExpenseId, actor: UserId): AppResult<WebBillLinkState> =
        call { gw ->
            gw.revoke(expenseId.value, actor.value, clock.nowEpochMillis())
            // Drop the plaintext too: a revoked token is worthless, and keeping it around only invites a
            // stale QR being shown at the next dinner.
            storage.remove(tokenKey(expenseId))
            gw.status(expenseId.value, actor.value).toState(expenseId)
        }

    /** No gateway means no Supabase (offline build / tests): there is no link to speak of, and saying so
     *  is better than reporting an empty one, which would read as "nobody has ever shared this bill". */
    private suspend inline fun call(block: (WebBillLinkGateway) -> AppResult<WebBillLinkState>): AppResult<WebBillLinkState> {
        val gw = gateway ?: return AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable))
        return try {
            block(gw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, cause = e))
        }
    }

    private suspend fun WebBillLinkStatusDto.toState(expenseId: ExpenseId): AppResult<WebBillLinkState> {
        val token = if (exists && revokedAt == null) storage.getString(tokenKey(expenseId)) else null
        return AppResult.Ok(
            WebBillLinkState(
                exists = exists,
                url = token?.let { billClaimUrl(it) },
                createdAt = createdAt,
                expiresAt = expiresAt,
                revokedAt = revokedAt,
                openedAtByUser = sessions.associate { UserId(it.userId) to it.openedAt },
            ),
        )
    }

    private companion object {
        const val TOKEN_KEY_PREFIX = "web_bill_token_"
    }
}
