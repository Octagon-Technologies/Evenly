/**
 * The only way this bundle talks to a server.
 *
 * Every guest read and write goes through the `web-claim` edge function, which holds the service key
 * and does its own authorisation per endpoint (spec §4.1). **This bundle ships no Supabase client and
 * no anon key.** If you are about to add one, stop: a browser talking to PostgREST directly turns this
 * feature into a data breach.
 *
 * `verify_jwt = false` for that function (it authenticates the bill token itself), so no `apikey` or
 * `Authorization` header is sent from here — there is no key to send.
 */

import type { BillPayload } from './money/fromApi.ts';

const BASE: string = (import.meta.env.VITE_WEB_CLAIM_URL as string | undefined)?.replace(/\/+$/, '') ?? '';

/** Error codes the edge function returns. `NETWORK` is ours, for a request that never landed. */
export type ApiErrorCode =
  | 'GONE'
  | 'EXPIRED'
  | 'OVERCLAIMED'
  | 'BAD_REQUEST'
  | 'BACKEND'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'NETWORK';

export class ApiError extends Error {
  code: ApiErrorCode;
  status: number;
  detail?: string;

  constructor(code: ApiErrorCode, status: number, detail?: string) {
    super(detail ? `${code}: ${detail}` : code);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.detail = detail;
  }

  /** The link is dead or the bill is gone — the page becomes read-only, not an error toast. */
  get isTerminal(): boolean {
    return this.code === 'GONE' || this.code === 'EXPIRED';
  }
}

export interface BillHeader {
  groupName: string;
  title: string;
  currency: string;
  totalSubunits: number;
  itemCount: number;
  claimedCount: number;
  participantCount: number;
  expiresAt: number;
}

export interface Evidence {
  title: string;
  date: string;
  amountSubunits: number;
}

export interface Candidate {
  userId: string;
  name: string;
  evidence: Evidence[];
}

export type ResolveResponse =
  | { state: 'welcome_back'; header: BillHeader; identity: { userId: string; name: string } }
  | { state: 'pick_name'; header: BillHeader; candidates: Candidate[] }
  | { state: 'name_entry'; header: BillHeader }
  | { state: 'claimed_elsewhere'; header: BillHeader };

/** Three possible outcomes, never two at once: blocked (exact dupe), suggested (fuzzy), or created. */
export type NameResponse =
  | { blocked: true; suggestions: string[] }
  | { suggestion: Candidate }
  | { created: true; userId: string; sessionToken: string; header: BillHeader };

export type ClaimPlaceholderResponse =
  | { won: false; winnerSessionId: string | null }
  | { won: true; sessionToken: string; userId: string; name: string; header: BillHeader };

export interface BillResponse extends BillPayload {
  expense: BillPayload['expense'] & { title: string; currency: string };
  items: Array<{
    id: string;
    label: string;
    quantity: number;
    line_total_subunits: number;
    sort_order: number | null;
  }>;
  claims: Array<{ id: string; item_id: string; user_id: string; quantity: number }>;
  shares: Array<{
    id: string;
    item_id: string;
    user_id: string;
    portion_id: string | null;
    quantity: number;
    added_by: string | null;
  }>;
  pendingEdits: PendingEdit[];
  participants: Array<{ userId: string; name: string; doneAt: number | null }>;
  namesByUser: Record<string, string>;
  payer: { userId: string | null; name: string | null; app: string | null; handle: string | null };
}

export type EditKind = 'ADD' | 'RELABEL' | 'REPRICE' | 'REQUANTITY' | 'REMOVE';

/** A guest's proposed change, awaiting the payer's individual decision (spec §2.7, §5.2). */
export interface PendingEdit {
  id: string;
  item_id: string | null;
  kind: EditKind;
  proposed_label: string | null;
  proposed_quantity: number | null;
  proposed_unit_price_subunits: number | null;
  previous_label: string | null;
  previous_quantity: number | null;
  previous_unit_price_subunits: number | null;
  proposed_by: string;
  proposed_at: number;
  decided_at: number | null;
  decision: 'APPROVED' | 'REJECTED' | null;
}

