package app.splitevenly.domain.expense

import app.splitevenly.allocate
import app.splitevenly.core.id.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The Kotlin half of the money CI gate (`WEB_CLAIM_SPEC.md` §7).
 *
 * `test-vectors/bill-split.json` is run against BOTH the Kotlin engine (here) and the hand-ported TS
 * engine (`web/test/vectors.test.ts`). Two implementations of the most dangerous code in the app can
 * drift apart silently; these vectors are the thing that makes the drift a red build instead of a
 * wrong dinner bill.
 *
 * **Kotlin is the authority.** To add a case: write its inputs into the JSON with `"expect": {}`,
 * then record the expectations from this engine and review the recorded numbers in the diff:
 *
 * ```bash
 * cd code && EVENLY_VECTORS_RECORD=true ./gradlew :shared:testAndroidHostTest
 * ```
 *
 * It is an ENV VAR, not `-D`: `build.gradle.kts` forwards `EVENLY_VECTORS_RECORD` into the test JVM's
 * `evenly.vectors.record` property, while a `-D` on the Gradle command line lands on the daemon, where
 * this test never sees it — the run then fails with "empty `expect`", which reads like a bad case
 * rather than a flag that never arrived.
 *
 * Record mode rewrites the file in place and is never on in CI. Never record to make a red build
 * green — if Kotlin's numbers changed, that is either a deliberate rule change (port it to TS in the
 * same commit) or a regression.
 *
 * JVM-only on purpose: the engine is pure `commonMain` code with no `expect`/`actual` in it, so
 * running the vectors once on the JVM proves what the gate is for. Native coverage of the same
 * engine comes from `commonTest`, which runs on both.
 */
class BillSplitVectorsTest {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    private val recording = System.getProperty("evenly.vectors.record") == "true"

    @Test
    fun vectorsMatchTheKotlinEngine() {
        val file = vectorsFile()
        val root = Json.parseToJsonElement(file.readText()).jsonObject

        val allocateCases =
            root.cases("allocate").map { case ->
                val actual = runAllocate(case)
                checkOrRecord(case, "allocate", actual)
            }
        val itemizedCases =
            root.cases("itemizedShares").map { case ->
                val actual = runItemizedShares(case)
                checkOrRecord(case, "itemizedShares", actual)
            }
        val splitCases =
            root.cases("splitBill").map { case ->
                val actual = runSplitBill(case)
                if (!recording) assertMoneyInvariants(case, actual.jsonObject)
                checkOrRecord(case, "splitBill", actual)
            }

        if (recording) {
            val rewritten =
                JsonObject(
                    root.toMutableMap().apply {
                        put("allocate", JsonArray(allocateCases))
                        put("itemizedShares", JsonArray(itemizedCases))
                        put("splitBill", JsonArray(splitCases))
                    },
                )
            file.writeText(json.encodeToString(JsonObject.serializer(), rewritten) + "\n")
            fail(
                "Recorded ${allocateCases.size + itemizedCases.size + splitCases.size} expectations into " +
                    "${file.path}. Review the diff, then re-run WITHOUT -Devenly.vectors.record to verify.",
            )
        }
    }

    // ---- the three engines under test -------------------------------------------------------

    /** [allocate] returns a bare map, so its whole result is the expectation. */
    private fun runAllocate(case: JsonObject): JsonElement {
        val weights =
            case.getValue("weights").jsonArray.map {
                UserId(it.jsonObject.str("userId")) to
                    it.jsonObject
                        .getValue("weight")
                        .jsonPrimitive.long
            }
        return allocate(case.getValue("totalSubunits").jsonPrimitive.long, weights).toJson()
    }

    private fun runItemizedShares(case: JsonObject): JsonElement {
        val subtotals =
            case.getValue("subtotals").jsonArray.map {
                UserId(it.jsonObject.str("userId")) to
                    it.jsonObject
                        .getValue("subtotal")
                        .jsonPrimitive.long
            }
        return itemizedShares(
            subtotals = subtotals,
            taxSubunits = case.long("taxSubunits"),
            tipSubunits = case.long("tipSubunits"),
            tipSplitMode = TipSplitMode.valueOf(case["tipSplitMode"]?.jsonPrimitive?.content ?: "EVEN"),
        ).toJson()
    }

