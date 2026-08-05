<script lang="ts">
  import Header from './Header.svelte';
  import Footer from './Footer.svelte';
  import StoreBadges from './StoreBadges.svelte';
</script>

<div class="site-root">
  <Header current="home" />

  <section class="site-hero">
    <h1>Split the bill.<br />Not the <em>friendship</em>.</h1>
    <p class="site-lede">
      Evenly reads the receipt, itemizes every plate, and works out who owes what to the cent, then
      lets anyone at the table claim their share, app or no app.
    </p>
    <StoreBadges />
    <p class="site-hero-foot">Got a link from a friend? Open it from your texts. No download needed.</p>
  </section>

  <section class="site-features" id="site-features">
    <div class="site-features-head">
      <h2>Everything a shared bill needs. Nothing it doesn't.</h2>
      <p>Four things Evenly does well, so the check never becomes the awkward part of the night.</p>
    </div>

    <div class="site-feature-row">
      <div class="site-f-text">
        <div class="site-fk">Receipt scanning</div>
        <h3>Snap it, and it's itemized.</h3>
        <p>
          Photograph the receipt and every line, tax, and tip is read automatically. No typing prices
          off a crumpled slip while everyone waits.
        </p>
      </div>
      <div class="site-f-art">
        <div class="site-fan">
          <div class="site-phone site-back">
            <div class="site-notch"></div>
            <div class="site-screen">
              <div class="site-ph-k">Osteria Bar</div>
              <div class="site-ph-t">Scanning receipt…</div>
              <div class="site-ph-row"><div class="site-rl"><div class="site-rt">Truffle fries</div></div><div class="site-ra">$9.00</div></div>
              <div class="site-ph-row"><div class="site-rl"><div class="site-rt">Margherita pizza</div></div><div class="site-ra">$18.00</div></div>
              <div class="site-ph-row"><div class="site-rl"><div class="site-rt">Two negronis</div></div><div class="site-ra">$24.00</div></div>
            </div>
          </div>
          <div class="site-phone site-front">
            <div class="site-notch"></div>
            <div class="site-screen">
              <div class="site-ph-k">Itemized</div>
              <div class="site-ph-t">4 items · tax &amp; tip split</div>
              <div class="site-ph-row"><div class="site-rl"><div class="site-rt">Subtotal</div></div><div class="site-ra">$68.00</div></div>
              <div class="site-ph-row"><div class="site-rl"><div class="site-rt">Tax + tip</div></div><div class="site-ra">$18.40</div></div>
              <div class="site-ph-tot"><span class="site-k">Total</span><span class="site-v">$86.40</span></div>
              <div class="site-ph-cta">Claim your items</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="site-feature-row site-rev">
      <div class="site-f-text">
        <div class="site-fk">Claiming</div>
        <h3>Everyone claims what they had.</h3>
        <p>
          Tap a chip on your items. Shared plates split evenly among whoever claims them, automatically,
          no arguing over the calculator.
        </p>
      </div>
      <div class="site-f-art">
        <div class="site-fan">
          <div class="site-phone site-back">
            <div class="site-notch"></div>
            <div class="site-screen">
              <div class="site-ph-k">Margherita pizza</div>
              <div class="site-ph-t">$18.00 · split 2 ways</div>
              <span class="site-ph-chip">P Priya</span> <span class="site-ph-chip site-me">M You</span>
            </div>
          </div>
          <div class="site-phone site-front">
            <div class="site-notch"></div>
            <div class="site-screen">
              <div class="site-ph-k">Claim progress</div>
              <div class="site-ph-t">3 of 4 items claimed</div>
              <div class="site-ph-bar"><i></i></div>
              <div class="site-ph-row" style="margin-top:8px"><div class="site-rl"><div class="site-rt">Dele just claimed</div><div class="site-ra">Two negronis</div></div></div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="site-feature-row">
      <div class="site-f-text">
        <div class="site-fk">Web claiming</div>
        <h3>No app? Claim from a text link.</h3>
        <p>
          Scan the QR at the table. Pick your name, tap what you had, and you're done, right in a
          browser, gone in 72 hours, no account made.
        </p>
      </div>
      <div class="site-f-art">
        <div class="site-fan">
          <div class="site-phone site-back">
            <div class="site-notch"></div>
            <div class="site-screen" style="text-align:center">
              <div class="site-ph-qr"></div>
              <div class="site-ph-note">Scan to claim your share<br />of Osteria Bar</div>
            </div>
          </div>
          <div class="site-phone site-front">
            <div class="site-notch"></div>
            <div class="site-screen">
              <div class="site-ph-k">split-evenly.app</div>
              <div class="site-ph-t">Hey! Which one are you?</div>
              <span class="site-ph-chip">M Marcus</span> <span class="site-ph-chip">P Priya</span> <span class="site-ph-chip">D Dele</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="site-feature-row site-rev">
      <div class="site-f-text">
        <div class="site-fk">Settling up</div>
        <h3>Balances stay per person.</h3>
        <p>
          Never lumped into one group number. Pay through whatever app you already use. Evenly is the
          ledger, not the wallet.
        </p>
      </div>
      <div class="site-f-art">
        <div class="site-fan">
          <div class="site-phone site-back">
            <div class="site-notch"></div>
            <div class="site-screen">
              <div class="site-ph-k">Balances</div>
              <div class="site-ph-t">You owe</div>
              <div class="site-ph-row"><div class="site-rl"><div class="site-rt">Priya</div></div><div class="site-ra">$14.20</div></div>
              <div class="site-ph-row"><div class="site-rl"><div class="site-rt">Dele</div></div><div class="site-ra">$8.75</div></div>
            </div>
          </div>
          <div class="site-phone site-front">
            <div class="site-notch"></div>
            <div class="site-screen">
              <div class="site-ph-k">Settle with Priya</div>
              <div class="site-ph-t">$14.20 via Venmo</div>
              <div class="site-ph-cta">Mark as paid</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <section class="site-band">
    <div class="site-band-in">
      <h2>Split the bill.<br />Not the <em>friendship</em>.</h2>
      <p>Free for every group, every bill.</p>
      <StoreBadges variant="dark" />
    </div>
  </section>

  <Footer />
</div>
