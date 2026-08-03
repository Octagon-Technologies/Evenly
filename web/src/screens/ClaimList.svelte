<script lang="ts">
  /**
   * Frame 4 — the main screen.
   *
   * **Every line is always visible, whatever its state** (spec §2.5). A claimed line changes what it
   * *offers* you, never whether it exists. The temptation to show only "what's left" is far stronger
   * here than in the app, because this screen is claim-first — and giving in to it means Jane can
   * never find the chicken Mary already claimed, can never join it, and the split is silently wrong.
   *
   * **The affordance is a chip in the people row** (§2.6), never a button in the price column, so the
   * amount column never moves and the card scans as one clean column of money. **No prose in the
   * claim list** — chips only. `N left` is a chip, not a sentence, and is not tappable.
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
      hint = "You haven't claimed anything yet. Tap ＋ I had this on what you ate, or say you had nothing.";
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

    <div class="card" style="margin-top:14px">
      {#each store.lines as line (line.itemId)}
        <div class="row" class:row--mine={line.mine}>
          <span class="qty">{line.quantity}</span>
          <div class="grow">
            <!-- The label is the wide tap target that opens the line's sheet (§2.6). The chips beside
                 it are their own buttons, so nothing interactive is nested inside anything else. -->
            <button
              type="button"
              class="linebtn"
              aria-label="{line.label} — who had this?"
              onclick={() => (sharing = line)}
            >
              <span class="lbl">{line.label}</span>
            </button>
            <div class="chips">
              {#each line.chips as chip (chip.userId + (chip.portionId ?? ''))}
                {#if chip.isMe}
                  <button type="button" class="chip chip--me" onclick={() => (sharing = line)}>
                    <Avatar name={store.identity?.name ?? 'You'} />You
                  </button>
                {:else}
                  <span class="chip"><Avatar name={chip.name} />{chip.name}</span>
                {/if}
              {/each}

              {#if line.action === 'claim'}
                <button type="button" class="chip chip--claim" onclick={() => claimOne(line)}>
                  ＋ I had this
                </button>
              {:else if line.action === 'add-me'}
                <button type="button" class="chip chip--join" onclick={() => (joining = line)}>
                  ＋ Add me
                </button>
              {/if}

              {#if line.left > 0 && line.assigned > 0}
                <span class="chip chip--ghost">{line.left} left</span>
              {/if}
              {#if line.status === 'OVERCLAIMED'}
                <span class="chip chip--ghost">more claimed than ordered</span>
              {/if}
            </div>
          </div>
          <div class="amt mono">{amount(line.lineTotalSubunits, store.currency)}</div>
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
  .linebtn {
    display: block;
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    padding: 0;
    font: inherit;
    color: inherit;
    cursor: pointer;
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
