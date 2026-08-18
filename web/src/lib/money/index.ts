/**
 * The web's money engine — a hand-port of the Kotlin split math (WEB_CLAIM_SPEC.md §2.10, §7).
 *
 * Authoritative for what a guest *sees*, never for what the ledger *records*. `test-vectors/
 * bill-split.json` runs against both implementations in CI; divergence is a red build.
 */

export { allocate, type Weight } from './allocate.ts';
export {
  splitBill,
  perUnitSubunits,
  tabTotal,
  fullyResolved,
  unclaimedCount,
  overClaimedCount,
  type BillItem,
  type IndividualClaim,
  type SharedMember,
  type SharedPortion,
  type TipSplitMode,
  type BillExtras,
  type ItemStatus,
  type ItemReconcile,
  type TabBreakdown,
  type BillResult,
  type SplitBillInput,
} from './billSplit.ts';
export { toSplitInput, type BillPayload } from './fromApi.ts';
