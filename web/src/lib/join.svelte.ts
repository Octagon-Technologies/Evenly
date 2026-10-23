/**
 * The group invite page's state (`/j/:token`, `marketing/Join.svelte`): join a group from its link with
 * no app and no account. Mockup: `design/web-group-join-mockup.html`.
 *
 * It is the bill claim flow's identity half and nothing else. Joining creates the same placeholder
 * member and group-scoped session as naming yourself on a bill, so the guest is recognised later on any
 * bill link in the group. There is no group view, balance or expense here (web/AGENTS.md).
 *
 * No optimistic writes (spec §6): `busy` while a write is in flight and an `error` that says it did not
 * land, never a "you're in" that is not true.
 */

import { ApiError, inviteApi, type Candidate, type InviteHeader } from './api.ts';
import { clearInviteSession, saveInviteSession, tokenForGroup, tokenForInvite } from './session.ts';
import { capture } from './analytics.ts';

export type JoinPhase =
  | 'loading'
  | 'landing'
  | 'pick_name'
  | 'name_entry'
  | 'joined'
  | 'claimed_elsewhere'
  | 'gone'
  | 'failed';

export type JoinNameResult =
  | { kind: 'blocked'; suggestions: string[] }
  | { kind: 'suggestion'; candidate: Candidate }
  | { kind: 'joined' }
  | { kind: 'error' };

export class JoinStore {
  phase = $state<JoinPhase>('loading');
  header = $state<InviteHeader | null>(null);
  candidates = $state<Candidate[]>([]);
  /** Who this browser is in the group, once joined or recognised. */
  me = $state<string | null>(null);
  /** True when this visit recognised an earlier join rather than making a new one. */
  returning = $state(false);
  busy = $state(false);
  error = $state<string | null>(null);

  readonly token: string;
  #sessionToken: string | null = null;

  constructor(token: string) {
    this.token = token;
  }

  async load(): Promise<void> {
    this.phase = 'loading';
    this.error = null;
    try {
      const stored = tokenForInvite(this.token);
      let res = await inviteApi.resolve(this.token, stored);
      this.#sessionToken = stored;
      // A guest who first met this group on a bill link is filed under the group's name instead.
      if (!stored && res.state !== 'welcome_back') {
        const byGroup = tokenForGroup(res.header.groupName);
        if (byGroup) {
          const second = await inviteApi.resolve(this.token, byGroup);
          if (second.state === 'welcome_back' || second.state === 'claimed_elsewhere') {
            res = second;
            this.#sessionToken = byGroup;
          }
        }
      }
      this.header = res.header;
      capture('invite_page_viewed', { state: res.state });
      switch (res.state) {
        case 'welcome_back':
          this.me = res.identity.name;
          this.returning = true;
          if (this.#sessionToken) saveInviteSession(this.token, res.header.groupName, this.#sessionToken);
          this.phase = 'joined';
          break;
        case 'claimed_elsewhere':
          this.phase = 'claimed_elsewhere';
          break;
        case 'pick_name':
          this.candidates = res.candidates;
          this.phase = 'landing';
          break;
        case 'name_entry':
          this.candidates = [];
          this.phase = 'landing';
          break;
      }
    } catch (e) {
      this.phase = e instanceof ApiError && e.isTerminal ? 'gone' : 'failed';
    }
  }

  /** "Join <group>" on the landing: recognition first when the group has names nobody has claimed. */
  start(): void {
    this.error = null;
    this.phase = this.candidates.length > 0 ? 'pick_name' : 'name_entry';
  }

  async claim(placeholderUserId: string): Promise<boolean> {
    return this.#write(async () => {
      const res = await inviteApi.claim(this.token, placeholderUserId);
      if (!res.won) {
        this.error = 'Someone just picked that name on another phone. If it really is you, try again in a moment.';
        return false;
      }
      this.#joined(res.sessionToken, res.name, 'claimed_existing');
      return true;
    }, false);
  }

  async submitName(name: string, force = false): Promise<JoinNameResult> {
    return this.#write<JoinNameResult>(async () => {
      const res = await inviteApi.name(this.token, name, force);
      if ('blocked' in res) return { kind: 'blocked', suggestions: res.suggestions };
      if ('suggestion' in res) return { kind: 'suggestion', candidate: res.suggestion };
      this.#joined(res.sessionToken, res.name, 'new_name');
      return { kind: 'joined' };
    }, { kind: 'error' });
  }

  /** "Not <name>? Use a different name": forget this browser's identity and start over. */
  async forget(): Promise<void> {
    if (this.header) clearInviteSession(this.token, this.header.groupName);
    this.#sessionToken = null;
    this.me = null;
    this.returning = false;
    await this.load();
    this.start();
  }

  #joined(sessionToken: string, name: string, how: string): void {
    this.#sessionToken = sessionToken;
    this.me = name;
    this.returning = false;
    if (this.header) saveInviteSession(this.token, this.header.groupName, sessionToken);
    capture('invite_joined_web', { how });
    this.phase = 'joined';
  }

  async #write<T>(run: () => Promise<T>, fallback: T): Promise<T> {
    this.busy = true;
    this.error = null;
    try {
      return await run();
    } catch (e) {
      if (e instanceof ApiError && e.isTerminal) {
        this.phase = 'gone';
      } else {
        this.error = e instanceof ApiError && e.code === 'NETWORK'
          ? "That didn't go through. Check your connection and try again."
          : "That didn't go through. Try again.";
      }
      return fallback;
    } finally {
      this.busy = false;
    }
  }
}
