package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.PeopleDao
import app.splitevenly.data.db.dao.PersonLinkDao
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.PersonLinkEntity
import app.splitevenly.domain.people.LinkKind
import app.splitevenly.domain.people.NameLink
import app.splitevenly.domain.people.PeopleLine
import app.splitevenly.domain.people.PeopleSummary
import app.splitevenly.domain.people.Person
import app.splitevenly.domain.people.RosterEntry
import app.splitevenly.domain.people.buildPeople
import app.splitevenly.domain.repository.PeopleRepository
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.time.Clock

class PeopleRepositoryImpl(
    private val peopleDao: PeopleDao,
    private val linkDao: PersonLinkDao,
    private val settlements: SettlementRepository,
    private val clock: Clock = Clock.System,
) : PeopleRepository {
    override fun observePeople(me: UserId): Flow<PeopleSummary> =
        combine(
            peopleDao.observeLines(me.value),
            peopleDao.observeRoster(me.value),
            linkDao.observeLive(me.value),
        ) { lines, roster, links ->
            buildPeople(
                me = me.value,
                lines =
                    lines.map {
                        PeopleLine(
                            groupId = it.groupId,
                            groupName = it.groupName,
                            groupEmoji = it.groupEmoji,
                            isQuick = it.groupKind == GroupEntity.KIND_QUICK,
                            expenseId = it.expenseId,
                            title = it.title,
                            expenseDate = it.expenseDate,
                            currency = it.currency,
                            debtorUserId = it.debtorUserId,
                            creditorUserId = it.creditorUserId,
                            // A double-recorded payment reads negative; for "who owes you" that is paid back.
                            remainingSubunits = it.remainingSubunits.coerceAtLeast(0L),
                        )
                    },
                roster = roster.map { RosterEntry(it.groupId, it.groupName, it.groupEmoji, it.userId, it.displayName, it.isPlaceholder) },
                links =
                    links.map {
                        NameLink(
                            it.id,
                            it.userId,
                            it.personId,
                            if (it.kind ==
                                PersonLinkEntity.KIND_NOT_SAME
                            ) {
                                LinkKind.NOT_SAME
                            } else {
                                LinkKind.SAME
                            },
                        )
                    },
            )
        }

    override suspend fun link(
        me: UserId,
        userIds: List<String>,
        personKey: String,
    ) = write(me, userIds, personKey, PersonLinkEntity.KIND_SAME)

    override suspend fun ruleOut(
        me: UserId,
        userIds: List<String>,
        personKey: String,
    ) = write(me, userIds, personKey, PersonLinkEntity.KIND_NOT_SAME)

    private suspend fun write(
        me: UserId,
        userIds: List<String>,
        personKey: String,
        kind: String,
    ) {
        val now = clock.nowEpochMillis()
        val existing = linkDao.live(me.value)
        val rows =
            userIds.distinct().filter { it != personKey }.mapNotNull { id ->
                // Re-linking an already linked pair is a no-op rather than a second row.
                if (existing.any { it.userId == id && it.personId == personKey && it.kind == kind }) return@mapNotNull null
                PersonLinkEntity(
                    id = newId(),
                    ownerUserId = me.value,
                    userId = id,
                    personId = personKey,
                    kind = kind,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        if (rows.isNotEmpty()) linkDao.upsertAll(rows)
    }

    override suspend fun unlink(
        me: UserId,
        userId: String,
    ) {
        val ids =
            linkDao
                .live(me.value)
                .filter { it.kind == PersonLinkEntity.KIND_SAME && (it.userId == userId || it.personId == userId) }
                .map { it.id }
        if (ids.isNotEmpty()) linkDao.softDelete(ids, clock.nowEpochMillis())
    }

    override suspend fun recordPayment(
        me: UserId,
        person: Person,
        currency: String,
        split: Map<String, Long>,
    ): AppResult<Unit> {
        for ((groupId, amount) in split) {
            if (amount <= 0) continue
            val group = person.groups.firstOrNull { it.groupId == groupId && it.currency == currency } ?: continue
            val result =
                settlements.applySettlement(
                    NewSettlement(
                        groupId = GroupId(groupId),
                        fromUserId = UserId(group.memberUserId),
                        toUserId = me,
                        paymentCurrency = currency,
                        paymentAmountSubunits = amount,
                        createdBy = me,
                    ),
                )
            if (result is AppResult.Err) return result
        }
        return AppResult.Ok(Unit)
    }
}
