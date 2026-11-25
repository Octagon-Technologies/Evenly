<script lang="ts">
  /**
   * Frame 4 — the main screen.
   *
   * **Every line is always visible, whatever its state** (spec §2.5). A claimed line changes what it
   * *offers* you, never whether it exists. The temptation to show only "what's left" is far stronger
   * here than in the app, because this screen is claim-first — and giving in to it means Jane can
   * never find the chicken Mary already claimed, can never join it, and the split is silently wrong.
   *
   * **The lines are the app's assign cards** (decided 2026-11-24, replacing §2.6's chip rows): a
   * numbered mark, the name, who has it ("2 of 4 servings", "You + Purity"), the price, and a chevron.
   * Tapping a card opens it in place, one at a time, with the line's actions. Guests and app users
   * should recognise the same screen. The amount column still never moves.
   */
  import BillHeader from '../components/BillHeader.svelte';
  import ErrorBanner from '../components/ErrorBanner.svelte';
  import Progress from '../components/Progress.svelte';
  import Avatar from '../components/Avatar.svelte';
  import JoinSheet from './JoinSheet.svelte';
  import ShareSheet from './ShareSheet.svelte';
  import AddItemSheet from './AddItemSheet.svelte';
  import { amount, money } from '../lib/format.ts';
  import type { LineView } from '../lib/lines.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  const { store }: { store: ClaimStore } = $props();

  let joining = $state<LineView | null>(null);
  let sharing = $state<LineView | null>(null);
  let adding = $state(false);
  let hint = $state<string | null>(null);
  // One card open at a time, like the app. Opens on the first line nobody holds yet.
  let openId = $state<string | null>(null);
  let opened = false;
  $effect(() => {
    if (opened || store.lines.length === 0) return;
    opened = true;
    openId = (store.lines.find((l) => l.assigned === 0) ?? store.lines[0]).itemId;
  });

  /** The card's second line, the same wording as the app's `assigneeLine`. */
  function assigneeLine(line: LineView): string {
    const names = [...new Set(line.chips.map((c) => (c.isMe ? 'You' : c.name)))];
    if (line.status === 'OVERCLAIMED') return 'More claimed than ordered';
    if (line.quantity > 1 && line.left > 0) return `${line.assigned} of ${line.quantity} servings`;
    if (names.length === 0) return 'No one yet';
    if (names.length <= 3) return names.join(' + ');
    return `${names[0]} + ${names.length - 1} others`;
  }

  function toggle(line: LineView) {
    openId = openId === line.itemId ? null : line.itemId;
  }

  /** The most servings I can hold solo: what I have plus what nobody has yet. */
  function maxMine(line: LineView): number {
    return line.myUnits + line.left;
  }

  async function setMine(line: LineView, n: number) {
    hint = null;
    await store.setClaim(line.itemId, Math.max(0, Math.min(n, maxMine(line))));
  }

  /** "I had this" on a single plate moves on to the next line nobody holds, as the app's cards do. */
  async function claimAndAdvance(line: LineView) {
    await setMine(line, 1);
    if (store.error) return;
    const at = store.lines.findIndex((l) => l.itemId === line.itemId);
    const next = store.lines.slice(at + 1).find((l) => l.assigned === 0);
    if (next) openId = next.itemId;
  }

  /** A sheet is a real modal: the page behind it must not be tabbable or readable underneath. */
  const sheetOpen = $derived(joining !== null || sharing !== null || adding);

  /**
   * People who have actually put themselves on something — not people who tapped "I'm done". The
   * question this answers is "is everyone still working?", and someone who claimed three plates and
   * wandered off has claimed.
   */
  const peopleClaimed = $derived(
    new Set([
      ...(store.bill?.claims ?? []).map((c) => c.user_id),
      ...(store.bill?.shares ?? []).map((s) => s.user_id),
    ]).size,
  );

  async function claimOne(line: LineView) {
    await store.setClaim(line.itemId, 1);
  }

  /** "Purity added Mango sticky rice". Past tense, because it already happened. */
  function verb(kind: string): string {
    if (kind === 'ADD') return 'added';
    if (kind === 'REMOVE') return 'removed';
    if (kind === 'RELABEL') return 'renamed';
    return 'changed';
  }

  function finish() {
    if (store.myTotalSubunits === 0) {
      // Never a silent dead end (root AGENTS.md §7): the button stays live and says what's missing.
      hint = "You haven't claimed anything yet. Tap an item you had, then I had this.";
      return;
    }
    void store.setDone(true);
  }
</script>

