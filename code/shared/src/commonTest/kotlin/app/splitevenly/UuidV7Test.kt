package app.splitevenly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7Test {

    private val canonicalUuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    @Test
    fun generatedIdsAreUnique() {
        // 06-architecture-and-stack.md §5.8, D-20 — 1000 generated IDs are all unique
        val ids = (1..1000).map { generateUuidV7() }
        assertEquals(1000, ids.toSet().size)
    }

    @Test
    fun outputIsCanonicalUuidFormat() {
        // 06-architecture-and-stack.md §5.8, D-20 — output is 36 chars and canonical UUID format
        val id = generateUuidV7()
        assertEquals(36, id.length)
        assertTrue(canonicalUuidRegex.matches(id), "Not a canonical UUID: $id")
    }

    @Test
    fun idsAreTimeOrderedMonotonic() {
        // 06-architecture-and-stack.md §5.8, D-20 — UUIDv7 is time-ordered: later IDs sort >= earlier
        val ids = (1..1000).map { generateUuidV7() }
        assertEquals(ids.sorted(), ids)
    }
}
