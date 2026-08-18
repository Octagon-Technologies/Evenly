/**
 * The landing hero's card loop, at the top of `/waitlist`.
 *
 * Every constant here was measured off a screen capture of the reference site rather than chosen,
 * and `design/landing-hero-cards.html` is the working that produced them. Four findings drive the
 * whole model, and none of them are guessable from looking at the finished effect:
 *
 *  - Lanes are not parallel. They radiate from a vanishing point at the phone, and a card's offset
 *    at the device is `CONVERGE` times its offset at the screen edge.
 *  - Scale is perspective, not emphasis: inbound cards shrink as they recede into the phone,
 *    outbound cards grow as they come back toward the viewer.
 *  - Velocity ramps linearly and never eases out. A power curve starts at zero velocity, which
 *    reads as a crawl and then a lurch; this integrates a straight ramp instead, so `VRATIO` is an
 *    exact ratio between the two ends rather than a feel.
 *  - Cards are PAIRED. An expense flies in, waits `DWELL` behind the phone, and its result leaves
 *    from the point the expense went in at. Only one card is ever hidden.
 *
 * Returns its own teardown, so Svelte's `onMount` can hand it straight back.
 */

const ICON: Record<string, string> = {
  car: '<path d="M5 17h14M6.5 17V11l1.8-4.2A2 2 0 0 1 10.1 5.5h3.8a2 2 0 0 1 1.8 1.3L17.5 11v6M6 11h12M8 20v-3M16 20v-3"/>',
  fork: '<path d="M3 2v7a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2V2M6.5 2v20M21 15V2a5 5 0 0 0-5 5v6a2 2 0 0 0 2 2h3Zm0 0v7"/>',
  house: '<path d="M3.5 10.5 12 4l8.5 6.5V20a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-9.5ZM9.5 21v-6h5v6"/>',
  cart: '<path d="M3 4h2.2l2.3 11.2a1.5 1.5 0 0 0 1.5 1.2h8.3a1.5 1.5 0 0 0 1.5-1.2L20.5 8H6M9.5 20.5h.01M16.5 20.5h.01"/>',
  glass: '<path d="M4 4h16l-7 8v7M13 19h4M13 19H9"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  split: '<path d="M12 3v18M5 8 3 12l2 4M19 8l2 4-2 4"/>',
  list: '<path d="M4 7h16M4 12h10M4 17h7M17.5 14.5 19 16l3-3.2"/>',
  tick: '<path d="m4 12 5.5 5.5L20 7"/>',
};

const tile = (bg: string, stroke: string, icon: keyof typeof ICON | string, w = 1.85) =>
  `<div class="tile" style="background:${bg}"><svg viewBox="0 0 24 24" stroke="${stroke}" fill="none" ` +
  `stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round">${ICON[icon]}</svg></div>`;
const av = (bg: string, ch: string) => `<div class="av" style="background:${bg}">${ch}</div>`;
const amt = (text: string, tone = '') => `<div class="amt${tone ? ' ' + tone : ''}">${text}</div>`;
const head = (left: string, title: string, sub: string, right: string) =>
  `<div class="head">${left}<div class="who"><b>${title}</b><span>${sub}</span></div>${right}</div>`;

type Row = [string, string] | [string, string, 1] | '-';
const receipt = (cap: string, rows: Row[]) => {
  const body = rows
    .map((r) =>
      r === '-'
        ? '<div class="hr"></div>'
        : `<div class="r${r[2] ? ' tot' : ''}"><span>${r[0]}</span><b>${r[1]}</b></div>`,
    )
    .join('');
  return `<div><div class="paper"><div class="cap">${cap}</div>${body}</div><div class="torn"></div></div>`;
};

interface Pair {
  /** ms from this pair launching to the next one. Shorter than a crossing means they share the stage. */
  gap: number;
  /** y offset at the screen edge, entering and leaving. Sign picks the lane, above or below. */
  edgeIn: number;
  edgeOut: number;
  into: string;
  outof: string;
}

