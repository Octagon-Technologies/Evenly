<script lang="ts">
  /**
   * The pre-launch page at `/waitlist`. Approved mockup: `design/waitlist-bevel.html`, which
   * supersedes `design/waitlist-final.html` (direction A3, the previous design of this same page).
   *
   * Three rules this page follows that the rest of the marketing site does not, all because it
   * exists to do exactly one thing:
   *  - No nav and no store badges. Every other link is a way to leave without signing up, and the
   *    store links would 404 until launch anyway. The sticky join pill is the only exception: it
   *    goes to the form on this page, so it cannot be an exit.
   *  - The email field appears twice and is the only control on the page.
   *  - It drops the site's Fraunces serif. Home, privacy and terms stay editorial; this page is the
   *    system in `marketing.css` under "§2 onwards", and the divergence is deliberate. See the
   *    comment there before harmonising anything back.
   *
   * The submit posts to the `waitlist` edge function, never to PostgREST: this bundle ships no
   * Supabase client and no anon key (WEB_CLAIM_SPEC §4.1), and `waitlist_signups` is RLS-denied to
   * everyone but that function's service key.
   */
  import { onMount } from 'svelte';
  import Footer from './Footer.svelte';
  import { startHeroLoop } from './heroLoop.ts';

  const ENDPOINT = import.meta.env.VITE_WAITLIST_URL as string | undefined;

  type Phase = 'idle' | 'sending' | 'done';

  let phase = $state<Phase>('idle');
  let email = $state('');
  let error = $state('');

  /** Mirrors the edge function's check so an obvious typo is caught without a round trip. The server
   *  is still the authority; this only saves the user a second of waiting to be told the same thing. */
  function looksLikeEmail(value: string): boolean {
    return /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test(value.trim());
  }

  async function submit(event: SubmitEvent, source: string) {
    event.preventDefault();
    if (phase === 'sending') return;

    if (!looksLikeEmail(email)) {
      error = 'That address is missing something. Check it and try again.';
      return;
    }
    if (!ENDPOINT) {
      error = 'Signup is not wired up yet. Try again shortly.';
      return;
    }

    error = '';
    phase = 'sending';
    try {
      const response = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim(), source }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      phase = 'done';
    } catch {
      phase = 'idle';
      error = 'That did not go through. Check your connection and try again.';
    }
  }

  function toSignup() {
    document.getElementById('site-wl-signup')?.scrollIntoView({ behavior: 'smooth' });
  }


  /** The phone's balance rows. Data rather than markup so the row template exists once. */
  const BALANCES = [
    { name: 'Maya',  initial: 'M', colour: '#2f7d74', state: 'owes you',  amount: '$62.10', tone: 'owed' },
    { name: 'Diego', initial: 'D', colour: '#a65a5a', state: 'owes you',  amount: '$44.30', tone: 'owed' },
    { name: 'Sam',   initial: 'S', colour: '#6b6fa8', state: 'owes you',  amount: '$22.00', tone: 'owed' },
    { name: 'Ali',   initial: 'A', colour: '#7a8496', state: 'all square', amount: '$0.00',  tone: 'flat' },
  ] as const;

  let stageEl = $state<HTMLElement | null>(null);
  let phoneEl = $state<HTMLElement | null>(null);

  onMount(() => {
    if (!stageEl || !phoneEl) return;
    return startHeroLoop(stageEl, phoneEl);
  });

  /** Category glyphs, lifted from `design/landing-hero-cards.html` so the marketing art and the
   *  hero deck cannot drift into two different icon sets. */
  const ICON = {
    fork: 'M3 2v7a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2V2M6.5 2v20M21 15V2a5 5 0 0 0-5 5v6a2 2 0 0 0 2 2h3Zm0 0v7',
    glass: 'M4 4h16l-7 8v7M13 19h4M13 19H9',
    house: 'M3.5 10.5 12 4l8.5 6.5V20a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-9.5ZM9.5 21v-6h5v6',
    car: 'M5 17h14M6.5 17V11l1.8-4.2A2 2 0 0 1 10.1 5.5h3.8a2 2 0 0 1 1.8 1.3L17.5 11v6M6 11h12M8 20v-3M16 20v-3',
    cart: 'M3 4h2.2l2.3 11.2a1.5 1.5 0 0 0 1.5 1.2h8.3a1.5 1.5 0 0 0 1.5-1.2L20.5 8H6M9.5 20.5h.01M16.5 20.5h.01',
    split: 'M12 3v18M5 8 3 12l2 4M19 8l2 4-2 4',
    list: 'M4 7h16M4 12h10M4 17h7',
    listTick: 'M4 7h16M4 12h10M4 17h7M17.5 14.5 19 16l3-3.2',
  } as const;

  /** Person colours are the muted set from the hero deck, not saturated tailwind hues. */
  const WHO = { diego: '#2f7d74', aisha: '#a65a5a', mara: '#6b6fa8' } as const;

  type Chip =
    | { kind: 'claim' }
    | { kind: 'ghost'; label: string }
    | { kind: 'me'; initials: string }
    | { kind: 'person'; name: string; initials: string; colour: string };

  type Line = { qty: number; name: string; amount: string; mine?: boolean; chips: Chip[] };

  /** Two states of the same bill. The first is what a guest sees before claiming, the second after,
   *  which is why §3 and §4 can both show the claim screen without showing the same picture twice. */
  const BILL_BEFORE: { who: string; claimed: string; people: string; pct: number; lines: Line[] } = {
    who: 'Diego',
    claimed: '£56.50 of £97.31 claimed',
    people: '2 of 4 people',
    pct: 58,
    lines: [
      { qty: 2, name: 'Carbonara', amount: '36.00', chips: [{ kind: 'claim' }, { kind: 'ghost', label: '2 left' }] },
      { qty: 1, name: 'Margherita', amount: '14.50', chips: [{ kind: 'person', name: 'Diego', initials: 'DG', colour: WHO.diego }] },
      {
        qty: 3,
        name: 'Aperol spritz',
        amount: '27.00',
        mine: true,
        chips: [
          { kind: 'me', initials: 'DG' },
          { kind: 'person', name: 'Aisha', initials: 'AK', colour: WHO.aisha },
          { kind: 'ghost', label: '1 left' },
        ],
      },
      { qty: 1, name: 'Tiramisu', amount: '9.00', chips: [{ kind: 'person', name: 'Aisha', initials: 'AK', colour: WHO.aisha }] },
    ],
  };

  const BILL_AFTER: typeof BILL_BEFORE = {
    who: 'Sam',
    claimed: '£75.90 of £97.31 claimed',
    people: '3 of 4 people',
    pct: 78,
    lines: [
      { qty: 2, name: 'Carbonara', amount: '36.00', mine: true, chips: [{ kind: 'me', initials: 'SM' }, { kind: 'ghost', label: '1 left' }] },
      { qty: 1, name: 'Margherita', amount: '14.50', chips: [{ kind: 'person', name: 'Diego', initials: 'DG', colour: WHO.diego }] },
      {
        qty: 3,
        name: 'Aperol spritz',
        amount: '27.00',
        chips: [{ kind: 'claim' }, { kind: 'person', name: 'Aisha', initials: 'AK', colour: WHO.aisha }],
      },
      { qty: 1, name: 'Tiramisu', amount: '9.00', chips: [{ kind: 'claim' }] },
    ],
  };

  const RECEIPT = [
    ['2 CARBONARA', '36.00'],
    ['1 MARGHERITA', '14.50'],
    ['3 APEROL SPRITZ', '27.00'],
    ['1 TIRAMISU', '9.00'],
  ];

  const NAGS = [
    ['"Can you send me £40?"', 'for what, exactly'],
    ['"I didn’t have the wine though."', 'said four minutes too late'],
    ['"Just get me next time."', 'there is no next time'],
    ['"Download what? I’m not making an account."', 'the split dies here'],
    ['"Roughly a tenner each?"', 'roughly is doing a lot of work'],
  ];
  const NAGS_2 = [
    ['"Who paid for the Airbnb again?"', 'nobody has the receipt'],
    ['"I’ll work it out on the flight."', 'reader, they did not'],
    ['"Let’s just call it even."', 'it is not even'],
    ['"Sorry, forgot. Sending now!"', 'three weeks later'],
  ];
