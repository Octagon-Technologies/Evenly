<script lang="ts">
  /**
   * The pre-launch page at `/waitlist`. Approved mockup: `design/waitlist-final.html` (direction A3).
   *
   * Two rules this page follows that the rest of the marketing site does not, both because it exists
   * to do exactly one thing:
   *  - No nav and no store badges. Every other link is a way to leave without signing up, and the
   *    store links would 404 until launch anyway.
   *  - The email field appears twice and is the only control on the page.
   *
   * The submit posts to the `waitlist` edge function, never to PostgREST: this bundle ships no
   * Supabase client and no anon key (WEB_CLAIM_SPEC §4.1), and `waitlist_signups` is RLS-denied to
   * everyone but that function's service key.
   */
  import Footer from './Footer.svelte';

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
</script>

<div class="site-root">
  <!-- Header, minus the nav. See the component comment: no exits on a page with one job. -->
  <div class="site-header-bar">
    <div class="site-wl-bar">
      <a class="site-wordmark" href="/">
        <svg viewBox="0 0 100 100" fill="currentColor" aria-hidden="true">
          <path
            d="M96.32 0.05C85.02 1.19 73.68 3.79 63 7.66C55.8 10.28 48.59 13.69 42.62 18.58C37.62 22.67 33.67 27.64 30.77 33.43C29.69 35.6 28.63 37.83 27.86 40.13C27.74 40.49 27.17 42.43 26.82 42.42C26.12 42.39 25.48 40.76 25.2 40.24C23.93 37.92 23.09 35.3 22.97 32.65C22.93 31.71 23.11 30.79 23.25 29.87C23.26 29.84 23.5 28.73 23.1 28.9C22.23 29.27 21.49 30.36 20.8 30.97C18.75 32.8 16.92 34.83 15.04 36.83C8.72 43.58 3.18 51.87 2.1 61.25C1.38 67.42 2.76 73.79 3.7 79.85C4.18 82.87 4.22 85.95 4.15 89C4.13 89.76 3.64 92.38 3.93 92.78C4.22 93.18 5.11 91.6 5.24 91.37C6.29 89.58 7.74 88 8.92 86.29C11.9 81.96 15.46 77.93 18.89 73.94C29.54 61.56 40.83 49.91 53.79 39.9C57.68 36.9 61.67 34.02 65.76 31.3C67.3 30.28 68.84 29.28 70.42 28.32C70.63 28.2 71.88 27.21 71.96 27.79C72.04 28.39 70.47 30.57 70.12 31.24C68.18 35.03 65.78 38.57 63.58 42.2C54.73 56.78 43.2 69.52 31.17 81.52C26.87 85.81 22.44 89.94 17.85 93.92C16.34 95.23 14.83 96.52 13.26 97.76C11.7 98.99 10.81 99.81 11.09 99.87C12.09 100.09 13.46 99.43 14.48 99.35C17.27 99.11 20.04 98.93 22.86 99.02C28.43 99.18 33.9 100.24 39.5 100C39.98 99.98 40.5 99.8 40.98 99.74C50.47 98.62 57.03 93.73 63.94 87.54C66.56 85.2 68.85 82.47 71.13 79.81C71.66 79.19 73.66 77.21 73.78 76.52C73.85 76.12 71.2 76.71 70.26 76.72C69.41 76.73 68.48 76.78 67.65 76.72C65.76 76.58 63.68 75.77 61.98 74.94C61.63 74.77 60.25 74.21 60.34 73.68C60.42 73.29 61.46 72.99 61.77 72.88C63.19 72.38 64.54 71.63 65.88 70.95C70.83 68.42 75.57 65.18 79.3 61.04C90.28 48.84 94.33 32 96.65 16.15C97.23 12.17 97.71 8.11 97.85 4.09C97.96 1 98.92 -0.08 96.32 0.05Z"
          />
        </svg>
        <span>Evenly</span>
      </a>
      <span class="site-wl-pill"><i class="site-wl-dot"></i>Launching August</span>
    </div>
  </div>

  <!-- ── hero ── -->
  <section class="site-wl-hero">
    <h1>Photograph the bill.<br />Everyone <em>claims their own.</em></h1>
    <p class="site-wl-lede">
      Evenly keeps track of who owes whom across dinners, trips and rent. Its party trick: snap a
      receipt and your friends tap the dishes they ate <b>in a browser</b>. Only one of you needs
      the app.
    </p>

    {#if phase === 'done'}
      <p class="site-wl-ok" role="status">
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.4"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M4 10.5l4 4 8-9" />
        </svg>
        You are on the list. We will email you once, when Evenly opens.
      </p>
    {:else}
      <form class="site-wl-form" novalidate onsubmit={(e) => submit(e, 'hero')}>
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
      <p class="site-wl-micro">Launching August. One email when it opens, nothing else.</p>
    {/if}
  </section>

  <!-- ── the trick, from both sides ── -->
  <section class="site-wl-panels">
    <div class="site-wl-panels-in">
      <div class="site-wl-pan">
        <div class="site-wl-eyebrow">What you photograph<i></i></div>
        <div class="site-wl-scan">
          <div class="site-wl-paper">
            <b>TRATTORIA NOVE</b>
            <span><i>2 CARBONARA</i><i>36.00</i></span>
            <span><i>1 MARGHERITA</i><i>14.50</i></span>
            <span><i>3 APEROL SPRITZ</i><i>27.00</i></span>
            <span><i>1 TIRAMISU</i><i>9.00</i></span>
            <span><i>SERVICE 12.5%</i><i>10.81</i></span>
          </div>
          <div class="site-wl-beam"></div>
        </div>
        <p class="site-wl-pcap">
          One photo, and Evenly pulls out every line item and the service charge.
          <b>No typing fourteen dishes in.</b>
        </p>
      </div>

      <div class="site-wl-pan">
        <div class="site-wl-eyebrow site-wl-on">What your friends get<i></i></div>
        <div class="site-wl-list">
          <div class="site-wl-lh">Tap what you ate<em>no app needed</em></div>
          <div class="site-wl-li">
            <span class="site-wl-n">Carbonara &times;2</span>
            <span class="site-wl-a">36.00</span>
            <span class="site-wl-claim">Claim</span>
          </div>
          <div class="site-wl-li">
            <span class="site-wl-n">Margherita</span>
            <span class="site-wl-a">14.50</span>
            <span class="site-wl-claim site-wl-taken"><i class="site-wl-av">DG</i>Diego</span>
          </div>
          <div class="site-wl-li">
            <span class="site-wl-n">Aperol &times;3</span>
            <span class="site-wl-a">27.00</span>
            <span class="site-wl-claim">Claim</span>
          </div>
          <div class="site-wl-li">
            <span class="site-wl-n">Tiramisu</span>
            <span class="site-wl-a">9.00</span>
            <span class="site-wl-claim site-wl-taken"><i class="site-wl-av">AK</i>Aisha</span>
          </div>
        </div>
        <p class="site-wl-pcap">
          A link in the group chat. It opens in whatever browser they already have.
          <b>No download, no account.</b>
        </p>
      </div>
    </div>
  </section>

  <!-- ── breadth: the receipt is the sharpest feature, not the only one ── -->
  <section class="site-wl-breadth">
    <div class="site-wl-bhead">
      <h2>Dinner is the hard part. Evenly does the rest too.</h2>
      <p>
        Add the Uber, the Airbnb, the weekly shop. Split it evenly, by share, or line by line.
        Evenly keeps the running balance for the whole group and
        <b>lets you settle one expense at a time</b> instead of one big mystery number.
      </p>
    </div>

    <div class="site-wl-bgrid">
      <div class="site-wl-card">
        <div class="site-wl-ch">Lisbon, 4 people<em>March 12 to 16</em></div>
        <div class="site-wl-ex">
          <i class="site-wl-ico">&#127968;</i>
          <span class="site-wl-g">
            <span class="site-wl-t">Airbnb, 4 nights</span>
            <span class="site-wl-m">Split evenly, you paid</span>
          </span>
          <span class="site-wl-v site-wl-owed">+&pound;318.00</span>
        </div>
        <div class="site-wl-ex">
          <i class="site-wl-ico">&#127860;</i>
          <span class="site-wl-g">
            <span class="site-wl-t">Trattoria Nove</span>
            <span class="site-wl-m">Itemised, 4 claimed</span>
          </span>
          <span class="site-wl-v site-wl-owed">+&pound;61.19</span>
        </div>
        <div class="site-wl-ex">
          <i class="site-wl-ico">&#128663;</i>
          <span class="site-wl-g">
            <span class="site-wl-t">Uber to the airport</span>
            <span class="site-wl-m">Split evenly, Diego paid</span>
          </span>
          <span class="site-wl-v site-wl-owe">&minus;&pound;9.75</span>
        </div>
        <div class="site-wl-ex">
          <i class="site-wl-ico">&#128722;</i>
          <span class="site-wl-g">
            <span class="site-wl-t">Supermarket run</span>
            <span class="site-wl-m">By share, you paid</span>
          </span>
          <span class="site-wl-v site-wl-owed">+&pound;24.40</span>
        </div>
      </div>

      <div class="site-wl-card">
        <div class="site-wl-ch">Your balance<em>across 4 expenses</em></div>
        <div class="site-wl-bal">
          <div>
            <div class="site-wl-balk">You are owed</div>
            <div class="site-wl-balv">&pound;393.84</div>
          </div>
          <div class="site-wl-bar2"><i class="site-wl-paid"></i><i class="site-wl-open"></i></div>
          <div class="site-wl-barlbl">
            <span><b>&pound;244.60 settled</b></span><span>&pound;149.24 outstanding</span>
          </div>
          <div class="site-wl-settle">
            <div class="site-wl-srow">
              <i class="site-wl-av">DG</i>
              <span class="site-wl-g">
                <span class="site-wl-t">Diego owes you &pound;96.05</span>
                <span class="site-wl-m">Airbnb, Trattoria Nove</span>
              </span>
              <span class="site-wl-sbtn">Remind</span>
            </div>
            <div class="site-wl-srow">
              <i class="site-wl-av">AK</i>
              <span class="site-wl-g">
                <span class="site-wl-t">Aisha owes you &pound;53.19</span>
                <span class="site-wl-m">Airbnb, supermarket</span>
              </span>
              <span class="site-wl-sbtn">Remind</span>
            </div>
            <div class="site-wl-srow">
              <i class="site-wl-av">MR</i>
              <span class="site-wl-g">
                <span class="site-wl-t">Mara settled &pound;98.40</span>
                <span class="site-wl-m">Paid you back on 18 March</span>
              </span>
              <span class="site-wl-sbtn site-wl-done">Settled</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- ── ask again, for anyone who scrolled to decide ── -->
  <section class="site-band site-wl-band">
    <div class="site-band-in">
      <h2>Be there when it <em>opens.</em></h2>
      {#if phase === 'done'}
        <p class="site-wl-ok" style="justify-content:center;max-width:460px;margin:0 auto;" role="status">
          <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.4"
               stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M4 10.5l4 4 8-9" />
          </svg>
          You are on the list.
        </p>
      {:else}
        <form class="site-wl-band-form" novalidate onsubmit={(e) => submit(e, 'band')}>
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
        {#if error}<p class="site-wl-err" role="alert" style="color:#fda29b">{error}</p>{/if}
        <p class="site-wl-band-micro">Launching August on iOS and Android.</p>
      {/if}
      <div class="site-wl-tags">
        <span class="site-wl-tag">Free to split</span>
        <span class="site-wl-tag">No bank details, ever</span>
        <span class="site-wl-tag">Friends never install anything</span>
        <span class="site-wl-tag">Settle in your own payment app</span>
      </div>
    </div>
  </section>

  <Footer />
</div>
