package app.splitevenly.domain.people

/**
 * The People tab's model: who owes the viewer, across every group, with names the viewer has linked
 * treated as one person (`docs/features/people-tab.md`).
 *
 * Pure. The repository feeds it rows; nothing here knows about Room.
 */

/** One share line involving the viewer. [remainingSubunits] can be 0 (paid back) but is never negative here. */
data class PeopleLine(
    val groupId: String,
    val groupName: String,
    val groupEmoji: String,
    val isQuick: Boolean,
    val expenseId: String,
    val title: String,
    /** ISO calendar day, "2026-11-20". Sorts correctly as text. */
    val expenseDate: String,
    val currency: String,
    val debtorUserId: String,
    val creditorUserId: String,
    val remainingSubunits: Long,
)

/** One ACTIVE member of one of the viewer's groups. */
data class RosterEntry(
    val groupId: String,
    val groupName: String,
    val groupEmoji: String,
    val userId: String,
    val displayName: String?,
    val isPlaceholder: Boolean,
)

enum class LinkKind { SAME, NOT_SAME }

data class NameLink(
    val id: String,
    val userId: String,
    val personId: String,
    val kind: LinkKind,
)

/** What one person owes the viewer in one group, in one currency: that group's own balance between them. */
data class PersonGroup(
    val groupId: String,
    /** Which of the person's names this group knows them by: the debtor on a payment recorded here. */
    val memberUserId: String,
    val groupName: String,
    val groupEmoji: String,
    val isQuick: Boolean,
    val currency: String,
    val amountSubunits: Long,
    /** The person's name in this group when it differs from [Person.name] ("Pookie"); null otherwise. */
    val alias: String?,
    /** Date of the oldest unpaid expense behind [amountSubunits]. Drives "oldest first". */
    val oldestDate: String,
)

/** One expense behind a person's total. */
data class PersonExpense(
    val expenseId: String,
    val groupId: String,
    val groupName: String,
    val isQuick: Boolean,
    val title: String,
    val expenseDate: String,
    val currency: String,
    val amountSubunits: Long,
    val alias: String?,
)

/** In a group where they owe you, what you owe them there, which that group's balance already takes off. */
data class PersonOffset(
    val groupId: String,
    val groupName: String,
    val currency: String,
    val amountSubunits: Long,
)

/** A name in one group, for the "Same name elsewhere" card and the Link other names pool. */
data class LinkCandidate(
    val userId: String,
    val name: String,
    val groupId: String,
    val groupName: String,
    val groupEmoji: String,
    /** What this name owes the viewer, per currency. */
    val owedByCurrency: Map<String, Long>,
)

data class Person(
    /** Stable id for navigation: the account id when there is one, otherwise the anchor name's id. */
    val key: String,
    val name: String,
    val hasAccount: Boolean,
    /** Every user id that is this person in the viewer's groups. */
    val memberIds: Set<String>,
    /** Every group any of [memberIds] is in, owing or not. */
    val groupIds: Set<String>,
    /** Linked names other than the one shown, with where each lives. */
    val otherNames: List<LinkCandidate>,
    val groups: List<PersonGroup>,
    val expenses: List<PersonExpense>,
    val offsets: List<PersonOffset>,
    /** Exact same-name matches waiting for a Link or No. */
    val sameNameMatches: List<LinkCandidate>,
) {
    /** Total owed per currency. Never summed across currencies. */
    val totals: Map<String, Long> =
        groups.groupBy { it.currency }.mapValues { (_, g) -> g.sumOf { it.amountSubunits } }

    val owesYou: Boolean get() = groups.isNotEmpty()
}

data class PeopleSummary(
    val owing: List<Person>,
    /** People who owed the viewer before and owe nothing now. */
    val settled: List<Person>,
    /** Everyone in the viewer's groups, owing or not: the "People you know" source when adding someone. */
    val everyone: List<Person> = owing + settled,
) {
    val totals: Map<String, Long> =
        owing.flatMap { it.groups }.groupBy { it.currency }.mapValues { (_, g) -> g.sumOf { it.amountSubunits } }
}

/** Case and spacing never make two names different. */
fun normalizeName(name: String): String =
    name
        .trim()
        .lowercase()
        .split(Regex("\\s+"))
        .joinToString(" ")

private class UnionFind {
    private val parent = mutableMapOf<String, String>()

    fun find(x: String): String {
        var r = x
        while (true) {
            val p = parent[r] ?: return r
            if (p == r) return r
            r = p
        }
    }

    fun union(
        a: String,
        b: String,
    ) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }
}

/**
 * Builds the People tab. [me] is the viewer.
 *
 * A person's amount in a group is the group's own net between them and the viewer in that currency, so
 * it always equals what that group's Balances tab shows. Groups where the viewer owes them, or is even,
 * are left out entirely; nothing is netted across groups.
 */
