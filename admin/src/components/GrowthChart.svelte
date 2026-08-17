<script lang="ts">
  /**
   * Daily signups as stacked bars, cumulative total as a line, on the same artboard (spec §3.2).
   * Both, because neither alone answers "how is this going": the bars show whether a campaign landed,
   * the line shows whether the list compounds.
   *
   * Hand-drawn SVG rather than a charting library. One chart with one shape does not justify a
   * runtime dependency, and the sibling bundle's rule of zero of them is a good habit to keep.
   *
   * The aggregation is already done: the server returns dense days (zeros included) and a `baseline`
   * for where the cumulative line starts. Nothing here counts rows.
   */
  import type { WaitlistStats } from '../lib/api.ts';

  let { stats }: { stats: WaitlistStats } = $props();

  const W = 720;
  const H = 240;
  const PAD = { top: 16, right: 46, bottom: 28, left: 40 };
  const PLOT_W = W - PAD.left - PAD.right;
  const PLOT_H = H - PAD.top - PAD.bottom;

  /** Distinct, ordered by volume so the biggest source is the base of every stack and the legend
   *  reads in the order the eye meets the colours. */
  const sources = $derived.by(() => {
    const totals = new Map<string, number>();
    for (const row of stats.bySource) totals.set(row.source, (totals.get(row.source) ?? 0) + row.n);
    return [...totals.entries()].sort((a, b) => b[1] - a[1]).map(([source]) => source);
  });

  const SOURCE_COLOR = ['#2563eb', '#7c3aed', '#0ea5e9', '#f59e0b', '#10b981', '#64748b'];
  function colorFor(source: string): string {
    const index = sources.indexOf(source);
    return SOURCE_COLOR[index < 0 ? SOURCE_COLOR.length - 1 : index % SOURCE_COLOR.length];
  }

  const SOURCE_LABEL: Record<string, string> = {
    hero: 'Hero form',
    band: 'Footer band',
    unknown: 'Not stamped',
  };
  const label = (source: string) => SOURCE_LABEL[source] ?? source;

  /** day -> source -> n, so a stack can be built without rescanning the sparse array per bar. */
  const bySourceByDay = $derived.by(() => {
    const map = new Map<string, Map<string, number>>();
    for (const row of stats.bySource) {
      const day = map.get(row.day) ?? new Map<string, number>();
      day.set(row.source, (day.get(row.source) ?? 0) + row.n);
      map.set(row.day, day);
    }
    return map;
  });

  const cumulative = $derived.by(() => {
    let running = stats.baseline;
    return stats.days.map((d) => (running += d.n));
  });

  const maxDaily = $derived(Math.max(1, ...stats.days.map((d) => d.n)));
  const maxCumulative = $derived(Math.max(1, ...cumulative));
  const windowTotal = $derived(stats.days.reduce((sum, d) => sum + d.n, 0));

  /** Bars get a ceiling as well as a share of the width: at four data points a full-width bar reads
   *  as a broken layout rather than a thin week, and thin windows are the normal pre-launch case. */
  const step = $derived(PLOT_W / Math.max(stats.days.length, 1));
  const barW = $derived(Math.min(Math.max(step * 0.62, 2), 44));

  /**
   * The bar axis is counts of people, so every gridline has to be a whole number. Scaling straight to
   * `maxDaily` and dividing by four gives "2, 2, 1, 1, 0" on a quiet week, which reads as a rendering
   * bug rather than a small number. So: pick a 1/2/5-times-a-power-of-ten step near max/4, then round
   * the top of the axis up to a multiple of it.
   */
  function niceStep(max: number): number {
    const raw = Math.max(max / 4, 1);
    const magnitude = 10 ** Math.floor(Math.log10(raw));
    const normalized = raw / magnitude;
    const factor = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
    return Math.max(1, factor * magnitude);
  }

  const yStep = $derived(niceStep(maxDaily));
  const yTop = $derived(Math.max(yStep, Math.ceil(maxDaily / yStep) * yStep));

  const x = (i: number) => PAD.left + step * (i + 0.5);
  const yBar = (value: number) => PAD.top + PLOT_H - (value / yTop) * PLOT_H;
  const yLine = (value: number) => PAD.top + PLOT_H - (value / maxCumulative) * PLOT_H;

  const linePath = $derived(
    cumulative.map((value, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)} ${yLine(value).toFixed(1)}`).join(' '),
  );

  /** At most six date labels whatever the range, so 90 days does not render an unreadable smear. */
  const xTicks = $derived.by(() => {
    const count = stats.days.length;
    if (count === 0) return [] as { i: number; text: string }[];
    const every = Math.max(1, Math.ceil(count / 6));
    const ticks: { i: number; text: string }[] = [];
    for (let i = 0; i < count; i += every) {
      ticks.push({ i, text: shortDate(stats.days[i].day) });
    }
    return ticks;
  });

  function shortDate(iso: string): string {
    return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      timeZone: 'UTC',
    });
  }

  const yTicks = $derived(
    Array.from({ length: Math.round(yTop / yStep) + 1 }, (_, i) => i * yStep),
  );

  function stackFor(day: string): { source: string; from: number; to: number }[] {
    const counts = bySourceByDay.get(day);
    if (!counts) return [];
    let from = 0;
    return sources
      .filter((source) => (counts.get(source) ?? 0) > 0)
      .map((source) => {
        const to = from + (counts.get(source) ?? 0);
        const segment = { source, from, to };
        from = to;
        return segment;
      });
  }
</script>

<div class="adm-stat-row">
  <div class="adm-stat"><b>{stats.total.toLocaleString()}</b><span>On the list</span></div>
  <div class="adm-stat"><b>{windowTotal.toLocaleString()}</b><span>In this range</span></div>
</div>

{#if stats.days.length === 0}
  <p class="adm-empty">No signups yet. The chart appears with the first one.</p>
{:else}
  <svg class="adm-chart" viewBox="0 0 {W} {H}" role="img"
       aria-label="Daily signups as bars with the cumulative total as a line">
    <!-- gridlines + left axis (daily) -->
    {#each yTicks as tick}
      {@const y = yBar(tick)}
      <line x1={PAD.left} x2={W - PAD.right} y1={y} y2={y} stroke="var(--adm-line)" stroke-width="1" />
      <text x={PAD.left - 8} y={y + 4} text-anchor="end" font-size="10" fill="var(--adm-ink-faint)">
        {Math.round(tick)}
      </text>
    {/each}

    <!-- right axis (cumulative), labelled at the two ends only: the line's shape is the message and
         a second full scale would just be clutter -->
    <text x={W - PAD.right + 8} y={yLine(maxCumulative) + 4} font-size="10" fill="var(--adm-ink-faint)">
      {maxCumulative}
    </text>
    <text x={W - PAD.right + 8} y={PAD.top + PLOT_H + 4} font-size="10" fill="var(--adm-ink-faint)">
      {stats.baseline}
    </text>

    <!-- bars -->
    {#each stats.days as day, i}
      {#each stackFor(day.day) as segment}
        <rect
          x={x(i) - barW / 2}
          y={yBar(segment.to)}
          width={barW}
          height={Math.max(yBar(segment.from) - yBar(segment.to), 1)}
          rx={barW > 8 ? 2 : 0}
          fill={colorFor(segment.source)}
        >
          <title>{shortDate(day.day)} · {label(segment.source)} · {segment.to - segment.from}</title>
        </rect>
      {/each}
      {#if day.n === 0}
        <!-- A zero day is drawn as a hairline on the axis rather than nothing at all. Pre-launch most
             days are zero, and a gap where a mark should be reads as missing data. -->
        <rect x={x(i) - barW / 2} y={PAD.top + PLOT_H - 1} width={barW} height="1" fill="var(--adm-line)">
          <title>{shortDate(day.day)} · no signups</title>
        </rect>
      {/if}
    {/each}

    <!-- cumulative line, over the bars -->
    <path d={linePath} fill="none" stroke="var(--adm-ink)" stroke-width="2"
          stroke-linejoin="round" stroke-linecap="round" opacity="0.75" />
    {#each cumulative as value, i}
      <circle cx={x(i)} cy={yLine(value)} r={stats.days.length > 45 ? 0 : 2.5}
              fill="var(--adm-paper)" stroke="var(--adm-ink)" stroke-width="1.5" opacity="0.75">
        <title>{shortDate(stats.days[i].day)} · {value} total</title>
      </circle>
    {/each}

    <!-- x labels -->
    {#each xTicks as tick}
      <text x={x(tick.i)} y={H - 8} text-anchor="middle" font-size="10" fill="var(--adm-ink-faint)">
        {tick.text}
      </text>
    {/each}
  </svg>

  <div class="adm-legend">
    {#each sources as source}
      <span><i class="adm-swatch" style="background:{colorFor(source)}"></i>{label(source)}</span>
    {/each}
    <span><i class="adm-swatch" style="background:var(--adm-ink);opacity:0.75"></i>Cumulative</span>
  </div>

  {#if windowTotal === 0}
    <p class="adm-sub" style="margin-top:10px">
      No signups in this range. The line holds at {stats.baseline}.
    </p>
  {/if}
{/if}
