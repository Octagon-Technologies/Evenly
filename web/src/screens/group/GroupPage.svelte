<script lang="ts">
  /**
   * The web group page (docs/features/web-group-page.md): the whole group for someone without the app,
   * built as the app's group screen (web/AGENTS.md, "The web is the app in a browser").
   *
   * Two tabs on a bottom bar, as the app's `GroupHomeScreen` (Overview needs an account, so it is left
   * out): **Expenses** is `GroupExpensesTab` (Active / All / Settled, day bands, expense cards, the
   * "Needs your pick" section, the Add expense button) and **Balances** is `GroupBalancesTab` (You owe /
   * Owes you, one card per person that opens to the expenses behind it).
   *
   * The left-edge strip marks every expense the guest is part of, and nothing on the page explains it:
   * repetition teaches it, the same rule as the app's expense rows.
   */
  import { onMount } from 'svelte';
  import ErrorBanner from '../../components/ErrorBanner.svelte';
  import Avatar from '../../components/Avatar.svelte';
  import Icon from '../../components/Icon.svelte';
  import { money, shortDate } from '../../lib/format.ts';
  import { category, dayLabel, tint } from '../../lib/category.ts';
  import { GroupStore } from '../../lib/group.svelte.ts';
  import type { GroupExpense } from '../../lib/api.ts';
  import type { Debt } from '../../lib/money/balances.ts';
  import SettleSheet from './SettleSheet.svelte';
  import ExpenseSheet from './ExpenseSheet.svelte';
  import ExpenseForm from './ExpenseForm.svelte';

  interface Props {
    store: GroupStore;
    onNotMe: () => void;
  }

  const { store, onNotMe }: Props = $props();

  let tab = $state<'expenses' | 'balances'>('expenses');
  let sub = $state<'Active' | 'All' | 'Settled'>('Active');
  let settledOpen = $state<Record<string, boolean>>({});
  let picksOpen = $state(true);
  let expanded = $state<Record<string, boolean>>({});
  let settling = $state<Debt | null | 'pick'>(null);
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
  const today = new Date().toISOString().slice(0, 10);

  /** Day sections narrowed to the sub-tab, the app's `VisibleDay`. */
  const days = $derived.by(() => {
    const out: Array<{ date: string; active: GroupExpense[]; settled: GroupExpense[] }> = [];
    for (const e of view?.expenses ?? []) {
      const done = store.settled(e);
      if (sub === 'Active' && done) continue;
      if (sub === 'Settled' && !done) continue;
      let day = out[out.length - 1];
      if (!day || day.date !== e.date) {
        day = { date: e.date, active: [], settled: [] };
        out.push(day);
      }
      (done ? day.settled : day.active).push(e);
    }
    return out;
  });
  const opened = $derived(view?.expenses.find((e) => e.id === openId) ?? null);

  /** The expenses behind one person's balance with the guest, for the card's breakdown. */
  function linesFor(peer: string, owedToMe: boolean) {
    const out: Array<{ id: string; title: string; date: string; subunits: number; currency: string }> = [];
    for (const e of view?.expenses ?? []) {
      const payer = owedToMe ? store.me : peer;
      const debtor = owedToMe ? peer : store.me;
      if (e.payerUserId !== payer) continue;
      const left = e.shares.filter((s) => s.userId === debtor).reduce((t, s) => t + Math.max(s.remainingSubunits, 0), 0);
      if (left > 0) out.push({ id: e.id, title: e.title, date: e.date, subunits: left, currency: e.currency });
    }
    return out;
  }

  function flash(message: string) {
    toast = message;
    setTimeout(() => (toast = null), 3500);
  }
</script>

