/**
 * Identity is group-scoped and durable; authorisation is bill-scoped and expires (spec §2.2).
 *
 * This file owns the durable half: a browser token that says "I am Purity in this group", kept so a
 * guest recognised at Ramen night is still recognised at Sunday roast, long after the Ramen night
 * link is dead. It stores nothing about the bill itself.
 *
 * `localStorage`, not a cookie: the token is sent explicitly in the request body, so there is no
 * ambient credential for a cross-site request to ride on.
 *
 * **The chicken-and-egg**, and why there are two keys. The server scopes a session to a group; the
 * browser only learns which group a link belongs to *after* `resolve` answers, and `resolve` wants
 * the session token in the same breath. So a token is filed twice:
 *
 * - under its **bill token** — the fast path, and the common one: same link, later that evening;
 * - under its **group name** — the §2.2 path: a different bill, same group, weeks later.
 *
 * A group name is not globally unique, so the group key can produce a token from a different group.
 * That is harmless by construction: the server resolves sessions within the link's own group and
 * simply finds nothing, and the guest lands on the evidence list — the same place an unknown visitor
 * lands. Erring toward asking is the correct side of §2.3 to be wrong on.
 */

const PREFIX = 'evenly.web.session.';

function safeStorage(): Storage | null {
  try {
    // Private-mode Safari and a locked-down browser both throw here rather than returning null.
    const s = window.localStorage;
    const probe = `${PREFIX}probe`;
    s.setItem(probe, '1');
    s.removeItem(probe);
    return s;
  } catch {
    return null;
  }
}

function billKey(billToken: string): string {
  return `${PREFIX}b.${billToken}`;
}

function groupKey(groupName: string): string {
  return `${PREFIX}g.${groupName.trim().toLowerCase()}`;
}

/**
 * With no storage the guest is simply never recognised — she picks her name again, which is the E6
 * path ("cleared storage mid-bill") and already works. Nothing here throws.
 */
export function tokenForBill(billToken: string): string | null {
  return safeStorage()?.getItem(billKey(billToken)) ?? null;
}

export function tokenForGroup(groupName: string): string | null {
  return safeStorage()?.getItem(groupKey(groupName)) ?? null;
}

export function saveSessionToken(billToken: string, groupName: string, sessionToken: string): void {
  const s = safeStorage();
  if (!s) return;
  s.setItem(billKey(billToken), sessionToken);
  s.setItem(groupKey(groupName), sessionToken);
}

/**
 * "Not Purity? Use a different name" (E4) and "someone else's turn with the phone" (E5). Drops both
 * filings, so the next resolve treats this browser as an unknown visitor.
 */
export function clearSessionToken(billToken: string, groupName: string): void {
  const s = safeStorage();
  if (!s) return;
  s.removeItem(billKey(billToken));
  s.removeItem(groupKey(groupName));
}
