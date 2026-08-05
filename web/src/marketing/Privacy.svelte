<script lang="ts">
  import Header from './Header.svelte';
  import Footer from './Footer.svelte';
  import LegalDoc from './LegalDoc.svelte';
</script>

<div class="site-root">
  <Header current="privacy" />

  <LegalDoc
    title="Privacy Policy"
    meta="Last updated August 4, 2026 · Applies to the Evenly app and split-evenly.app"
    summary="Evenly needs your email, and your payment handle so friends know how to pay you. Nothing is ever sold, and there's no advertising ID, anywhere."
  >
    <section class="site-doc-section">
      <span class="site-sn">01</span>
      <h2>What we collect</h2>
      <p>We collect what the app needs to split a bill and reach you about it, nothing more.</p>
      <table class="site-doc-table">
        <thead>
          <tr><th>Category</th><th>Examples</th><th>Why</th></tr>
        </thead>
        <tbody>
          <tr><td>Account</td><td>Email address</td><td>Signing you in, across devices</td></tr>
          <tr><td>Profile</td><td>Display name, payment app handle</td><td>So the group knows how to pay you</td></tr>
          <tr><td>Bill data</td><td>Expenses, items, amounts, receipt photos</td><td>The core of splitting and settling</td></tr>
          <tr><td>Diagnostics</td><td>Anonymous usage events, crash reports</td><td>Fixing bugs, never tied to who you are</td></tr>
        </tbody>
      </table>
      <p>We never request payment credentials, bank details, or card numbers. Settling up happens in whatever payment app you already use.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">02</span>
      <h2>How we use it</h2>
      <p>
        To run the core features: splitting, balances, and notifying you when someone adds an expense
        or claims an item. Anonymous, aggregated analytics via Firebase Analytics help us see what's
        confusing; Firebase Crashlytics tells us what broke. No names, emails, amounts, or item titles
        are ever included in a crash report.
      </p>
      <div class="site-doc-note"><b>No advertising, ever.</b> No ad network, no advertising ID, and Firebase's ad-ID collection is explicitly disabled.</div>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">03</span>
      <h2>The web claim link</h2>
      <p>A payer can send a bill as a link that opens in any browser, no account required. It only ever handles the one bill it was sent for:</p>
      <ul>
        <li>The link expires automatically after 72 hours.</li>
        <li>The person opening it types just a first name to say which items they had.</li>
        <li>Nothing is stored beyond that claim, and it disappears from view once the link expires.</li>
      </ul>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">04</span>
      <h2>Who we share it with</h2>
      <p>A small number of infrastructure providers run Evenly, each bound to use your data only to serve us:</p>
      <ul>
        <li><strong>Supabase</strong>, hosts the database and authentication behind your groups and account.</li>
        <li><strong>Firebase (Google)</strong>, anonymous analytics, crash reporting, and push delivery.</li>
        <li><strong>Other group members</strong>, your name and payment handle are visible within your groups, because that's how splitting works.</li>
      </ul>
      <p>We do not sell personal information, or share it with data brokers or advertisers.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">05</span>
      <h2>Retention &amp; deletion</h2>
      <p>
        Account and bill data is kept as long as your account is active. Deleting your account from
        Profile removes your personal data from active use; some records may persist briefly in
        backups, or where a group's shared history depends on them. Analytics data is kept for the
        shortest window Firebase allows us to configure.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">06</span>
      <h2>Your choices</h2>
      <ul>
        <li><strong>Turn off analytics</strong> any time in Profile → Privacy. Disables Firebase Analytics and Crashlytics immediately.</li>
        <li><strong>Export or delete your data</strong> from Profile, including full account deletion.</li>
        <li><strong>Control notifications</strong> per-group or entirely.</li>
      </ul>
      <p>Depending on where you live, you may have additional rights to access, correct, or port your data. Reach us below to exercise them.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">07</span>
      <h2>Children's privacy</h2>
      <p>Evenly isn't directed at children under 13, and we don't knowingly collect their information. If you believe a child has an account, contact us and we'll remove it.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">08</span>
      <h2>Changes to this policy</h2>
      <p>If we make a material change to how we handle your data, we'll notify you in the app before it takes effect. The date above always reflects the latest version.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">09</span>
      <h2>Contact</h2>
      <p>Reach the Evenly team at <a href="mailto:privacy@split-evenly.app">privacy@split-evenly.app</a>.</p>
    </section>
  </LegalDoc>

  <Footer />
</div>
