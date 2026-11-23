/**
 * Who owes whom in a group — a hand-port of `domain/balance/BilateralBalance.kt`'s
 * `buildBilateralBalances`. Kotlin is the authority; this is what the web group page shows.
 *
 * Each pair of people is netted on its own, within one currency. Never across a triangle (Alice owes
 * Bob, Bob owes Joe stays two debts) and never across currencies. A share's `remaining` arrives already
 * derived by the server the same way the app derives it: owed minus live payments, 0 for the payer.
 */

/** One person's outstanding part of one expense, from the payer's side. */
export interface OpenShare {
  payerUserId: string;
  participantUserId: string;
  currency: string;
  remainingSubunits: number;
}

/** [debtorUserId] owes [creditorUserId] this much, in this currency. Always strictly positive. */
export interface Debt {
  debtorUserId: string;
  creditorUserId: string;
  currency: string;
  amountSubunits: number;
}

export function buildBilateralBalances(shares: readonly OpenShare[]): Debt[] {
  const key = (c: string, payer: string, participant: string) => `${c}\u0000${payer}\u0000${participant}`;
  const owed = new Map<string, number>();
  for (const s of shares) {
    if (s.remainingSubunits <= 0) continue;
    const k = key(s.currency, s.payerUserId, s.participantUserId);
    owed.set(k, (owed.get(k) ?? 0) + s.remainingSubunits);
  }
  const debts: Debt[] = [];
  const handled = new Set<string>();
  for (const [k, forward] of owed) {
    if (handled.has(k)) continue;
    const [currency, payer, participant] = k.split('\u0000');
    const reverseKey = key(currency, participant, payer);
    const reverse = owed.get(reverseKey) ?? 0;
    handled.add(k);
    handled.add(reverseKey);
    const net = forward - reverse;
    if (net > 0) debts.push({ debtorUserId: participant, creditorUserId: payer, currency, amountSubunits: net });
    else if (net < 0) debts.push({ debtorUserId: payer, creditorUserId: participant, currency, amountSubunits: -net });
  }
  return debts;
}

/** The debts that involve [me]: what I owe each person, and what each person owes me. */
export function myDebts(debts: readonly Debt[], me: string): { iOwe: Debt[]; owedToMe: Debt[] } {
  return {
    iOwe: debts.filter((d) => d.debtorUserId === me),
    owedToMe: debts.filter((d) => d.creditorUserId === me),
  };
}