const PAIRS: Pair[] = [
  {
    gap: 3750, edgeIn: -196, edgeOut: 212,
    into:
      head(tile('#F8EFEE', '#A65A5A', 'fork'), 'Tamarind Dhow', '9 items scanned', amt('$184.20')) +
      receipt('TAMARIND DHOW', [
        ['GRILLED SNAPPER', '42.00'], ['COCONUT RICE', '14.50'], ['CALAMARI', '28.00'],
        ['PASSION JUICE x2', '11.00'], ['CHAPATI BASKET', '8.00'], ['PRAWN CURRY', '60.00'],
        '-', ['SERVICE &amp; TAX', '20.70'], ['TOTAL', '184.20', 1],
      ]),
    outof: head(av('#A65A5A', 'D'), 'Diego claimed 2 items', 'Passion juice, service', amt('$12.24')),
  },
  {
    gap: 3900, edgeIn: 245, edgeOut: -229,
    into: head(tile('#0B1220', '#fff', 'car'), 'Uber', 'Fri, 11:40 pm &middot; 4 riders', amt('$32.80')),
    outof: head(tile('#EFF4FF', '#3762E3', 'split'), 'Split 4 ways', 'Uber to Diani', amt('$8.20')),
  },
  {
    gap: 3900, edgeIn: -212, edgeOut: 196,
    into: head(tile('#EFEFF7', '#6B6FA8', 'glass'), "Petley's Bar", 'Bar tab &middot; Sat, 11:58 pm', amt('$54.00')),
    outof: head(av('#2F7D74', 'M'), 'Maya owes you', "Her round at Petley's", amt('$18.00', 'owed')),
  },
  {
    gap: 6100, edgeIn: 229, edgeOut: 245,
    into: head(tile('#EFF4FF', '#3762E3', 'house'), 'Lamu Beach House', '3 nights &middot; 4 guests', amt('$420.00')),
    outof: head(av('#6B6FA8', 'S'), 'Sam settled up', 'Lamu Beach House',
      '<div><span class="pill pill--done">Paid $105.00</span></div>'),
  },
  {
    gap: 3800, edgeIn: -180, edgeOut: -245,
    into:
      head(tile('#F0F4E9', '#3F6212', 'cart'), 'Chandarana Market', '14 items scanned', amt('$63.40')) +
      receipt('CHANDARANA MARKET', [
        ['COFFEE 500G', '9.80'], ['MANGOES x6', '4.20'], ['ICE, 2 BAGS', '3.00'],
        ['CHARCOAL', '6.50'], ['BREAD x3', '4.50'], ['WATER 5L x4', '7.20'],
        '-', ['8 MORE ITEMS', '24.30'], ['VAT', '3.90'], ['TOTAL', '63.40', 1],
      ]),
    outof: head(tile('#EFF4FF', '#3762E3', 'list'), '6 items unclaimed', 'Chandarana Market',
      '<div><span class="pill pill--claim">Claim yours</span></div>'),
  },
  {
    gap: 13450, edgeIn: 180, edgeOut: -98,
    into: head(tile('#F1F3F6', '#7A8496', 'plus'), 'Boat to Manda', 'Added by Maya &middot; split evenly', amt('$45.00')),
    outof: head(tile('#E7F4EC', '#16A34A', 'tick', 2.4), 'Lamu Trip is all square', '12 expenses, 4 people',
      amt('$0.00', 'owed')),
  },
];

const CARD_W = 268;
const HALF = CARD_W / 2;
const BASE = 0.92;      // scale at the phone: 268 * 0.92 = 247, inside the 352px phone, so it occludes
const STEPS = 34;
const CONVERGE = 0.42;  // lane offset at the device over its offset at the edge
const FAR_IN = 1.18;    // entry scale; measured 35px tall shrinking to 29 on approach
const FAR_OUT = 1.42;   // exit scale; measured 45px growing to 62 on departure
const CROSS = 2812;     // 1.2x the restaurant receipt's speed, applied to every card
const DWELL = 900;      // hidden behind the phone, one card at a time
const VRATIO = 1.25;    // velocity at the phone end over velocity at the edge
const K = VRATIO - 1;

const easeIn = (u: number) => (u + (K * u * u) / 2) / (1 + K / 2);
const easeOut = (u: number) => (VRATIO * u - (K * u * u) / 2) / (1 + K / 2);

interface Pt { x: number; y: number }
const bez = (p0: Pt, p1: Pt, p2: Pt, t: number): Pt => {
  const m = 1 - t;
  return {
    x: m * m * p0.x + 2 * m * t * p1.x + t * t * p2.x,
    y: m * m * p0.y + 2 * m * t * p1.y + t * t * p2.y,
  };
};

interface Spec { dir: 'in' | 'out'; edge: number; near: number; html: string }

