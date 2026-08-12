package app.splitevenly.data.repository

import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.GroupPassDao
import app.splitevenly.data.db.dao.GroupScanUsageDao
import app.splitevenly.data.db.dao.UserSubscriptionDao
import app.splitevenly.data.db.entity.GroupPassEntity
import app.splitevenly.data.db.entity.GroupScanUsageEntity
import app.splitevenly.data.db.entity.UserSubscriptionEntity
import app.splitevenly.data.remote.supabase.ScanUsageGateway
import app.splitevenly.domain.pro.ProCandidate
import app.splitevenly.domain.pro.ProSource
import app.splitevenly.domain.pro.ProStatus
import app.splitevenly.domain.pro.proStatusOf
import app.splitevenly.domain.repository.GroupProState
import app.splitevenly.domain.repository.MySubscription
import app.splitevenly.domain.repository.ProRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Reads Pro state from local rows only (`PRO_PASS_SPEC.md` §5.2), so the badge and the meter render
 * offline and cost no round trip.
 *
 * There is no write path here at all, and there is not meant to be one: `group_passes` is server-owned
 * and pull-only, and the free-scan count is a cache of an RPC answer. Everything this class exposes is
 * for *drawing*. Enforcement lives in `extract-receipt` and reads the server's own copy, so nothing here
 * can grant a scan by being wrong or stale.
 *
 * [scanUsageGateway] follows the optional-ctor-dep pattern: production DI passes the real one when
 * Supabase is configured, and offline builds and tests pass nothing and simply keep whatever count is
 * already cached.
 */
@OptIn(ExperimentalTime::class)
class ProRepositoryImpl(
    private val groupPassDao: GroupPassDao,
    private val scanUsageDao: GroupScanUsageDao,
    private val subscriptionDao: UserSubscriptionDao,
    private val scanUsageGateway: ScanUsageGateway? = null,
    private val freeLimitFallback: Int = DEFAULT_FREE_LIMIT,
) : ProRepository {

    override fun observe(groupId: String): Flow<GroupProState> =
        combine(
            groupPassDao.observeForGroup(groupId),
            // Already joined against the ACTIVE roster in SQL, mirroring the server's own join, so a
            // subscriber leaving the group drops it back to free with nothing else to keep in step.
            subscriptionDao.observeForGroup(groupId),
            scanUsageDao.observe(groupId),
        ) { passes, subscriptions, usage ->
            val candidates = passes.map { it.toCandidate() } + subscriptions.map { it.toCandidate() }
            GroupProState(
                // Evaluated against the clock at emission rather than filtered in SQL, so a screen left
                // open across an expiry re-decides instead of holding a `true` a query settled earlier.
                status = proStatusOf(candidates, now = Clock.System.nowEpochMillis()),
                freeUsed = usage?.freeUsed,
                freeLimit = usage?.freeLimit ?: freeLimitFallback,
                // Includes revoked and expired rows: the question is "has this group ever been Pro",
                // not "is it now".
                everHadPro = candidates.isNotEmpty(),
            )
        }

    override fun observeAll(groupIds: List<String>): Flow<Map<String, GroupProState>> {
        if (groupIds.isEmpty()) return flowOf(emptyMap())
        return combine(groupIds.map { id -> observe(id).map { id to it } }) { it.toMap() }
    }

    override fun observeMySubscription(userId: String): Flow<MySubscription?> =
        subscriptionDao.observeForUser(userId).map { row ->
            // Revoked (refund or chargeback) reads as "no subscription" here too, so the Profile row can
            // never sit on PRO after the money came back.
            row?.takeIf { it.revokedAt == null }
                ?.let { MySubscription(period = it.period, expiresAt = it.expiresAt, willRenew = it.willRenew) }
        }

    override suspend fun refresh(groupId: String) {
        val gateway = scanUsageGateway ?: return
        val fresh = runCatching { gateway.usage(groupId) }.getOrNull() ?: return
        scanUsageDao.upsert(
            GroupScanUsageEntity(
                groupId = groupId,
                freeUsed = fresh.freeUsed,
                freeLimit = fresh.freeLimit,
                fetchedAt = Clock.System.nowEpochMillis(),
            ),
        )
    }

    private companion object {
        /** Only ever used to size a label before the first successful fetch, never to allow a scan. */
        const val DEFAULT_FREE_LIMIT = 5
    }
}

private fun GroupPassEntity.toCandidate() = ProCandidate(
    expiresAt = expiresAt,
    purchasedBy = purchasedBy,
    tier = tier,
    source = ProSource.Pass,
    revokedAt = revokedAt,
)

/** `period` doubles as the tier label: "Pro because Sam subscribes" needs monthly-vs-annual nowhere on
 *  screen, but the badge and analytics both want one field that says what was bought. */
private fun UserSubscriptionEntity.toCandidate() = ProCandidate(
    expiresAt = expiresAt,
    purchasedBy = userId,
    tier = period,
    source = ProSource.Subscription,
    revokedAt = revokedAt,
)