/** Swapped for the dev fixture by `main.ts` when the page is opened with `?mock`. */
export type Transport = (action: string, body: Record<string, unknown>) => Promise<unknown>;

let transport: Transport = async (action, body) => {
  let res: Response;
  try {
    res = await fetch(`${BASE}/${action}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    // No optimistic anything (spec §6): a write that never landed is a retry, never a fake success.
    throw new ApiError('NETWORK', 0, 'no connection');
  }
  const payload = (await res.json().catch(() => ({}))) as { error?: string; detail?: string };
  if (!res.ok) {
    throw new ApiError((payload.error as ApiErrorCode) ?? 'BACKEND', res.status, payload.detail);
  }
  return payload;
};

export function setTransport(next: Transport): void {
  transport = next;
}

function post<T>(action: string, body: Record<string, unknown>): Promise<T> {
  return transport(action, body) as Promise<T>;
}

export const api = {
  /** Landing branch (spec §3.1). */
  resolve: (token: string, sessionToken: string | null) =>
    post<ResolveResponse>('resolve', { token, sessionToken }),

  /** Submit a typed name. `force` accepts a fuzzy suggestion's rejection and creates anyway (§3.2). */
  name: (token: string, name: string, force = false) => post<NameResponse>('name', { token, name, force }),

  /** "That's me" on the evidence list. First claim wins (§5.4). */
  claimPlaceholder: (token: string, placeholderUserId: string) =>
    post<ClaimPlaceholderResponse>('claim-placeholder', { token, placeholderUserId }),

  /** The claim list's raw rows. Money is computed here in the browser, never returned by the server. */
  bill: (token: string) => post<BillResponse>('bill', { token }),

  /** Solo claim / unclaim. `quantity: 0` removes. */
  claim: (token: string, sessionToken: string, itemId: string, quantity: number) =>
    post<{ ok: true }>('claim', { token, sessionToken, itemId, quantity }),

  /** Join a line someone else already claimed — the one write a guest cannot make directly (§5.3). */
  join: (token: string, sessionToken: string, itemId: string, portionId?: string | null, overClaimAck = false) =>
    post<{ ok: true; portionId: string; quantity: number; members: string[] }>('join', {
      token,
      sessionToken,
      itemId,
      portionId: portionId ?? null,
      overClaimAck,
    }),

  /** Declare who had a line together — the share sheet (§3.4). Caller is always included. */
  share: (token: string, sessionToken: string, itemId: string, memberUserIds: string[], overClaimAck = false) =>
    post<{ ok: true; portionId: string; members: string[] }>('share', {
      token,
      sessionToken,
      itemId,
      memberUserIds,
      overClaimAck,
    }),

  /** Take myself off a line — the mirror of `join`, writing only my own rows (§2.7, E12). */
  leave: (token: string, sessionToken: string, itemId: string) =>
    post<{ ok: true }>('leave', { token, sessionToken, itemId }),

  /** Propose an edit. Never applies it — the payer approves each one in the app (§2.7). */
  edit: (
    token: string,
    sessionToken: string,
    edit: { kind: EditKind; itemId?: string; label?: string; quantity?: number; unitPriceSubunits?: number },
  ) => post<{ ok: true; pendingEditId: string }>('edit', { token, sessionToken, ...edit }),

  /** A nudge-silencer, not a lock (§3.3, E31). */
  done: (token: string, sessionToken: string, done: boolean) =>
    post<{ ok: true }>('done', { token, sessionToken, done }),

  /** "Not Purity? Use a different name" — releases this browser, never the placeholder (E4, E5). */
  release: (token: string, sessionToken: string) =>
    post<{ ok: true; released: boolean }>('release', { token, sessionToken }),
};
