<script lang="ts">
  /**
   * The web group page (docs/features/web-group-page.md, mockup `design/web-group-page-mockup.html`):
   * the whole group for someone without the app. All is the default so a guest sees what an app user
   * beside them sees; Mine is one tap away.
   *
   * The left-edge strip marks every expense the guest is part of, and nothing on the page explains it:
   * repetition teaches it, the same rule as the app's expense rows.
   */
  import { onMount } from 'svelte';
  import ErrorBanner from '../../components/ErrorBanner.svelte';
  import { money, shortDate } from '../../lib/format.ts';
  import { GroupStore } from '../../lib/group.svelte.ts';
  import type { GroupExpense } from '../../lib/api.ts';
  import SettleSheet from './SettleSheet.svelte';
  import ExpenseSheet from './ExpenseSheet.svelte';
  import ExpenseForm from './ExpenseForm.svelte';

  interface Props {
    store: GroupStore;
    onNotMe: () => void;
  }

  const { store, onNotMe }: Props = $props();

  let filter = $state<'all' | 'mine'>('all');
  let settling = $state(false);
  let adding = $state(false);
  let openId = $state<string | null>(null);
  let toast = $state<string | null>(null);

  onMount(() => {
    void store.load();
    // A light poll so a bill the app user just added shows up without a manual refresh.
    const timer = setInterval(() => {
      if (!document.hidden && !store.busy) void store.load();
    }, 30_000);
    return () => clearInterval(timer);
  });

  const view = $derived(store.view);
  const shown = $derived((view?.expenses ?? []).filter((e) => filter === 'all' || store.involved(e)));
  const days = $derived.by(() => {
    const out: Array<{ date: string; items: GroupExpense[] }> = [];
    for (const e of shown) {
      const last = out[out.length - 1];
      if (last && last.date === e.date) last.items.push(e);
      else out.push({ date: e.date, items: [e] });
    }
    return out;
  });
  const opened = $derived(view?.expenses.find((e) => e.id === openId) ?? null);
  const today = new Date().toISOString().slice(0, 10);

  function dayLabel(date: string): string {
    return date === today ? 'Today' : shortDate(date);
  }

  function flash(message: string) {
    toast = message;
    setTimeout(() => (toast = null), 3500);
  }
</script>

