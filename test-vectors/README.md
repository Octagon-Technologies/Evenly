# `test-vectors/` — the money gate

`bill-split.json` is the one file two implementations of Evenly's split math have to agree on:

| | Runs the vectors | Role |
| --- | --- | --- |
| Kotlin | `code/shared/src/androidHostTest/.../BillSplitVectorsTest.kt` | **The authority.** What the ledger records. |
| TypeScript | `web/test/vectors.test.ts` | The copy. What a web guest sees while claiming. |

`WEB_CLAIM_SPEC.md` §2.10 accepted a hand-port so a guest's total is instant on a flaky restaurant
connection. §7 is the price of that: the port may never drift, and CI runs both against this file
(`.github/workflows/money-vectors.yml`). A divergence is a red build.

## Running them

```bash
cd code && ./gradlew :shared:testAndroidHostTest --tests '*BillSplitVectorsTest*'
```

```bash
cd web && npm test
```

## Adding a case

1. Write the **inputs** into the right group (`allocate`, `itemizedShares`, `splitBill`) with
   `"expect": {}`, plus a `"name"` and a `"source"` saying where the case comes from.
2. Record the expectations **from Kotlin**, which is the authority:

   ```bash
   cd code && EVENLY_VECTORS_RECORD=true ./gradlew :shared:testAndroidHostTest --tests '*BillSplitVectorsTest*'
   ```

   This rewrites the file in place and deliberately fails the run, so a recording can never be
   mistaken for a passing gate.
3. **Read the recorded numbers.** They are only as good as your eye — check the tab sums to the bill,
   the per-line splits sum to their line totals, and the statuses say what you expected.
4. Re-run both suites without the flag. Green on both = the port agrees.

**Never record to turn a red build green.** A changed number means either a deliberate rule change,
which must land in Kotlin *and* TS in the same commit, or a regression.

## What the cases cover

The `source` field traces most of them to the Kotlin test they were lifted from
(`AllocatorTest.kt`, `ItemizedAllocatorTest.kt`, `BillSplitTest.kt`). The rest exist because §7 names
them as where a hand-port goes wrong: penny remainders on odd splits, `EVEN` vs `PROPORTIONAL` tip,
gratuity and discount, over-assignment (`OVERCLAIMED`), partial assignment (`UNCLAIMED`),
multi-portion lines, explicit portions beating the legacy shared set, a zero-quantity line, an
all-free bill, an intermediate product too large for a double, and extras riding on the whole bill's
subtotal rather than only the claimed part — plus the spec's own scenario, a twelve-person dinner
claimed down to the penny.

Expectations are recorded in full (`owedByUser`, `breakdownByUser`, `perItemByUser`, `items`), so a
port that gets the total right by getting two parts wrong still fails.
