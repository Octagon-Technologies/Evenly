<script lang="ts">
  import Header from './Header.svelte';
  import Footer from './Footer.svelte';
  import LegalDoc from './LegalDoc.svelte';
</script>

<div class="site-root">
  <Header current="delete-account" />

  <LegalDoc
    eyebrow="Account"
    title="Delete Your Account"
    meta="Evenly, by Octagon Technologies"
    summary="Request deletion from inside the app in under a minute. We'll tell you exactly what's removed, what's kept, and for how long."
  >
    <section class="site-doc-section">
      <span class="site-sn">01</span>
      <h2>Delete from the app</h2>
      <ol>
        <li>Open Evenly and sign in.</li>
        <li>Tap the <strong>Settings</strong> tab.</li>
        <li>Under <strong>Account</strong>, tap <strong>Delete account</strong>.</li>
        <li>Read the confirmation, then tap <strong>Delete</strong>.</li>
      </ol>
      <p>You're signed out immediately, and the 30-day countdown described below starts right away.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">02</span>
      <h2>Can't open the app?</h2>
      <p>
        Email <a href="mailto:tech.octagontechnologies@gmail.com">tech.octagontechnologies@gmail.com</a>
        from the address on your account and ask us to delete it. We'll confirm once it's done.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">03</span>
      <h2>What's deleted right away</h2>
      <ul>
        <li>Your name, email address, and profile photo</li>
        <li>Your payment app handles (Venmo, Cash App, PayPal, Zelle)</li>
        <li>Your push notification tokens, and your ability to sign in</li>
      </ul>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">04</span>
      <h2>What's kept, and why</h2>
      <p>
        Expenses, splits, settlements, and receipts you were part of stay in your former groups'
        history, shown as <strong>"Deleted user"</strong> instead of your name. Evenly's expenses are
        shared with other people; removing them would silently change what everyone else in the group
        owes or is owed. Keeping the numbers, without your identity attached to them, is how we avoid
        breaking other people's accounts to honor your deletion request.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">05</span>
      <h2>Retention period</h2>
      <p>
        Your account has a <strong>30-day grace period</strong> after you request deletion. Signing
        back in during those 30 days cancels the request and restores full access, nothing is lost.
        After 30 days, your account is permanently anonymized and the request can no longer be undone.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">06</span>
      <h2>Questions</h2>
      <p>
        See our <a href="/privacy">Privacy Policy</a> for the full picture of what we collect and why,
        or reach the Evenly team at
        <a href="mailto:tech.octagontechnologies@gmail.com">tech.octagontechnologies@gmail.com</a>.
      </p>
    </section>
  </LegalDoc>

  <Footer />
</div>