<div class="page" inert={sheetOpen}>
  <div class="scroll">
    {#if store.header}
      <BillHeader
        header={store.header}
        title="What did you have, {store.identity?.name ?? 'you'}?"
        showProof={false}
      />
      <Progress
        claimedSubunits={store.progress.claimedSubunits}
        totalSubunits={store.progress.totalSubunits}
        currency={store.currency}
        {peopleClaimed}
        peopleTotal={store.header.participantCount}
      />
    {/if}

    {#if store.error}
      <ErrorBanner message={store.error} onretry={() => store.refresh()} />
    {/if}

    <div class="k-sec">
      <span>Items</span>
      <span class="mono">{store.lines.length}</span>
    </div>
    <div class="cards">
      {#each store.lines as line, i (line.itemId)}
        {@const open = openId === line.itemId}
        <div class="acard" class:acard--open={open} class:acard--mine={line.mine}>
          <button
            type="button"
            class="ahead"
            aria-expanded={open}
            aria-label="{line.label}, {assigneeLine(line)}"
            onclick={() => toggle(line)}
          >
            <span class="mark" class:mark--on={line.mine}>
              {#if line.mine}
                <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"
                  ><path d="M3.5 8.5l3 3 6-7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg
                >
              {:else}
                {i + 1}
              {/if}
            </span>
            <span class="grow">
              <span class="albl">{line.label}</span>
              <span class="asub" class:asub--warn={line.status === 'OVERCLAIMED'}>{assigneeLine(line)}</span>
            </span>
            {#if line.quantity > 1}<span class="qchip">×{line.quantity}</span>{/if}
            <span class="aamt mono">{money(line.lineTotalSubunits, store.currency)}</span>
            <svg class="chev" class:chev--up={open} viewBox="0 0 16 16" width="18" height="18" aria-hidden="true"
              ><path d="M4 6l4 4 4-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg
            >
          </button>

          {#if open}
            <div class="abody">
              {#if line.quantity > 1}
                <div class="k">How many did you have?</div>
                <div class="stepper">
                  <button
                    type="button"
                    class="stp"
                    aria-label="One fewer"
                    disabled={store.busy || line.myUnits === 0}
                    onclick={() => setMine(line, line.myUnits - 1)}>−</button
                  >
                  <span class="stv"
                    ><span><b class="mono">{line.myUnits}</b> of {line.quantity}</span>
                    <small>{money(Math.round(line.lineTotalSubunits / line.quantity), store.currency)} each</small></span
                  >
                  <button
                    type="button"
                    class="stp stp--plus"
                    aria-label="One more"
                    disabled={store.busy}
                    onclick={() =>
                      line.myUnits < maxMine(line)
                        ? setMine(line, line.myUnits + 1)
                        : (hint = `All ${line.quantity} ${line.label} are taken. If you shared one, tap Shared one.`)}
                    >＋</button
                  >
                </div>
              {:else if line.action === 'claim'}
                <button type="button" class="btn" aria-busy={store.busy} onclick={() => claimAndAdvance(line)}>I had this</button>
              {:else if line.action === 'add-me'}
                <button type="button" class="btn" onclick={() => (joining = line)}>I shared this too</button>
              {/if}

              {#if line.chips.length > 0}
                <div class="k">Who has it</div>
                <div class="chips">
                  {#each line.chips as chip (chip.userId + (chip.portionId ?? ''))}
                    <span class="chip" class:chip--me={chip.isMe}
                      ><Avatar name={chip.isMe ? (store.identity?.name ?? 'You') : chip.name} />{chip.isMe ? 'You' : chip.name}</span
                    >
                  {/each}
                </div>
              {/if}

              <div class="arow">
                {#if line.myShareSubunits > 0}
                  <span class="k">Your part <b class="mono">{money(line.myShareSubunits, store.currency)}</b></span>
                {:else}
                  <span></span>
                {/if}
                <button type="button" class="link" onclick={() => (sharing = line)}>
                  {line.quantity > 1 ? 'Shared one' : line.mine ? 'Shared it? Change' : 'Shared it with someone'}
                </button>
              </div>
            </div>
          {/if}
        </div>
      {/each}
    </div>

    <button type="button" class="btn btn--ghost btn--sm" onclick={() => (adding = true)}>
      ＋  Add something that's missing
    </button>

    <!--
      What changed, and the way to take it back (§2.7). This is NOT prose in the claim list: the rule
      there is chips only, and this is a separate section below the lines that renders on the minority
      of bills where anyone edited anything.

      **Anyone on the bill may undo**, not just the person who did it and not just the payer. An undo is
      itself an attributed entry here, and undoing something already undone does nothing, so the log is
      the tiebreak. A rights hierarchy would re-import the adjudication the approval gate used to be.
    -->
    {#if store.changes.length > 0}
      <div class="changes">
        <div class="k">Changes to this bill</div>
        {#each store.changes as change (change.id)}
          <div class="chg" class:chg--done={!change.live}>
            <div class="grow">
              <span class="lbl">{change.mine ? 'You' : change.byName} {verb(change.kind)} {change.label}</span
              >
              <div class="meta">
                {#if change.live}
                  {#if change.deltaSubunits !== 0}
                    {change.deltaSubunits > 0 ? '+' : '−'}<span class="mono"
                      >{amount(Math.abs(change.deltaSubunits), store.currency)}</span
                    > on the bill
                  {:else}
                    No change to the total
                  {/if}
                {:else}
                  Undone by {change.undoneByName}
                {/if}
              </div>
            </div>
            {#if change.live}
              <button type="button" class="link" onclick={() => store.undoChange(change.id)}>Undo</button>
            {/if}
          </div>
        {/each}
      </div>
    {/if}

    {#if hint}
      <div class="note note--amber" role="alert" style="margin-top:14px">{hint}</div>
    {/if}

    <!-- E4/E5: the way out is on every screen, not buried at the start. -->
    <p class="appline">
      Not {store.identity?.name ?? 'you'}?
      <button type="button" class="link" onclick={() => store.release()}>Use a different name</button>
    </p>
  </div>

  <div class="dock">
    <div class="dockrow">
      <span class="k">Your total so far</span>
      <span class="v mono">{money(store.myTotalSubunits, store.currency)}</span>
    </div>
    <button type="button" class="btn" aria-busy={store.busy} onclick={finish}>I'm done</button>
  </div>
</div>

{#if joining}
  <JoinSheet {store} line={joining} onclose={() => (joining = null)} />
{/if}
{#if sharing}
  <ShareSheet {store} line={sharing} onclose={() => (sharing = null)} />
{/if}
{#if adding}
  <AddItemSheet {store} onclose={() => (adding = false)} />
{/if}

<style>
  .k-sec {
    display: flex;
    justify-content: space-between;
    margin: 18px 2px 8px;
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--ink-3);
  }
  .cards {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-bottom: 12px;
  }
  /* The app's AccordionCard: 13px radius, hairline border, blue 1.5px border while open. */
  .acard {
    border: 1px solid var(--border);
    border-radius: 13px;
    background: var(--page);
  }
  .acard--open {
    border: 1.5px solid var(--blue);
  }
  .ahead {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 16px 14px;
    background: none;
    border: none;
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .grow {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .albl {
    font-size: 15.5px;
    font-weight: 600;
    color: var(--ink);
  }
  .asub {
    font-size: 13px;
    color: var(--ink-2);
  }
  .asub--warn {
    color: var(--amber);
  }
  .mark {
    flex: none;
    width: 28px;
    height: 28px;
    border-radius: 50%;
    border: 1.5px solid var(--border-strong);
    display: grid;
    place-items: center;
    font: 600 12px var(--mono);
    color: var(--ink-2);
  }
  .mark--on {
    background: var(--blue);
    border-color: var(--blue);
    color: #fff;
  }
  /* Same chip as the app's receipt review: quantity in white on blue so it never reads as small print. */
  .qchip {
    flex: none;
    min-width: 28px;
    padding: 3px 8px;
    border-radius: var(--r-pill);
    background: var(--blue);
    color: #fff;
    font-size: 13px;
    font-weight: 600;
    text-align: center;
  }
  .aamt {
    flex: none;
    font-size: 15px;
    color: var(--ink);
  }
  .chev {
    flex: none;
    color: var(--ink);
    transition: transform 0.15s;
  }
  .chev--up {
    transform: rotate(180deg);
  }
  .abody {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 0 14px 14px;
  }
  .abody .k {
    font-size: 13px;
    font-weight: 600;
    color: var(--ink-2);
  }
  .abody .btn {
    margin: 0;
  }
  .stepper {
    display: flex;
    align-items: center;
    gap: 10px;
    background: var(--surface);
    border-radius: var(--r-sm);
    padding: 8px;
  }
  .stp {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    border: 1px solid var(--border-strong);
    background: var(--page);
    font-size: 20px;
    color: var(--ink);
    cursor: pointer;
  }
  .stp--plus {
    background: var(--blue);
    border-color: var(--blue);
    color: #fff;
  }
  .stp:disabled {
    opacity: 0.4;
  }
  .stv {
    flex: 1;
    text-align: center;
    font-size: 15px;
    display: flex;
    flex-direction: column;
  }
  .stv small {
    font-size: 12px;
    color: var(--ink-3);
  }
  .arow {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .changes {
    margin-top: 18px;
  }
  .changes > .k {
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-3);
    margin-bottom: 6px;
  }
  .chg {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 0;
    border-top: 1px solid var(--border);
  }
  /* An undone change stays on screen: a mis-tap that vanished would be worse than one that is legible. */
  .chg--done .lbl {
    text-decoration: line-through;
    color: var(--ink-3);
  }
  .chg .lbl {
    font-size: 13.5px;
  }
  .chg .meta {
    font-size: 11.5px;
    color: var(--ink-3);
  }
</style>