</script>

{#snippet tile(d: string, stroke: string, bg: string)}
  <span class="site-wl-tile" style="background:{bg}">
    <svg viewBox="0 0 24 24" {stroke} fill="none" stroke-width="1.85" stroke-linecap="round" stroke-linejoin="round">
      <path {d} />
    </svg>
  </span>
{/snippet}

{#snippet statusBar(time: string)}
  <div class="site-wl-status">
    <span>{time}</span>
    <span style="display:flex;gap:6px;align-items:center">
      <span class="site-wl-sig">
        <i style="height:4px"></i><i style="height:6px"></i><i style="height:8px"></i><i style="height:10px"></i>
      </span>
      <span class="site-wl-batt"></span>
    </span>
  </div>
{/snippet}

{#snippet urlBar()}
  <div class="site-wl-brwurl">
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <rect x="5" y="10.5" width="14" height="9.5" rx="2" />
      <path d="M8.5 10.5V7.8a3.5 3.5 0 0 1 7 0v2.7" />
    </svg>
    <span><b>split-evenly.app</b>/b/7k2mQr9x</span>
  </div>
{/snippet}

{#snippet claimScreen(bill: typeof BILL_BEFORE)}
  <div style="padding:0 14px 14px">
    <div class="site-wl-billhead">
      <b>What did you have, {bill.who}?</b>
      <span>Trattoria Nove &middot; Aisha paid &pound;97.31</span>
    </div>
    <div class="site-wl-prog">
      <div class="site-wl-pbar"><i style="width:{bill.pct}%"></i></div>
      <div class="site-wl-lbl2"><span>{bill.claimed}</span><span>{bill.people}</span></div>
    </div>
    <div class="site-wl-list">
      {#each bill.lines as line (line.name)}
        <div class="site-wl-crow" class:site-wl-crow--mine={line.mine}>
          <span class="site-wl-qty">{line.qty}</span>
          <span class="site-wl-grow">
            <span class="site-wl-lbl">{line.name}</span>
            <span class="site-wl-chips">
              {#each line.chips as chip}
                {#if chip.kind === 'claim'}
                  <span class="site-wl-c site-wl-c--claim">Claim</span>
                {:else if chip.kind === 'ghost'}
                  <span class="site-wl-c site-wl-c--ghost">{chip.label}</span>
                {:else if chip.kind === 'me'}
                  <span class="site-wl-c site-wl-c--me"><i class="site-wl-a2">{chip.initials}</i>You</span>
                {:else}
                  <span class="site-wl-c"><i class="site-wl-a2" style="background:{chip.colour}">{chip.initials}</i>{chip.name}</span>
                {/if}
              {/each}
            </span>
          </span>
          <span class="site-wl-camt">{line.amount}</span>
        </div>
      {/each}
    </div>
  </div>
{/snippet}

{#snippet emailForm(source: string)}
  <form class="site-wl-form" novalidate onsubmit={(e) => submit(e, source)}>
    <input
      class="site-wl-input"
      type="email"
      name="email"
      autocomplete="email"
      placeholder="you@example.com"
      aria-label="Email address"
      bind:value={email}
      disabled={phase === 'sending'}
    />
    <button class="site-wl-submit" type="submit" disabled={phase === 'sending'}>
      {phase === 'sending' ? 'Adding you...' : 'Get early access'}
    </button>
  </form>
  {#if error}<p class="site-wl-err" role="alert">{error}</p>{/if}
{/snippet}

{#snippet onTheList(text: string)}
  <p class="site-wl-ok" role="status">
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.4"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M4 10.5l4 4 8-9" />
    </svg>
    {text}
  </p>
{/snippet}

<div class="site-root site-wl-page">
  <!-- ═══ §0 landing hero ═══════════════════════════════════════════════
       Ported from `design/landing-hero-v2.html`. The bar keeps the mockup's floating pill but
       drops its nav and its "Download app" button: on a page whose only job is the email field,
       both are exits, and the store links would 404 until launch. The mockup's CTA slot is where
       the form now sits. -->
  <section class="site-lh">
    <div class="site-lh-clouds"></div>
    <div class="site-lh-in">
      <div class="site-lh-bar">
        <div class="site-lh-pill">
          <span class="site-lh-brand">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path fill="currentColor" d="M20.5 3.2c-7.4-.9-13 2.6-15.2 8.2-1 2.6-1 5-.6 6.6L3 20.7a1 1 0 1 0 1.4 1.4l1.7-1.7c1.6.4 4 .4 6.6-.6 5.6-2.2 9.1-7.8 8.2-15.2a1.4 1.4 0 0 0-.4-1.4z" />
              <path d="M17.6 6.4 6.6 17.4M13.8 7.2h3.3v3.3M10.2 10.8h3.3v3.3" stroke="#fff"
                    stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" fill="none" />
            </svg>
            Evenly
          </span>
          <span class="site-lh-live"><i></i>Launching August</span>
        </div>
      </div>

      <div class="site-lh-copy">
        <h1>Split Anything.<br />Stay <em>Even</em>.</h1>
        <p class="site-lh-sub">
          Snap the receipt, tap who had what, and everyone sees exactly what they owe. No
          spreadsheets, no chasing.
        </p>

        {#if phase === 'done'}
          {@render onTheList('You are on the list. We will email you when Evenly opens.')}
        {:else}
          {@render emailForm('hero')}
        {/if}

        <div class="site-lh-proof">
          <span class="site-lh-faces">
            <span style="background:#3762e3">A</span><span style="background:#2f7d74">M</span>
            <span style="background:#a65a5a">D</span><span style="background:#6b6fa8">S</span>
          </span>
          <span><b>Your friends do not need the app.</b> They claim their items from a link.</span>
        </div>
      </div>

      <div class="site-lh-stage" bind:this={stageEl}>
        <img class="site-lh-tunnel" src="/hero-tunnel.svg" alt="" aria-hidden="true" />
        <div class="site-lh-phone" bind:this={phoneEl}>
          <div class="site-lh-notch"></div>
          <!-- A real capture of the Expenses tab, not a drawing. The hand-built screen this
               replaced showed copy ("You are owed", an Overview/Expenses/Activity tab row) that
               exists nowhere in the app. Recapture with `xcrun simctl io booted screenshot`. -->
          <img class="site-lh-shot" src="/app/expenses-tab.png" alt="The Lamu Trip group in Evenly, showing six expenses paid by four different people." />
        </div>
      </div>
    </div>
  </section>

  <!-- ═══ §2 trust strip ══════════════════════════════════════════════════ -->
  <div class="site-wl-in site-wl-strip">
    <h2>Your friends need nothing but a browser</h2>
    <div class="site-wl-strip-row">
      <span class="site-wl-chip">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="m15.5 8.5-2.2 4.8-4.8 2.2 2.2-4.8Z" /></svg>Safari
      </span>
      <span class="site-wl-chip">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="3.4" /><path d="M12 8.6h8M8.9 13.7 4.9 20.6M15.1 13.7l-4 6.9" /></svg>Chrome
      </span>
      <span class="site-wl-chip">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 20.5 5.4 16A8.2 8.2 0 1 1 8.6 19l-4.6 1.5Z" /></svg>WhatsApp
      </span>
      <span class="site-wl-chip">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4c4.4 0 8 2.9 8 6.5S16.4 17 12 17a10 10 0 0 1-2.4-.3L5 18l1.2-2.9A6.5 6.5 0 0 1 4 10.5C4 6.9 7.6 4 12 4Z" /></svg>iMessage
      </span>
      <span class="site-wl-chip">
        <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6.5" y="3" width="11" height="18" rx="2.4" /><path d="M10.8 18.2h2.4" /></svg>Any Android
      </span>
    </div>
  </div>

  <!-- ═══ §3 three moves ══════════════════════════════════════════════════ -->
  <section class="site-wl-sec">
    <div class="site-wl-in">
      <div class="site-wl-head">
        <h2 class="site-wl-d2">Three moves from photograph to settled.</h2>
        <p class="site-wl-p">
          Typing the items in, chasing everyone to install something, arguing over who had the
          wine: all of it happens between these three, and all of it is Evenly's job now.
        </p>
      </div>

      <div class="site-wl-trio">
        <!-- 01 Snap -->
        <div class="site-wl-gcard" style="--site-wl-tint:var(--site-wl-t-blue)">
          <div class="site-wl-step">01</div>
          <h3 class="site-wl-d3">Snap</h3>
          <p class="site-wl-p">
            One photo of the receipt. Evenly reads out every line and the service charge, so nobody
            types fourteen dishes in.
          </p>
          <div class="site-wl-gart">
            <img class="site-wl-shot" src="/app/claim.png" alt="The Tamarind Dhow bill in Evenly, each line showing who claimed it." />
          </div>
        </div>

        <!-- 02 Claim -->
        <div class="site-wl-gcard" style="--site-wl-tint:var(--site-wl-t-teal)">
          <div class="site-wl-step">02</div>
          <h3 class="site-wl-d3">Claim</h3>
          <p class="site-wl-p">
            Drop the link in the group chat. Everyone taps the dishes they actually ate, in the
            browser they already have. No download, no account.
          </p>
          <div class="site-wl-gart">
            <div class="site-wl-brw site-wl-ui site-wl-web">
              {@render urlBar()}
              <div class="site-wl-brwscreen">{@render claimScreen(BILL_BEFORE)}</div>
            </div>
          </div>
        </div>

        <!-- 03 Settle -->
        <div class="site-wl-gcard" style="--site-wl-tint:var(--site-wl-t-green)">
          <div class="site-wl-step">03</div>
          <h3 class="site-wl-d3">Settle</h3>
          <p class="site-wl-p">
            Evenly keeps the running balance and lets you clear it one expense at a time, in
            whatever payment app you already use.
          </p>
          <div class="site-wl-gart">
            <img class="site-wl-shot" src="/app/balances.png" alt="The Lamu Trip balances in Evenly, showing who owes whom." />
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- ═══ §4 the trick, from both sides. Full bleed, so no `.site-wl-in`. ══ -->
  <!-- `--flush`: the second panel runs straight into the dark act below rather than leaving a band
       of page ground between two full-bleed blocks. -->
  <section class="site-wl-sec site-wl-sec--tight site-wl-sec--flush">
    <div class="site-wl-panel" style="--site-wl-tint:var(--site-wl-t-lilac)">
      <div class="site-wl-panel-in">
        <div class="site-wl-copy">
          <div class="site-wl-eye" style="margin-bottom:16px">What you photograph</div>
          <h3 class="site-wl-d2">A receipt is a bad<br />spreadsheet.</h3>
          <p class="site-wl-p">
            So stop retyping it. Point the camera once and every line, quantity and service charge
            lands in the bill, priced and ready to be claimed.
          </p>
        </div>
        <div class="site-wl-part">
          <div class="site-wl-photo site-wl-ui">
            <div class="site-wl-paper">
              <span class="site-wl-cap">TRATTORIA NOVE</span>
              <div class="site-wl-r"><span>TABLE 12</span><span>21:04</span></div>
              <div class="site-wl-hr"></div>
              {#each RECEIPT as [label, value] (label)}
                <div class="site-wl-r"><span>{label}</span><b>{value}</b></div>
              {/each}
              <div class="site-wl-hr"></div>
              <div class="site-wl-r"><span>SERVICE 12.5%</span><b>10.81</b></div>
              <div class="site-wl-r site-wl-r--tot"><span>TOTAL</span><b>97.31</b></div>
            </div>
            <div class="site-wl-torn"></div>
          </div>
        </div>
      </div>
    </div>

    <!-- The panels touch, so this one blends out of the lilac above it before building its own
         teal. `--site-wl-from` must stay in step with the tint of the panel above. -->
    <div
      class="site-wl-panel site-wl-flip site-wl-panel--blend"
      style="--site-wl-tint:var(--site-wl-t-teal);--site-wl-from:var(--site-wl-t-lilac)"
    >
      <div class="site-wl-panel-in">
        <div class="site-wl-copy">
          <div class="site-wl-eye" style="margin-bottom:16px">What your friends get</div>
          <h3 class="site-wl-d2">A link. That is the<br />whole ask.</h3>
          <p class="site-wl-p">
            They tap what they ate and they are done. Only one person at the table ever needs the
            app, which is the difference between a split that happens and a split that does not.
          </p>
        </div>
        <div class="site-wl-part">
          <div class="site-wl-brw site-wl-brw--full site-wl-ui site-wl-web">
            {@render urlBar()}
            <div class="site-wl-brwscreen">{@render claimScreen(BILL_AFTER)}</div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- ═══ §5 beyond dinner ════════════════════════════════════════════════ -->
  <section class="site-wl-sec site-wl-dark2">
    <div class="site-wl-in">
      <div class="site-wl-dhead">
        <div class="site-wl-eye" style="margin-bottom:18px">Beyond dinner</div>
        <h2 class="site-wl-d2">Dinner is the hard part. Evenly does the rest too.</h2>
        <p class="site-wl-p">
          The receipt is the sharpest trick, not the only one. Add the Uber, the Airbnb, the weekly
          shop, and Evenly keeps one honest number for the whole group.
        </p>
      </div>

      <div class="site-wl-dcard" style="--site-wl-tint:var(--site-wl-t-lilac)">
        <div class="site-wl-copy">
          <h3 class="site-wl-d3">Every expense, one trip</h3>
          <p class="site-wl-p">
            Four people, five days, one running list. Who paid and how it was split sits on the row,
            so nobody has to remember.
          </p>
        </div>
        <div class="site-wl-part">
          <div class="site-wl-ui" style="width:100%;max-width:400px">
            <div class="site-wl-list" style="box-shadow:0 18px 34px -20px rgba(11,18,32,.45)">
              <div class="site-wl-erow">
                {@render tile(ICON.house, 'var(--site-wl-teal)', 'var(--site-wl-teal-t)')}
                <span class="site-wl-who"><b>Airbnb, 4 nights</b><span>Split evenly &middot; you paid</span></span>
                <span class="site-wl-amt site-wl-amt--owed">+&pound;318.00</span>
              </div>
              <div class="site-wl-erow">
                {@render tile(ICON.fork, 'var(--site-wl-clay)', 'var(--site-wl-clay-t)')}
                <span class="site-wl-who"><b>Trattoria Nove</b><span>Itemised &middot; 4 claimed</span></span>
                <span class="site-wl-amt site-wl-amt--owed">+&pound;61.19</span>
              </div>
              <div class="site-wl-erow">
                {@render tile(ICON.car, '#fff', '#0b1220')}
                <span class="site-wl-who"><b>Uber to the airport</b><span>Split evenly &middot; Diego paid</span></span>
                <span class="site-wl-amt site-wl-amt--owe">&minus;&pound;9.75</span>
              </div>
              <div class="site-wl-erow">
                {@render tile(ICON.cart, 'var(--site-wl-moss)', 'var(--site-wl-moss-t)')}
                <span class="site-wl-who"><b>Supermarket run</b><span>By share &middot; you paid</span></span>
                <span class="site-wl-amt site-wl-amt--owed">+&pound;24.40</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="site-wl-dcard" style="--site-wl-tint:var(--site-wl-t-peach)">
        <div class="site-wl-copy">
          <h3 class="site-wl-d3">Three ways to split, chosen per expense</h3>
          <p class="site-wl-p">
            Evenly for the taxi, by share for the room someone got to themselves, line by line for
            the meal. Decided on the expense, never locked to the group.
          </p>
        </div>
        <div class="site-wl-part">
          <div class="site-wl-ui" style="width:100%;max-width:400px">
            <div class="site-wl-list" style="box-shadow:0 18px 34px -20px rgba(11,18,32,.45)">
              <div class="site-wl-erow" style="background:var(--site-wl-blue-tint)">
                {@render tile(ICON.split, 'var(--site-wl-blue)', '#fff')}
                <span class="site-wl-who"><b>Split evenly</b><span>&pound;39.00 each &middot; 4 people</span></span>
                <span class="site-wl-c site-wl-c--solid">Chosen</span>
              </div>
              <div class="site-wl-erow">
                {@render tile(ICON.list, 'var(--site-wl-i3)', 'var(--site-wl-srf)')}
                <span class="site-wl-who"><b>By share</b><span>Diego 2 &middot; Aisha 1 &middot; Mara 1</span></span>
              </div>
              <div class="site-wl-erow">
                {@render tile(ICON.listTick, 'var(--site-wl-i3)', 'var(--site-wl-srf)')}
                <span class="site-wl-who"><b>Itemised</b><span>Everyone claims their own lines</span></span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="site-wl-dcard" style="--site-wl-tint:var(--site-wl-t-green)">
        <div class="site-wl-copy">
          <h3 class="site-wl-d3">Settle one expense at a time</h3>
          <p class="site-wl-p">
            Not one big mystery number at the end of the trip. Clear the Airbnb today and the dinner
            on Friday, and the balance moves with you.
          </p>
        </div>
        <div class="site-wl-part">
          <div class="site-wl-ui" style="width:100%;max-width:400px">
            <div class="site-wl-list" style="box-shadow:0 18px 34px -20px rgba(11,18,32,.45)">
              <div class="site-wl-erow">
                <span class="site-wl-tile site-wl-tile--av" style="background:{WHO.diego}">DG</span>
                <span class="site-wl-who"><b>Diego owes you &pound;96.05</b><span>Airbnb, Trattoria Nove</span></span>
                <span class="site-wl-c site-wl-c--solid">Remind</span>
              </div>
              <div class="site-wl-erow">
                <span class="site-wl-tile site-wl-tile--av" style="background:{WHO.aisha}">AK</span>
                <span class="site-wl-who"><b>Aisha owes you &pound;53.19</b><span>Airbnb, supermarket</span></span>
                <span class="site-wl-c site-wl-c--solid">Remind</span>
              </div>
              <div class="site-wl-erow">
                <span class="site-wl-tile site-wl-tile--av" style="background:{WHO.mara}">MR</span>
                <span class="site-wl-who"><b>Mara settled &pound;98.40</b><span>Paid you back on 18 March</span></span>
                <span class="site-wl-c site-wl-c--done">Settled</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- ═══ §6 the smaller things ═══════════════════════════════════════════ -->
  <section class="site-wl-sec">
    <div class="site-wl-in">
      <h2 class="site-wl-d2" style="max-width:26ch">And a pile of small things you would otherwise miss.</h2>
      <div class="site-wl-grid9">
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M3.5 12h17M12 3.2a15 15 0 0 1 0 17.6M12 3.2a15 15 0 0 0 0 17.6" /></svg>
          <span><b>Multi-currency</b><em>Pay in euros, owe in pounds. The rate is fixed the day you spend.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 18.5V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8l-4 2.5Z" /></svg>
          <span><b>Group chat</b><em>The argument about the bill happens on the bill, not in three places.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="6" width="18" height="13" rx="2.4" /><path d="M8.5 6 10 3.5h4L15.5 6" /><circle cx="12" cy="12.5" r="3.2" /></svg>
          <span><b>Receipt archive</b><em>Every photo stays attached to the expense it paid for.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7.5A13 13 0 0 1 20 7.5M7 11.5a8.5 8.5 0 0 1 10 0M10 15.5a4 4 0 0 1 4 0M12 19.5h.01M3 3l18 18" /></svg>
          <span><b>Works offline</b><em>Log the taxi in a tunnel. It syncs when you surface.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.5 10a5.5 5.5 0 0 1 11 0c0 4.5 1.5 5.5 1.5 5.5H5s1.5-1 1.5-5.5ZM10 19a2.2 2.2 0 0 0 4 0" /></svg>
          <span><b>Reminders</b><em>A nudge that is not you having to send a message.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5.5 7h13l-1 12.2a2 2 0 0 1-2 1.8H8.5a2 2 0 0 1-2-1.8Z" /><path d="M9.5 7V4.8h5V7M4 7h16M12 11v5.5" /></svg>
          <span><b>Recently deleted</b><em>Nothing you delete is gone for thirty days.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.5-5.8M20 4v4h-4" /></svg>
          <span><b>Recurring expenses</b><em>Rent and the broadband bill post themselves.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 15.5V4M8.2 7.6 12 3.8l3.8 3.8M5 15v3.5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V15" /></svg>
          <span><b>Export</b><em>Take the whole ledger out whenever you like.</em></span>
        </div>
        <div class="site-wl-mini">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v18M5 8 3 12l2 4M19 8l2 4-2 4" /></svg>
          <span><b>Free to split</b><em>The splitting is the product. It stays free.</em></span>
        </div>
      </div>
    </div>
  </section>

  <!-- ═══ §7 privacy ══════════════════════════════════════════════════════ -->
  <section class="site-wl-sec site-wl-sec--tight">
    <div class="site-wl-in">
      <div class="site-wl-privacy">
        <div class="site-wl-eye" style="margin-bottom:18px;color:#6f7c96">Built for privacy</div>
        <h2 class="site-wl-d2">We never see a bank detail, because we never ask for one.</h2>
        <p class="site-wl-p">
          Evenly works out who owes whom. Paying each other is something you already know how to do,
          in an app you already trust.
        </p>
        <div class="site-wl-pgrid">
          <div class="site-wl-pitem">
            <b>No bank details, ever</b>
            <span>No card, no account number, no open banking connection. There is nothing to leak.</span>
          </div>
          <div class="site-wl-pitem">
            <b>We do not move money</b>
            <span>You settle in your own payment app. Evenly just stops counting.</span>
          </div>
          <div class="site-wl-pitem">
            <b>Your data is not the product</b>
            <span>Never sold, never brokered. Export or delete it whenever you want.</span>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- ═══ §8 the problem, on a loop ═══════════════════════════════════════ -->
  <section class="site-wl-sec site-wl-sec--tight site-wl-marquee">
    <div class="site-wl-in" style="margin-bottom:52px">
      <h2 class="site-wl-d2" style="max-width:24ch">Everyone has had this exact evening.</h2>
      <p class="site-wl-p" style="margin-top:20px;max-width:52ch">
        You just paid for everyone, and now comes the part where you chase them.
        You have heard every one of these before.
      </p>
    </div>
    <div class="site-wl-mtrack">
      <!-- Each row is rendered twice: the keyframe translates by -50%, so the second copy is what
           the first one slides into and the loop has no seam. -->
      <div class="site-wl-mrow">
        {#each [...NAGS, ...NAGS] as [line, tag], i (i)}
          <div class="site-wl-mcard"><q>{line}</q><em>{tag}</em></div>
        {/each}
      </div>
      <div class="site-wl-mrow site-wl-mrow--rev">
        {#each [...NAGS_2, ...NAGS_2] as [line, tag], i (i)}
          <div class="site-wl-mcard"><q>{line}</q><em>{tag}</em></div>
        {/each}
      </div>
    </div>
  </section>

  <!-- ═══ §9 the field again, for anyone who scrolled to decide ═══════════ -->
  <section class="site-wl-sec site-wl-sec--tight site-wl-sec--last">
    <div class="site-wl-in">
      <div class="site-wl-close" id="site-wl-signup">
        <h2 class="site-wl-d2">Be there when it opens.</h2>
        <p class="site-wl-p">
          Launching August on iOS and Android. Leave your email and we will tell you the moment
          it is live.
        </p>
        {#if phase === 'done'}
          {@render onTheList('You are on the list.')}
        {:else}
          {@render emailForm('band')}
        {/if}
      </div>
    </div>
  </section>

  <Footer />

  <!-- Sticky join pill. Hidden once they are on the list: a standing call to do the thing they have
       already done is the fastest way to make someone think the form did not work. -->
  {#if phase !== 'done'}
    <div class="site-wl-sticky">
      <span class="site-wl-sart">
        <!-- Ring centre is x=0, pinned to the pill's midpoint by CSS. Only the right halves fall
             inside the viewBox, so they clip to semicircles with no extra masking. -->
        <svg width="340" height="300" viewBox="0 -150 340 300" fill="none" aria-hidden="true">
          <g stroke="var(--site-wl-pill-art)" fill="none">
            <circle cx="0" cy="0" r="40" stroke-width="30" />
            <circle cx="0" cy="0" r="98" stroke-width="22" />
            <circle cx="0" cy="0" r="158" stroke-width="16" opacity=".74" />
            <circle cx="0" cy="0" r="216" stroke-width="12" opacity=".5" />
            <circle cx="0" cy="0" r="272" stroke-width="9" opacity=".32" />
          </g>
        </svg>
      </span>
      <span class="site-wl-veil"></span>
      <div class="site-wl-stxt">
        <b>Launching August</b>
        <span>Wanna try it out early?</span>
      </div>
      <button class="site-wl-sgo" type="button" onclick={toSignup}>Join Waitlist</button>
    </div>
  {/if}
</div>
