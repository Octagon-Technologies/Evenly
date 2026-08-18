/**
 * Dev-only walkthrough fixture behind `?mock`, mirroring `web/src/lib/mock.ts`.
 *
 * It exists for one reason: the states that matter most in this dashboard are the ones there is no
 * data for yet. Pre-launch the chart has four points and a run of genuine zero days, and "four data
 * points must look deliberate, not broken" (spec §3.2) is a judgement you can only make by looking at
 * it. Waiting for real signups to find out is how a thin state ships broken.
 *
 * `?mock=empty` for nobody at all, `?mock=thin` for four signups, `?mock` for a busy list.
 *
 * Every call site is guarded by `import.meta.env.DEV`, so none of this reaches a build.
 */

import type { WaitlistPage, WaitlistStats, Range } from './api.ts';

export type MockShape = 'busy' | 'thin' | 'empty';

export function mockShape(): MockShape | null {
  if (!import.meta.env.DEV) return null;
  const params = new URLSearchParams(location.search);
  if (!params.has('mock')) return null;
  const value = params.get('mock');
  return value === 'empty' ? 'empty' : value === 'thin' ? 'thin' : 'busy';
}

const SOURCES = ['hero', 'band'];

function isoDay(daysAgo: number): string {
  const date = new Date();
  date.setUTCHours(0, 0, 0, 0);
  date.setUTCDate(date.getUTCDate() - daysAgo);
  return date.toISOString().slice(0, 10);
}

function rows(count: number): WaitlistPage['rows'] {
  return Array.from({ length: count }, (_, i) => ({
    id: `mock-${i}`,
    email: `person${i + 1}@example.com`,
    source: i % 3 === 0 ? 'band' : 'hero',
    created_at: new Date(Date.now() - i * 3.7e7).toISOString(),
  }));
}

export function mockWaitlist(shape: MockShape, limit: number, offset: number): WaitlistPage {
  const total = shape === 'empty' ? 0 : shape === 'thin' ? 4 : 137;
  const all = rows(total);
  return { rows: all.slice(offset, offset + limit), total, limit, offset };
}

export function mockStats(shape: MockShape, range: Range): WaitlistStats {
  const span = range === '7' ? 7 : range === '90' ? 90 : range === 'all' ? 45 : 30;
  const days = Array.from({ length: span }, (_, i) => ({ day: isoDay(span - 1 - i), n: 0 }));
  const bySource: WaitlistStats['bySource'] = [];

  if (shape === 'thin') {
    // Four points scattered across the window, most days genuinely zero.
    for (const [index, n] of [[3, 1], [9, 2], [10, 1], [span - 2, 0]] as [number, number][]) {
      if (index < span && n > 0) {
        days[index].n = n;
        bySource.push({ day: days[index].day, source: 'hero', n });
      }
    }
  } else if (shape === 'busy') {
    days.forEach((day, i) => {
      // Deterministic, not random: a fixture that redraws differently on every reload is useless for
      // judging whether a layout is stable.
      const n = Math.max(0, Math.round(4 + 5 * Math.sin(i / 3.1) + (i % 7 === 0 ? 6 : 0)));
      day.n = n;
      if (n > 0) {
        const split = Math.ceil(n * 0.6);
        bySource.push({ day: day.day, source: SOURCES[0], n: split });
        if (n - split > 0) bySource.push({ day: day.day, source: SOURCES[1], n: n - split });
      }
    });
  }

  const windowTotal = days.reduce((sum, d) => sum + d.n, 0);
  const baseline = shape === 'busy' ? 42 : 0;
  return {
    from: `${days[0]?.day ?? isoDay(0)}T00:00:00Z`,
    baseline,
    total: baseline + windowTotal,
    days: shape === 'empty' ? [] : days,
    bySource,
  };
}
