<script lang="ts">
  /**
   * The waitlist view (spec §3): the chart, then the list, then CSV export.
   *
   * **No delete, deliberately** (§3.1). An unsubscribe flow needs somewhere for the person to find
   * it, and before the first email is sent there is no such surface, so a delete button here would be
   * one only the owner can press on behalf of someone with no way to ask. Revisit alongside the first
   * campaign's unsubscribe link.
   */
  import { api, ApiError, type Range, type WaitlistPage, type WaitlistStats } from '../lib/api.ts';
  import GrowthChart from '../components/GrowthChart.svelte';

  const PAGE = 50;
  const RANGES: { value: Range; label: string }[] = [
    { value: '7', label: '7d' },
    { value: '30', label: '30d' },
    { value: '90', label: '90d' },
    { value: 'all', label: 'All' },
  ];

  let range = $state<Range>('30');
  let stats = $state<WaitlistStats | null>(null);
  let statsError = $state('');

  let query = $state('');
  let offset = $state(0);
  let page = $state<WaitlistPage | null>(null);
  let listError = $state('');
  let loading = $state(true);

  let exporting = $state(false);
  let exportError = $state('');

  /** The search box types faster than the round trip. Without this every keystroke is a query and the
   *  answers can land out of order, which shows the wrong rows for the text on screen. */
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  /** Monotonic, so a slow earlier response cannot overwrite a newer one. */
  let requestSeq = 0;

  function message(e: unknown): string {
    const code = e instanceof ApiError ? e.code : 'SERVER';
    if (code === 'NETWORK') return 'Could not reach the server. Check your connection.';
    if (code === 'FORBIDDEN') return 'This account is no longer on the allowlist.';
    return 'The server did not answer. Try again in a moment.';
  }

  async function loadStats() {
    statsError = '';
    try {
      stats = await api.waitlistStats(range);
    } catch (e) {
      statsError = message(e);
    }
  }

  async function loadList() {
    const seq = ++requestSeq;
    loading = true;
    listError = '';
    try {
      const result = await api.waitlist(query.trim(), PAGE, offset);
      if (seq !== requestSeq) return;
      page = result;
    } catch (e) {
      if (seq !== requestSeq) return;
      listError = message(e);
    } finally {
      if (seq === requestSeq) loading = false;
    }
  }

  function onSearch(value: string) {
    query = value;
    offset = 0;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(loadList, 250);
  }

  function setRange(value: Range) {
    range = value;
    stats = null;
    void loadStats();
  }

  function goto(nextOffset: number) {
    offset = Math.max(nextOffset, 0);
    void loadList();
  }

  async function exportCsv() {
    if (exporting) return;
    exporting = true;
    exportError = '';
    try {
      const csv = await api.waitlistCsv();
      // Built in the page rather than linked: a browser cannot put an Authorization header on a
      // navigation, so an <a href> straight at the endpoint would arrive unauthenticated.
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `evenly-waitlist-${new Date().toISOString().slice(0, 10)}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      exportError = message(e);
    } finally {
      exporting = false;
    }
  }

  function when(iso: string): string {
    return new Date(iso).toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    });
  }

  const shown = $derived(page ? `${page.total.toLocaleString()} total` : '');
  const hasPrev = $derived(offset > 0);
  const hasNext = $derived(page ? offset + page.rows.length < page.total : false);

  void loadStats();
  void loadList();
</script>

<h1>Waitlist</h1>
<p class="adm-sub">Everyone who asked for early access, newest first.</p>

<div class="adm-card" style="margin-top:20px">
  <div class="adm-card-head">
    <h2>Growth</h2>
    <div class="adm-seg">
      {#each RANGES as option}
        <button class:adm-on={range === option.value} onclick={() => setRange(option.value)}>
          {option.label}
        </button>
      {/each}
    </div>
  </div>

  {#if statsError}
    <div class="adm-error">{statsError}</div>
  {:else if !stats}
    <div class="adm-empty"><div class="adm-spin" style="margin:0 auto"></div></div>
  {:else}
    <GrowthChart {stats} />
  {/if}
</div>

<div class="adm-card">
  <div class="adm-card-head">
    <h2>Signups {#if shown}<span class="adm-tag">{shown}</span>{/if}</h2>
    <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
      <input
        type="search"
        placeholder="Search by email"
        value={query}
        oninput={(e) => onSearch(e.currentTarget.value)}
      />
      <button onclick={exportCsv} disabled={exporting}>
        {exporting ? 'Preparing…' : 'Export CSV'}
      </button>
    </div>
  </div>

  {#if exportError}
    <div class="adm-error" style="margin-bottom:12px">{exportError}</div>
  {/if}

  {#if listError}
    <div class="adm-error">{listError}</div>
  {:else if loading && !page}
    <div class="adm-empty"><div class="adm-spin" style="margin:0 auto"></div></div>
  {:else if page && page.rows.length === 0}
    <p class="adm-empty">
      {query.trim() ? `No address matches "${query.trim()}".` : 'Nobody has signed up yet.'}
    </p>
  {:else if page}
    <div class="adm-table-wrap">
      <table>
        <thead>
          <tr><th>Email</th><th>Source</th><th>Signed up</th></tr>
        </thead>
        <tbody>
          {#each page.rows as row (row.id)}
            <tr>
              <td class="adm-email">{row.email}</td>
              <td><span class="adm-tag">{row.source ?? 'not stamped'}</span></td>
              <td class="adm-when">{when(row.created_at)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>

    {#if hasPrev || hasNext}
      <div style="display:flex;gap:10px;align-items:center;justify-content:flex-end;margin-top:16px">
        <span class="adm-sub">{offset + 1}&ndash;{offset + page.rows.length} of {page.total.toLocaleString()}</span>
        <button onclick={() => goto(offset - PAGE)} disabled={!hasPrev}>Previous</button>
        <button onclick={() => goto(offset + PAGE)} disabled={!hasNext}>Next</button>
      </div>
    {/if}
  {/if}
</div>