{#snippet expenseRow(e: GroupExpense)}
  {@const stake = store.stake(e)}
  {@const done = store.settled(e)}
  {@const cat = category(e.categoryId)}
  {@const payer = store.name(e.payerUserId)}
  <button type="button" class="xrow" class:in={store.involved(e)} onclick={() => (openId = e.id)}>
    <span class="xtile" style="background:{done ? 'var(--green-tint)' : tint(cat.color)};color:{done ? 'var(--green)' : cat.color}">
      <Icon name={done ? 'check' : cat.icon} size={20} />
    </span>
    <span class="xmid">
      <span class="xt">{e.title}</span>
      <span class="xs">
        <Avatar name={payer === 'You' ? (view?.me.name ?? 'You') : payer} me={payer === 'You'} />
        <span class="xs-t">{payer} paid · {money(e.amountSubunits, e.currency)}</span>
      </span>
    </span>
    <span class="xend">
      {#if done}
        <span class="tag tag--green"><Icon name="check" size={14} />Settled</span>
      {:else if stake}
        <span class="stake" class:owed={stake.owedToMe}>
          <span class="stake-l">{stake.owedToMe ? "you're owed" : 'you owe'}</span>
          <span class="mono stake-v">{money(stake.subunits, e.currency)}</span>
        </span>
      {:else}
        <span class="mono xamt">{money(e.amountSubunits, e.currency)}</span>
      {/if}
      <span class="xchev"><Icon name="chevR" size={16} /></span>
    </span>
  </button>
{/snippet}

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
    <header class="topbar gtop">
      <span class="gemoji" aria-hidden="true">{view.header.emoji}</span>
      <div class="tb-titles">
        <div class="tb-title">{tab === 'expenses' ? view.header.groupName : 'Balances'}</div>
        <div class="tb-sub">
          You're {view.me.name} · <button type="button" class="link" onclick={onNotMe}>Not you?</button>
        </div>
      </div>
    </header>

    {#if tab === 'expenses'}
      <div class="segwrap">
        <div class="seg" role="tablist" aria-label="Which expenses">
          {#each ['Active', 'All', 'Settled'] as const as opt (opt)}
            <button type="button" role="tab" aria-selected={sub === opt} onclick={() => (sub = opt)}>{opt}</button>
          {/each}
        </div>
      </div>
    {/if}

    <div class="gscroll">
      {#if view.readOnly}
        <div class="note note--blue gnote">
          {view.me.name} is linked to an Evenly account now, so changes happen in the app. This page still shows the group.
        </div>
      {/if}

      {#if tab === 'expenses'}
        {#if store.waiting.length > 0 && !view.readOnly}
          <!-- UnresolvedBillsSection: a tinted, collapsible to-do above the feed. -->
          <div class="picks">
            <button type="button" class="picks-h" aria-expanded={picksOpen} onclick={() => (picksOpen = !picksOpen)}>
              <Icon name="checkCircle" size={18} />
              <span class="grow">Needs your pick</span>
              <span class="picks-n">{store.waiting.length}</span>
              <Icon name={picksOpen ? 'chevU' : 'chevD'} size={18} />
            </button>
            {#if picksOpen}
              {#each store.waiting as e (e.id)}
                <a class="picks-r" href={`/b/${store.billToken(e)}`}>
                  <span class="grow">
                    <span class="picks-t">{e.title}</span>
                    <span class="picks-s">{store.name(e.payerUserId)} paid · {money(e.amountSubunits, e.currency)}</span>
                  </span>
                  <span class="btn btn--sm btn--tonal" style="width:auto">Pick items</span>
                </a>
              {/each}
            {/if}
          </div>
        {/if}

        {#if days.length === 0}
          <div class="empty">
            <span class="empty-ic"><Icon name="receipt" size={28} /></span>
            <div class="h2">{(view.expenses ?? []).length === 0 ? 'No expenses yet' : 'Nothing here'}</div>
            <p class="sub">
              {(view.expenses ?? []).length === 0
                ? 'Add the first expense and Evenly tracks who owes whom.'
                : `No ${sub.toLowerCase()} expenses in this group yet.`}
            </p>
            {#if !view.readOnly}
              <button type="button" class="btn" style="margin-top:16px" onclick={() => (adding = true)}>Add expense</button>
            {/if}
          </div>
        {/if}

        {#each days as day (day.date)}
          <div class="dayband">{dayLabel(day.date, today)}</div>
          {#each day.active as e (e.id)}
            {@render expenseRow(e)}
          {/each}
          {#if day.settled.length > 0}
            {#if sub === 'Settled'}
              {#each day.settled as e (e.id)}
                {@render expenseRow(e)}
              {/each}
            {:else}
              <button
                type="button"
                class="xrow xrow--tray"
                aria-expanded={!!settledOpen[day.date]}
                onclick={() => (settledOpen = { ...settledOpen, [day.date]: !settledOpen[day.date] })}
              >
                <span class="xtile" style="background:var(--blue-tint);color:var(--blue)"><Icon name="check" size={20} /></span>
                <span class="xmid"><span class="xt" style="color:var(--ink-2)">Settled ({day.settled.length})</span></span>
                <span class="xchev"><Icon name={settledOpen[day.date] ? 'chevU' : 'chevD'} size={16} /></span>
              </button>
              {#if settledOpen[day.date]}
                {#each day.settled as e (e.id)}
                  {@render expenseRow(e)}
                {/each}
              {/if}
            {/if}
          {/if}
        {/each}
        <div style="height:88px"></div>
      {:else}
        <!-- GroupBalancesTab -->
        {#if store.debts.iOwe.length === 0 && store.debts.owedToMe.length === 0}
          <div class="empty">
            <span class="empty-ic" style="color:var(--green)"><Icon name="checkCircle" size={28} /></span>
            <div class="h2">All settled here 🎉</div>
            <p class="sub">No one owes anyone right now.</p>
          </div>
        {:else}
          <div class="bal">
            {#each [{ label: 'You owe', rows: store.debts.iOwe, owedToMe: false }, { label: 'Owes you', rows: store.debts.owedToMe, owedToMe: true }] as section (section.label)}
              {#if section.rows.length > 0}
                <section>
                  <div class="sec">{section.label}</div>
                  <div class="balcards">
                    {#each section.rows as d (d.creditorUserId + d.debtorUserId + d.currency)}
                      {@const peer = section.owedToMe ? d.debtorUserId : d.creditorUserId}
                      {@const key = peer + d.currency}
                      {@const lines = linesFor(peer, section.owedToMe)}
                      <div class="evcard">
                        <button
                          type="button"
                          class="balrow"
                          aria-expanded={!!expanded[key]}
                          onclick={() => (expanded = { ...expanded, [key]: !expanded[key] })}
                        >
                          <Avatar name={store.name(peer)} size="sm" />
                          <span class="grow">
                            <span class="baln">{store.name(peer)}</span>
                            <span class="bals">
                              {lines.length === 0
                                ? 'Tap to see details'
                                : `${lines.length} ${lines.length === 1 ? 'expense' : 'expenses'} · tap to ${expanded[key] ? 'hide' : 'see'}`}
                            </span>
                          </span>
                          <span class="tag mono" class:tag--owed={section.owedToMe} class:tag--owe={!section.owedToMe}
                            >{money(d.amountSubunits, d.currency)}</span
                          >
                          <span style="color:var(--ink-3)"><Icon name={expanded[key] ? 'chevU' : 'chevD'} size={18} /></span>
                        </button>
                        {#if expanded[key]}
                          <div class="balbd">
                            {#each lines as l (l.id)}
                              <button type="button" class="balline" onclick={() => (openId = l.id)}>
                                <span class="grow">
                                  <span class="balline-t">{l.title}</span>
                                  <span class="balline-d">{shortDate(l.date)}</span>
                                </span>
                                <span class="mono balline-a">{money(l.subunits, l.currency)}</span>
                              </button>
                            {/each}
                            {#if !view.readOnly}
                              <button type="button" class="btn" style="margin:4px 0 8px" onclick={() => (settling = d)}>
                                {section.owedToMe ? `${store.name(peer)} paid me` : `Settle up with ${store.name(peer)}`}
                              </button>
                            {/if}
                          </div>
                        {/if}
                      </div>
                    {/each}
                  </div>
                </section>
              {/if}
            {/each}
          </div>
        {/if}
        <div style="height:24px"></div>
      {/if}
    </div>

    {#if tab === 'expenses' && !view.readOnly && days.length > 0}
      <!-- EvFab: 48px, 16px corners, plus + "Add expense". -->
      <button type="button" class="fab" onclick={() => (adding = true)}><Icon name="plus" size={20} />Add expense</button>
    {/if}

    <!-- EvBottomNav, iOS island: Expenses · Balances. -->
    <nav class="bnav" aria-label="Group sections">
      <button type="button" class:on={tab === 'expenses'} aria-current={tab === 'expenses' ? 'page' : undefined} onclick={() => (tab = 'expenses')}>
        <Icon name="receipt" size={22} /><span>Expenses</span>
      </button>
      <button type="button" class:on={tab === 'balances'} aria-current={tab === 'balances' ? 'page' : undefined} onclick={() => (tab = 'balances')}>
        <Icon name="swap" size={22} /><span>Balances</span>
        {#if store.debts.iOwe.length > 0}<i class="bdot" aria-label="You owe someone"></i>{/if}
      </button>
    </nav>

    {#if toast}<div class="gp-toast" role="status">{toast}</div>{/if}
  </div>
  {#if settling}
    <SettleSheet
      {store}
      forDebt={settling === 'pick' ? undefined : settling}
      onclose={() => (settling = null)}
      ondone={(m) => {
        settling = null;
        flash(m);
      }}
    />
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
  .gtop {
    padding-left: 12px;
    gap: 10px;
    border-bottom: none;
  }
  .gemoji {
    font-size: 22px;
    line-height: 1;
    flex: none;
  }
  .segwrap {
    flex: none;
    padding: 8px 16px;
  }
  .gscroll {
    flex: 1;
    overflow-y: auto;
    padding-bottom: calc(96px + env(safe-area-inset-bottom));
  }
  .gnote {
    margin: 8px 16px;
  }

  /* UnresolvedBillsSection */
  .picks {
    margin: 10px 16px 4px;
    border: 1px solid var(--blue-tint-2);
    border-radius: 14px;
    overflow: hidden;
  }
  .picks-h {
    display: flex;
    align-items: center;
    gap: 9px;
    width: 100%;
    padding: 11px 13px;
    background: var(--blue-tint);
    border: none;
    color: var(--blue);
    font: 600 14px var(--font);
    text-align: left;
    cursor: pointer;
  }
  .picks-n {
    background: var(--border-strong);
    color: var(--ink);
    border-radius: 6px;
    padding: 2px 7px;
    font-size: 12px;
  }
  .picks-r {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 13px;
    border-top: 1px solid var(--border);
    text-decoration: none;
    color: inherit;
  }
  .picks-t {
    display: block;
    font-size: 15px;
    font-weight: 600;
    color: var(--ink);
  }
  .picks-s {
    display: block;
    font-size: 13px;
    color: var(--ink-2);
    margin-top: 2px;
  }

  /* EvDayHeader: a full-width surface band. */
  .dayband {
    background: var(--surface);
    padding: 14px 16px 6px;
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.015em;
    color: var(--ink-2);
    margin-bottom: 4px;
  }

  /* ExpenseRowCard */
  .xrow {
    position: relative;
    display: flex;
    align-items: center;
    gap: 12px;
    width: calc(100% - 32px);
    margin: 4px 16px;
    padding: 12px 14px;
    background: var(--page);
    border: 1px solid var(--border);
    border-radius: 14px;
    overflow: hidden;
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .xrow:active {
    background: var(--surface);
  }
  .xrow.in::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 3px;
    background: var(--blue);
  }
  .xrow--tray {
    background: var(--surface);
  }
  .xtile {
    flex: none;
    width: 40px;
    height: 40px;
    border-radius: 11px;
    display: grid;
    place-items: center;
  }
  .xmid {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .xt {
    font-size: 15px;
    font-weight: 600;
    color: var(--ink);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .xs {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  }
  .xs-t {
    font-size: 13px;
    color: var(--ink-2);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .xend {
    flex: none;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .xchev {
    color: var(--ink-3);
    display: flex;
  }
  .stake {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    color: var(--ink);
  }
  .stake.owed {
    color: var(--green);
  }
  .stake-l {
    font-size: 11px;
    font-weight: 600;
  }
  .stake-v {
    font-size: 15px;
    font-weight: 600;
  }
  .xamt {
    font-size: 14px;
    color: var(--ink-2);
  }

  /* EvEmptyState */
  .empty {
    text-align: center;
    padding: 48px 24px;
  }
  .empty-ic {
    display: inline-grid;
    place-items: center;
    width: 56px;
    height: 56px;
    border-radius: 16px;
    background: var(--surface);
    color: var(--ink-2);
    margin-bottom: 14px;
  }

  /* GroupBalancesTab */
  .bal {
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }
  .balcards {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .balrow {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 12px 16px;
    background: none;
    border: none;
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .baln {
    display: block;
    font-size: 15px;
    font-weight: 600;
  }
  .bals {
    display: block;
    font-size: 13px;
    color: var(--ink-2);
  }
  .balbd {
    background: var(--surface);
    padding: 8px 16px;
  }
  .balline {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 8px 0;
    background: none;
    border: none;
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .balline-t {
    display: block;
    font-size: 14px;
  }
  .balline-d {
    display: block;
    font-size: 12px;
    color: var(--ink-3);
  }
  .balline-a {
    font-size: 14px;
    font-weight: 500;
    color: var(--ink-2);
  }

  /* EvFab */
  .fab {
    position: fixed;
    right: max(16px, calc(50vw - 240px + 16px));
    bottom: calc(92px + env(safe-area-inset-bottom));
    height: 48px;
    padding: 0 16px;
    border-radius: 16px;
    border: none;
    background: var(--blue);
    color: #fff;
    display: flex;
    align-items: center;
    gap: 7px;
    font: 600 15px var(--font);
    cursor: pointer;
    z-index: 5;
  }
  .fab:active {
    background: var(--blue-press);
  }

  /* EvBottomNav, iOS: a floating island with two items. */
  .bnav {
    position: fixed;
    left: 50%;
    transform: translateX(-50%);
    bottom: calc(8px + env(safe-area-inset-bottom));
    width: min(calc(100% - 24px), 456px);
    display: flex;
    gap: 6px;
    padding: 6px;
    background: var(--page);
    border: 1px solid var(--border);
    border-radius: 28px;
    box-shadow: 0 8px 24px rgba(11, 18, 32, 0.1);
    z-index: 6;
  }
  .bnav button {
    position: relative;
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 8px 0;
    border: none;
    border-radius: 22px;
    background: none;
    color: var(--ink-3);
    font: 600 11px var(--font);
    cursor: pointer;
  }
  .bnav button.on {
    background: var(--blue-tint);
    color: var(--blue);
  }
  .bdot {
    position: absolute;
    top: 8px;
    left: calc(50% + 10px);
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--blue);
  }

  .gp-toast {
    position: fixed;
    left: 50%;
    transform: translateX(-50%);
    bottom: calc(160px + env(safe-area-inset-bottom));
    background: var(--ink);
    color: #fff;
    padding: 11px 16px;
    border-radius: 12px;
    font-size: 14px;
    max-width: calc(100% - 32px);
    z-index: 30;
  }
</style>
