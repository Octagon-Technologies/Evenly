package app.splitevenly.data.repository

import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.GroupPassDao
import app.splitevenly.data.db.dao.GroupScanUsageDao
import app.splitevenly.data.db.entity.GroupPassEntity
import app.splitevenly.data.db.entity.GroupScanUsageEntity
import app.splitevenly.data.remote.supabase.ScanUsageGateway
import app.splitevenly.domain.pro.ProPass
import app.splitevenly.domain.pro.ProStatus
import app.splitevenly.domain.pro.proStatusOf
import app.splitevenly.domain.repository.GroupProState
import app.splitevenly.domain.repository.ProRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val scanUsageGateway: ScanUsageGateway? = null,
    private val freeLimitFallback: Int = DEFAULT_FREE_LIMIT,
) : ProRepository {

    override fun observe(groupId: String): Flow<GroupProState> =
        combine(
            groupPassDao.observeForGroup(groupId),
            scanUsageDao.observe(groupId),
        ) { passes, usage ->
            GroupProState(
                // Evaluated against the clock at emission rather than filtered in SQL, so a screen left
                // open across an expiry re-decides instead of holding a `true` a query settled earlier.
                status = proStatusOf(passes.map { it.toProPass() }, now = Clock.System.nowEpochMillis()),
                freeUsed = usage?.freeUsed,
                freeLimit = usage?.freeLimit ?: freeLimitFallback,
                // Includes revoked and expired rows: the question is "has this group ever been Pro",
                // not "is it now".
                everHadPass = passes.isNotEmpty(),
            )
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

private fun GroupPassEntity.toProPass() = ProPass(
    expiresAt = expiresAt,
    purchasedBy = purchasedBy,
    tier = tier,
    revokedAt = revokedAt,
)
