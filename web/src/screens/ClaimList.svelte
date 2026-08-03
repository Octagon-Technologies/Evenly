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
            {#if line.pending}
              <!-- E19: it counts toward her total, and it says whose approval it is waiting on, so
                   she is never shown a number she cannot account for. -->
              <span class="lbl">{line.label}</span>
              <div class="meta">
                {line.pending.mine ? 'You added this' : `${line.pending.proposedByName} added this`} ·
                waiting for {store.bill?.payer.name ?? 'the payer'}
              </div>
            {:else}
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
            {/if}
            <div class="chips">
              {#each line.chips as chip (chip.userId + (chip.portionId ?? ''))}
                {#if chip.isMe && line.pending}
                  <span class="chip chip--me"><Avatar name={store.identity?.name ?? 'You'} />You</span>
                {:else if chip.isMe}
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
</style>