fun buildPeople(
    me: String,
    lines: List<PeopleLine>,
    roster: List<RosterEntry>,
    links: List<NameLink>,
): PeopleSummary {
    val nameOf = mutableMapOf<String, String>()
    val placeholder = mutableMapOf<String, Boolean>()
    val groupsOf = mutableMapOf<String, MutableSet<String>>()
    val groupMeta = mutableMapOf<String, Triple<String, String, Boolean>>()
    for (r in roster) {
        if (r.userId == me) continue
        r.displayName?.let { nameOf[r.userId] = it }
        placeholder[r.userId] = r.isPlaceholder
        groupsOf.getOrPut(r.userId) { mutableSetOf() } += r.groupId
        groupMeta.getOrPut(r.groupId) { Triple(r.groupName, r.groupEmoji, false) }
    }
    for (l in lines) {
        groupMeta[l.groupId] = Triple(l.groupName, l.groupEmoji, l.isQuick)
        for (id in listOf(l.debtorUserId, l.creditorUserId)) {
            if (id != me) groupsOf.getOrPut(id) { mutableSetOf() } += l.groupId
        }
    }
    val everyone = groupsOf.keys
    val hasAccount = { id: String -> placeholder[id] == false }

    val uf = UnionFind()
    val members = mutableMapOf<String, MutableSet<String>>().apply { everyone.forEach { put(it, mutableSetOf(it)) } }
    val sameLinks = links.filter { it.kind == LinkKind.SAME && it.userId in everyone && it.personId in everyone }
    for (link in sameLinks.sortedBy { it.id }) {
        val ra = uf.find(link.personId)
        val rb = uf.find(link.userId)
        if (ra == rb) continue
        val a = members.getValue(ra)
        val b = members.getValue(rb)
        // Two accounts are two people, and nobody is in one group twice. A link that breaks either rule
        // (a placeholder claimed since, someone added to a group later) is ignored rather than trusted.
        if (a.any(hasAccount) && b.any(hasAccount)) continue
        if (sharesGroup(a, b, groupsOf)) continue
        uf.union(ra, rb)
        val root = uf.find(ra)
        val merged = (a + b).toMutableSet()
        members.remove(ra)
        members.remove(rb)
        members[root] = merged
    }
    val notSame =
        links
            .filter { it.kind == LinkKind.NOT_SAME }
            .flatMap {
                listOf(
                    it.userId to it.personId,
                    it.personId to it.userId,
                )
            }.toSet()
    val anchorVotes = sameLinks.groupingBy { it.personId }.eachCount()

    // Per-name, per-group, per-currency directions.
    data class Key(
        val groupId: String,
        val currency: String,
    )
    val owedToMe = mutableMapOf<String, MutableMap<Key, Long>>()
    val iOwe = mutableMapOf<String, MutableMap<Key, Long>>()
    val everOwedMe = mutableSetOf<String>()
    for (l in lines) {
        if (l.creditorUserId == me && l.debtorUserId != me) {
            everOwedMe += l.debtorUserId
            if (l.remainingSubunits > 0) {
                val m = owedToMe.getOrPut(l.debtorUserId) { mutableMapOf() }
                m[Key(l.groupId, l.currency)] = (m[Key(l.groupId, l.currency)] ?: 0L) + l.remainingSubunits
            }
        } else if (l.debtorUserId == me && l.creditorUserId != me && l.remainingSubunits > 0) {
            val m = iOwe.getOrPut(l.creditorUserId) { mutableMapOf() }
            m[Key(l.groupId, l.currency)] = (m[Key(l.groupId, l.currency)] ?: 0L) + l.remainingSubunits
        }
    }

    fun owedByCurrency(id: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val forward = owedToMe[id].orEmpty()
        val reverse = iOwe[id].orEmpty()
        for ((k, v) in forward) {
            val net = v - (reverse[k] ?: 0L)
            if (net > 0) out[k.currency] = (out[k.currency] ?: 0L) + net
        }
        return out
    }

    fun candidate(
        id: String,
        groupId: String,
    ): LinkCandidate {
        val meta = groupMeta[groupId]
        return LinkCandidate(
            userId = id,
            name = nameOf[id] ?: "Someone",
            groupId = groupId,
            groupName = meta?.first.orEmpty(),
            groupEmoji = meta?.second ?: "💸",
            owedByCurrency = owedByCurrency(id),
        )
    }

    val components = members.values.toList()

    fun representative(ids: Set<String>): String =
        ids.firstOrNull(hasAccount)
            ?: ids.maxWithOrNull(compareBy<String>({ anchorVotes[it] ?: 0 }, { -(groupsOf[it]?.size ?: 0) }).thenByDescending { it })!!

    fun buildPerson(ids: Set<String>): Person {
        val key = representative(ids)
        val name = nameOf[key] ?: ids.firstNotNullOfOrNull { nameOf[it] } ?: "Someone"
        val norm = normalizeName(name)
        val groups = mutableListOf<PersonGroup>()
        val offsets = mutableListOf<PersonOffset>()
        val owedGroupCurrencies = mutableSetOf<Key>()
        val keys = ids.flatMap { owedToMe[it].orEmpty().keys }.toSet()
        for (k in keys) {
            val forward = ids.sumOf { owedToMe[it]?.get(k) ?: 0L }
            val reverse = ids.sumOf { iOwe[it]?.get(k) ?: 0L }
            val net = forward - reverse
            if (net <= 0) continue
            owedGroupCurrencies += k
            val meta = groupMeta[k.groupId]
            val idHere = ids.firstOrNull { k.groupId in groupsOf[it].orEmpty() }
            val hereName = idHere?.let { nameOf[it] }
            val oldest =
                lines
                    .filter {
                        it.groupId == k.groupId && it.currency == k.currency && it.creditorUserId == me &&
                            it.debtorUserId in ids && it.remainingSubunits > 0
                    }.minOf { it.expenseDate }
            groups +=
                PersonGroup(
                    groupId = k.groupId,
                    memberUserId = idHere ?: key,
                    groupName = meta?.first.orEmpty(),
                    groupEmoji = meta?.second ?: "💸",
                    isQuick = meta?.third ?: false,
                    currency = k.currency,
                    amountSubunits = net,
                    alias = hereName?.takeIf { normalizeName(it) != norm },
                    oldestDate = oldest,
                )
            if (reverse > 0) offsets += PersonOffset(k.groupId, meta?.first.orEmpty(), k.currency, reverse)
        }
        val expenses =
            lines
                .filter {
                    it.creditorUserId == me && it.debtorUserId in ids && it.remainingSubunits > 0 &&
                        Key(it.groupId, it.currency) in owedGroupCurrencies
                }.map {
                    PersonExpense(
                        expenseId = it.expenseId,
                        groupId = it.groupId,
                        groupName = it.groupName,
                        isQuick = it.isQuick,
                        title = it.title,
                        expenseDate = it.expenseDate,
                        currency = it.currency,
                        amountSubunits = it.remainingSubunits,
                        alias = nameOf[it.debtorUserId]?.takeIf { n -> normalizeName(n) != norm },
                    )
                }.sortedWith(compareByDescending<PersonExpense> { it.expenseDate }.thenBy { it.expenseId })

        val otherNames =
            ids
                .filter { it != key }
                .flatMap { id -> groupsOf[id].orEmpty().map { candidate(id, it) } }
                .sortedBy { it.groupName }

        val namesHere = ids.mapNotNull { nameOf[it] }.map(::normalizeName).toSet()
        val matches =
            components
                .filter { other -> other !== ids && other.none { it in ids } }
                .filter { other -> other.any { nameOf[it]?.let(::normalizeName) in namesHere } }
                .filter { other -> !(other.any(hasAccount) && ids.any(hasAccount)) }
                .filter { other -> !sharesGroup(ids, other, groupsOf) }
                .filter { other -> other.none { o -> ids.any { (o to it) in notSame } } }
                .flatMap { other -> other.flatMap { id -> groupsOf[id].orEmpty().map { candidate(id, it) } } }
                .filter { it.owedByCurrency.isNotEmpty() }
                .sortedBy { it.groupName }

        return Person(
            key = key,
            name = name,
            hasAccount = ids.any(hasAccount),
            memberIds = ids,
            groupIds = ids.flatMap { groupsOf[it].orEmpty() }.toSet(),
            otherNames = otherNames,
            groups = groups.sortedWith(compareByDescending<PersonGroup> { it.amountSubunits }.thenBy { it.groupName }),
            expenses = expenses,
            offsets = offsets,
            sameNameMatches = matches,
        )
    }

    val people = components.map(::buildPerson)
    val owing =
        people.filter { it.owesYou }.sortedWith(
            compareByDescending<Person> { it.totals.values.maxOrNull() ?: 0L }.thenBy { it.name },
        )
    val settled = people.filter { !it.owesYou && it.memberIds.any { id -> id in everOwedMe } }.sortedBy { it.name }
    return PeopleSummary(owing, settled, people.sortedBy { it.name.lowercase() })
}

private fun sharesGroup(
    a: Set<String>,
    b: Set<String>,
    groupsOf: Map<String, Set<String>>,
): Boolean {
    val ga = a.flatMap { groupsOf[it].orEmpty() }.toSet()
    return b.any { id -> groupsOf[id].orEmpty().any { it in ga } }
}

/**
 * Names that can be linked to [person] ("Link other names"): typed by hand, not linked to anyone yet,
 * owing the viewer something, and in no group [person] is already in (nobody is in a group twice).
 */
fun linkPool(
    person: Person,
    summary: PeopleSummary,
): List<LinkCandidate> =
    summary.owing
        .asSequence()
        .filter { it.key != person.key && !it.hasAccount && it.memberIds.size == 1 }
        .filter { other -> other.groupIds.none { it in person.groupIds } }
        .flatMap { other ->
            other.groups.distinctBy { it.groupId }.map { g ->
                LinkCandidate(
                    userId = other.key,
                    name = other.name,
                    groupId = g.groupId,
                    groupName = g.groupName,
                    groupEmoji = g.groupEmoji,
                    owedByCurrency = other.totals,
                )
            }
        }.sortedWith(compareBy<LinkCandidate> { it.groupName }.thenBy { it.name })
        .toList()