export function startHeroLoop(stage: HTMLElement, phone: HTMLElement): () => void {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return () => {};

  let index = 0;
  let stopped = false;
  let launchTimer: number | undefined;
  const dwellTimers: number[] = [];
  const live = new Set<{ anims: Animation[]; lane: HTMLElement }>();

  function frames(spec: Spec, cardH: number) {
    const W = stage.clientWidth;
    // the phone's own centre, which is not the stage's: it sits 14px from the top
    const vpY = phone.offsetTop + phone.offsetHeight / 2;
    const hidX = W / 2 - HALF;
    const edgeX = (CARD_W * BASE) / 2 + 60;

    const inbound = spec.dir === 'in';
    const p0 = inbound
      ? { x: -edgeX - HALF, y: vpY + spec.edge - cardH / 2 }
      : { x: hidX, y: vpY + spec.near - cardH / 2 };
    const p2 = inbound
      ? { x: hidX, y: vpY + spec.near - cardH / 2 }
      : { x: W + edgeX - HALF, y: vpY + spec.edge - cardH / 2 };
    const s0 = inbound ? BASE * FAR_IN : BASE;
    const s1 = inbound ? BASE : BASE * FAR_OUT;
    const ctrl = { x: (p0.x + p2.x) / 2, y: p0.y + (p2.y - p0.y) * 0.62 };

    const out: { offset: number; transform: string; sc: string }[] = [];
    for (let s = 0; s <= STEPS; s++) {
      const lin = s / STEPS;
      const t = inbound ? easeIn(lin) : easeOut(lin);
      const pt = bez(p0, ctrl, p2, t);
      out.push({
        offset: lin,
        transform: `translate(${pt.x.toFixed(1)}px,${pt.y.toFixed(1)}px)`,
        sc: (s0 + (s1 - s0) * t).toFixed(4),
      });
    }
    return out;
  }

  function spawn(spec: Spec, done?: () => void) {
    const lane = document.createElement('div');
    lane.className = 'site-lh-lane';
    lane.style.transform = 'translate(-9999px,0)'; // park it offstage while we measure
    lane.innerHTML = `<div class="spin"><div class="site-lh-card">${spec.html}</div></div>`;
    stage.appendChild(lane);

    const spin = lane.firstElementChild as HTMLElement;
    const kf = frames(spec, spin.getBoundingClientRect().height);
    const opts: KeyframeAnimationOptions = { duration: CROSS, easing: 'linear', fill: 'forwards' };
    const move = lane.animate(kf.map((f) => ({ offset: f.offset, transform: f.transform })), opts);
    const zoom = spin.animate(kf.map((f) => ({ offset: f.offset, transform: `scale(${f.sc})` })), opts);

    const rec = { anims: [move, zoom], lane };
    live.add(rec);
    move.onfinish = () => {
      lane.remove();
      live.delete(rec);
      done?.();
    };
  }

  function runPair(pair: Pair) {
    const near = pair.edgeIn * CONVERGE;
    spawn({ dir: 'in', edge: pair.edgeIn, near, html: pair.into }, () => {
      if (stopped) return;
      dwellTimers.push(
        window.setTimeout(() => {
          if (!stopped) spawn({ dir: 'out', edge: pair.edgeOut, near, html: pair.outof });
        }, DWELL),
      );
    });
  }

  function launch() {
    if (stopped) return;
    // A hidden tab throttles animations but not timers, so without this the scheduler would keep
    // spawning cards that never advance and they would pile up behind the phone.
    if (document.hidden) {
      launchTimer = window.setTimeout(launch, 400);
      return;
    }
    const pair = PAIRS[index % PAIRS.length];
    index += 1;
    runPair(pair);
    launchTimer = window.setTimeout(launch, pair.gap);
  }

  function clearTimers() {
    if (launchTimer !== undefined) window.clearTimeout(launchTimer);
    while (dwellTimers.length) window.clearTimeout(dwellTimers.pop()!);
  }

  function onVisibility() {
    if (document.hidden) {
      clearTimers();
      live.forEach((rec) => rec.anims.forEach((a) => a.finish()));
    } else if (!stopped) {
      clearTimers();
      launchTimer = window.setTimeout(launch, 300);
    }
  }

  document.addEventListener('visibilitychange', onVisibility);
  launch();

  return () => {
    stopped = true;
    clearTimers();
    document.removeEventListener('visibilitychange', onVisibility);
    live.forEach((rec) => {
      rec.anims.forEach((a) => a.cancel());
      rec.lane.remove();
    });
    live.clear();
  };
}