{#if store.phase === 'loading' || !view}
  <div class="page">
    <div class="scroll center" style="padding-top:80px">
      {#if store.phase === 'failed'}
        <div class="h2">Couldn't load the group</div>
        <div class="sub">Check your connection and try again.</div>
        <div class="spacer"></div>
        <button type="button" class="btn" onclick={() => store.load()}>Try again</button>
      {:else}
        <div class="spin" style="margin:0 auto 14px"></div>
        <p class="sub">Loading the group…</p>
      {/if}
    </div>
  </div>
{:else}
  <div class="page">
    <div class="scroll gp">
      <div class="gp-head">
        <div class="gp-emoji" aria-hidden="true">{view.header.emoji}</div>
        <div class="grow">
          <div class="gp-name">{view.header.groupName}</div>
          <div class="gp-me">
            You're {view.me.name} · <button type="button" class="link" onclick={onNotMe}>Not you?</button>
          </div>
        </div>
      </div>

      {#if view.readOnly}
        <div class="note note--blue">
          {view.me.name} is linked to an Evenly account now, so changes happen in the app. This page still shows
          the group.
        </div>
      {/if}

      <div class="gp-hero">
        {#if store.debts.iOwe.length > 0}
          <div class="gp-k">You owe</div>
          {#each store.debts.iOwe as d (d.creditorUserId + d.currency)}
            <div class="gp-line"><span>{store.name(d.creditorUserId)}</span><span class="mono">{money(d.amountSubunits, d.currency)}</span></div>
          {/each}
          {#if !view.readOnly}
            <button type="button" class="btn gp-settle" onclick={() => (settling = true)}>
              {store.debts.iOwe.length === 1 ? `Pay ${store.name(store.debts.iOwe[0].creditorUserId)} back` : 'Pay someone back'}
            </button>
          {/if}
        {/if}
        {#if store.debts.owedToMe.length > 0}
          <div class="gp-k" class:gp-k--gap={store.debts.iOwe.length > 0}>You're owed</div>
          {#each store.debts.owedToMe as d (d.debtorUserId + d.currency)}
            <div class="gp-line gp-line--owed"><span>{store.name(d.debtorUserId)}</span><span class="mono">{money(d.amountSubunits, d.currency)}</span></div>
          {/each}
        {/if}
        {#if store.debts.iOwe.length === 0 && store.debts.owedToMe.length === 0}
          <div class="gp-k">You're all square</div>
          <div class="gp-sub">Nobody owes you and you don't owe anyone here.</div>
        {/if}
      </div>

      {#if store.waiting.length > 0 && !view.readOnly}
        <div class="gp-wait">
          <div class="gp-wait-h">Waiting on you</div>
          {#each store.waiting as e (e.id)}
            <a class="gp-wait-r" href={`/b/${store.billToken(e)}`}>
              <span class="grow">{e.title} · pick what you had</span>
              <span class="gp-go">Claim ›</span>
            </a>
          {/each}
        </div>
      {/if}

      <div class="gp-seg" role="tablist" aria-label="Which expenses">
        <button type="button" role="tab" aria-selected={filter === 'all'} class:on={filter === 'all'} onclick={() => (filter = 'all')}>All</button>
        <button type="button" role="tab" aria-selected={filter === 'mine'} class:on={filter === 'mine'} onclick={() => (filter = 'mine')}>Mine</button>
      </div>

      {#if days.length === 0}
        <div class="gp-empty">
          {#if filter === 'mine'}
            You're not on any expenses yet.
          {:else}
            Nothing here yet. Add the first expense.
          {/if}
        </div>
      {/if}

      {#each days as day (day.date)}
        <div class="gp-day">{dayLabel(day.date)}</div>
        {#each day.items as e (e.id)}
          {@const stake = store.stake(e)}
          <button type="button" class="gp-row" class:in={store.involved(e)} onclick={() => (openId = e.id)}>
            <span class="grow">
              <span class="gp-t">{e.title}</span>
              <span class="gp-s">{store.name(e.payerUserId) === 'You' ? 'You' : store.name(e.payerUserId)} paid{e.splitMode === 'ITEMIZED' ? ' · by items' : ''}</span>
            </span>
            {#if store.settled(e)}
              <span class="gp-done">✓ Settled</span>
            {:else if stake}
              <span class="gp-stake" class:owed={stake.owedToMe}>
                <span class="gp-stake-l">{stake.owedToMe ? "you're owed" : 'you owe'}</span>
                <span class="mono">{money(stake.subunits, e.currency)}</span>
              </span>
            {:else}
              <span class="gp-amt mono">{money(e.amountSubunits, e.currency)}</span>
            {/if}
            <span class="gp-chev" aria-hidden="true">›</span>
          </button>
        {/each}
      {/each}
      <div style="height:84px"></div>
    </div>

    {#if !view.readOnly}
      <button type="button" class="gp-fab" onclick={() => (adding = true)}>+ Add expense</button>
    {/if}
    {#if toast}<div class="gp-toast" role="status">{toast}</div>{/if}
  </div>

  {#if settling}
    <SettleSheet {store} onclose={() => (settling = false)} ondone={(m) => { settling = false; flash(m); }} />
  {/if}
  {#if opened}
    <ExpenseSheet {store} expense={opened} onclose={() => (openId = null)} onsaved={flash} />
  {/if}
  {#if adding}
    <ExpenseForm
      {store}
      onclose={() => (adding = false)}
      onsaved={(m, billToken) => {
        adding = false;
        flash(m);
        if (billToken) window.location.href = `/b/${billToken}`;
      }}
    />
  {/if}
{/if}

{#if store.phase === 'ready' && !view}
  <ErrorBanner message="Couldn't load the group." />
{/if}

<style>
  .gp {
    padding-top: 18px;
  }
  .gp-head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 14px;
  }
  .gp-emoji {
    width: 46px;
    height: 46px;
    border-radius: 14px;
    background: var(--blue-tint);
    display: grid;
    place-items: center;
    font-size: 24px;
    flex-shrink: 0;
  }
  .gp-name {
    font-size: 20px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }
  .gp-me {
    font-size: 13px;
    color: var(--ink-2);
    margin-top: 2px;
  }
  .gp-hero {
    background: var(--surface);
    border-radius: var(--r-card);
    padding: 14px;
    margin-bottom: 12px;
  }
  .gp-k {
    font-size: 12.5px;
    font-weight: 660;
    color: var(--ink-2);
    margin-bottom: 4px;
  }
  .gp-k--gap {
    margin-top: 14px;
  }
  .gp-sub {
    font-size: 13px;
    color: var(--ink-2);
  }
  .gp-line {
    display: flex;
    justify-content: space-between;
    font-size: 15px;
    font-weight: 600;
    padding: 6px 0;
    border-top: 1px solid var(--border);
  }
  .gp-line:first-of-type {
    border-top: 0;
  }
  .gp-line--owed .mono {
    color: var(--green);
  }
  .gp-settle {
    margin-top: 10px;
  }
  .gp-wait {
    background: var(--blue-tint);
    border-radius: var(--r-card);
    padding: 12px;
    margin-bottom: 12px;
  }
  .gp-wait-h {
    font-size: 13px;
    font-weight: 660;
    margin-bottom: 8px;
  }
  .gp-wait-r {
    display: flex;
    align-items: center;
    gap: 8px;
    background: var(--page);
    border-radius: var(--r-sm);
    padding: 11px 12px;
    font-size: 13.5px;
    color: var(--ink);
    text-decoration: none;
    margin-top: 6px;
  }
  .gp-go {
    color: var(--blue);
    font-weight: 660;
    white-space: nowrap;
  }
  .gp-seg {
    display: flex;
    background: var(--surface);
    border-radius: var(--r-pill);
    padding: 3px;
    margin: 4px 0 6px;
  }
  .gp-seg button {
    flex: 1;
    border: 0;
    background: none;
    font: 600 13.5px var(--font);
    color: var(--ink-2);
    padding: 8px 0;
    border-radius: var(--r-pill);
    cursor: pointer;
  }
  .gp-seg button.on {
    background: var(--page);
    color: var(--ink);
    box-shadow: 0 1px 3px rgba(11, 18, 32, 0.08);
  }
  .gp-day {
    font-size: 11.5px;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: var(--ink-3);
    margin: 14px 0 6px;
  }
  .gp-row {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    text-align: left;
    background: var(--page);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 12px 12px 12px 14px;
    margin-bottom: 8px;
    font: inherit;
    color: var(--ink);
    cursor: pointer;
    overflow: hidden;
  }
  /* 3px, the app's InvolvedStripWidth: 2 went faint in testing. */
  .gp-row.in::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 3px;
    background: var(--blue);
  }
  .gp-t {
    display: block;
    font-weight: 600;
    font-size: 14.5px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .gp-s {
    display: block;
    font-size: 12.5px;
    color: var(--ink-2);
    margin-top: 2px;
  }
  .gp-stake {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    font-weight: 660;
    font-size: 14.5px;
    flex-shrink: 0;
  }
  .gp-stake.owed {
    color: var(--green);
  }
  .gp-stake-l {
    font-size: 10.5px;
  }
  .gp-amt {
    font-size: 13.5px;
    color: var(--ink-2);
    flex-shrink: 0;
  }
  .gp-done {
    font-size: 11.5px;
    font-weight: 660;
    color: var(--green);
    background: var(--green-tint);
    border-radius: var(--r-pill);
    padding: 3px 9px;
    flex-shrink: 0;
  }
  .gp-chev {
    color: var(--ink-3);
  }
  .gp-empty {
    text-align: center;
    color: var(--ink-2);
    font-size: 13.5px;
    padding: 28px 0;
  }
  .gp-fab {
    position: fixed;
    bottom: calc(18px + env(safe-area-inset-bottom));
    /* The right edge of the centred 480px column, or of the screen when it is narrower. */
    right: max(18px, calc(50vw - 240px + 18px));
    background: var(--blue);
    color: #fff;
    border: 0;
    border-radius: var(--r-pill);
    padding: 14px 18px;
    font: 660 15px var(--font);
    box-shadow: 0 6px 18px rgba(37, 99, 235, 0.35);
    cursor: pointer;
    z-index: 10;
  }
  .gp-toast {
    position: fixed;
    left: 50%;
    transform: translateX(-50%);
    bottom: calc(84px + env(safe-area-inset-bottom));
    background: var(--ink);
    color: #fff;
    font-size: 13.5px;
    padding: 10px 14px;
    border-radius: 12px;
    z-index: 30;
    max-width: 90vw;
  }
</style>