    /** [splitBill] returns four things worth pinning: the tab, its parts, the per-line split, and the statuses. */
    private fun runSplitBill(case: JsonObject): JsonElement {
        val extras = case["extras"]?.jsonObject ?: JsonObject(emptyMap())
        val result =
            splitBill(
                items =
                    case.rows("items").map {
                        BillItem(it.str("itemId"), it.long("lineTotalSubunits"), it.int("quantity"))
                    },
                individualClaims =
                    case.rows("individualClaims").map {
                        IndividualClaim(it.str("itemId"), UserId(it.str("userId")), it.int("units"))
                    },
                sharedMembers =
                    case.rows("sharedMembers").map {
                        SharedMember(it.str("itemId"), UserId(it.str("userId")))
                    },
                extras =
                    BillExtras(
                        taxSubunits = extras.long("taxSubunits"),
                        gratuitySubunits = extras.long("gratuitySubunits"),
                        tipSubunits = extras.long("tipSubunits"),
                        tipSplitMode = TipSplitMode.valueOf(extras["tipSplitMode"]?.jsonPrimitive?.content ?: "EVEN"),
                        discountSubunits = extras.long("discountSubunits"),
                        otherChargesSubunits = extras.long("otherChargesSubunits"),
                    ),
                participants =
                    (case["participants"]?.jsonArray ?: JsonArray(emptyList()))
                        .map { UserId(it.jsonPrimitive.content) },
                sharedPortions =
                    case.rows("sharedPortions").map {
                        SharedPortion(
                            itemId = it.str("itemId"),
                            portionId = it.str("portionId"),
                            quantity = it.int("quantity"),
                            members = it.getValue("members").jsonArray.map { m -> UserId(m.jsonPrimitive.content) },
                        )
                    },
            )
        return buildJsonObject {
            put("owedByUser", result.owedByUser.toJson())
            put(
                "breakdownByUser",
                JsonObject(
                    result.breakdownByUser.entries.associate { (id, b) ->
                        id.value to
                            buildJsonObject {
                                put("itemsSubunits", JsonPrimitive(b.itemsSubunits))
                                put("taxSubunits", JsonPrimitive(b.taxSubunits))
                                put("tipSubunits", JsonPrimitive(b.tipSubunits))
                                put("discountSubunits", JsonPrimitive(b.discountSubunits))
                            }
                    },
                ),
            )
            put(
                "perItemByUser",
                JsonObject(result.perItemByUser.entries.associate { (itemId, byUser) -> itemId to byUser.toJson() }),
            )
            put(
                "items",
                buildJsonArray {
                    result.items.forEach { r ->
                        add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(r.itemId))
                                put("quantity", JsonPrimitive(r.quantity))
                                put("individualUnits", JsonPrimitive(r.individualUnits))
                                put("hasSharers", JsonPrimitive(r.hasSharers))
                                put("status", JsonPrimitive(r.status.name))
                            },
                        )
                    }
                },
            )
        }
    }

    // ---- the money invariants ------------------------------------------------------------------

    /**
     * Two rules that hold for EVERY vector, checked in addition to its recorded numbers. A recorded
     * expectation only pins the case someone thought to write down; an invariant catches the variants
     * nobody enumerated, which is the whole reason findings #20 and #21 survived their own unit tests.
     *
     *  1. **A fully-claimed bill's shares sum to its total.** #20 dropped an EVEN tip slice belonging
     *     to a participant with no items, so a finished bill's shares summed BELOW `amount_subunits`
     *     and the difference was simply never owed by anyone.
     *  2. **A bill's total is positive.** #21 let an oversized discount produce a zero or negative
     *     expense, which the `> 0` outstanding filters then hid entirely.
     *
     * Both are gated on the bill being fully claimed (every line RESOLVED): mid-claim, the unclaimed
     * remainder deliberately rides a phantom bucket, and over-claimed lines deliberately over-sum.
     */
    private fun assertMoneyInvariants(
        case: JsonObject,
        actual: JsonObject,
    ) {
        val name = case.str("name")
        val extras = case["extras"]?.jsonObject ?: JsonObject(emptyMap())
        val lineTotals = case.rows("items").sumOf { it.long("lineTotalSubunits") }
        val total =
            lineTotals + extras.long("taxSubunits") + extras.long("gratuitySubunits") +
                extras.long("otherChargesSubunits") + extras.long("tipSubunits") - extras.long("discountSubunits")

        val statuses = actual.rows("items").map { it.str("status") }
        val fullyClaimed = statuses.isNotEmpty() && statuses.all { it == "RESOLVED" }
        if (!fullyClaimed) return

        // An all-free bill (every line 0, no extras) is a legitimate 0 and pins the divide-by-zero path.
        if (total == 0L && lineTotals == 0L) return

        if (total <= 0L) {
            fail("splitBill/$name → a bill's total must be positive, was $total (#21)")
        }
        val owed =
            actual
                .getValue("owedByUser")
                .jsonObject.values
                .sumOf { it.jsonPrimitive.long }
        assertEquals(total, owed, "splitBill/$name → a fully-claimed bill's shares must sum to its total (#20)")
    }

    // ---- verify / record ---------------------------------------------------------------------

    /**
     * Verify mode asserts every expectation the case declares and ignores the rest, so a
     * hand-authored case can pin only the numbers its author could justify. Record mode replaces the
     * whole `expect` block with what this engine produced.
     */
    private fun checkOrRecord(
        case: JsonObject,
        group: String,
        actual: JsonElement,
    ): JsonObject {
        val name = case.str("name")
        if (recording) return JsonObject(case.toMutableMap().apply { put("expect", actual) })

        val expected =
            case["expect"]?.jsonObject
                ?: fail("$group/$name has no `expect` — record it (see this file's KDoc)")
        if (expected.isEmpty()) fail("$group/$name has an empty `expect` — record it (see this file's KDoc)")

        if (group == "splitBill") {
            // A splitBill case may pin a subset (owedByUser, items, …) — assert what it declares.
            for (key in expected.keys) {
                assertEquals(expected.getValue(key), (actual as JsonObject)[key], "$group/$name → expect.$key")
            }
        } else {
            assertEquals(expected as JsonElement, actual, "$group/$name")
        }
        return case
    }

    // ---- tiny JSON helpers -------------------------------------------------------------------

    private fun JsonObject.cases(group: String): List<JsonObject> = (this[group]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject }

    private fun JsonObject.rows(key: String): List<JsonObject> = (this[key]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.long ?: 0L

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.int ?: 0

    private fun Map<UserId, Long>.toJson(): JsonObject = JsonObject(entries.associate { (id, amount) -> id.value to JsonPrimitive(amount) })

    private fun vectorsFile(): File {
        // Walk up from the Gradle test working directory (`code/shared`) to the repo root, so the
        // path survives being run from an IDE, from `code/`, or from anywhere else.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "test-vectors/bill-split.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("test-vectors/bill-split.json not found walking up from ${System.getProperty("user.dir")}")
    }
}
