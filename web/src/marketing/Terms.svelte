<script lang="ts">
  import Header from './Header.svelte';
  import Footer from './Footer.svelte';
  import LegalDoc from './LegalDoc.svelte';
</script>

<div class="site-root">
  <Header current="terms" />

  <LegalDoc
    title="Terms of Service"
    meta="Last updated August 4, 2026 · Applies to the Evenly app and split-evenly.app"
    summary="Evenly is the ledger, not the wallet. Money always changes hands directly between you and your friends, outside the app."
  >
    <section class="site-doc-section">
      <span class="site-sn">01</span>
      <h2>Acceptance of terms</h2>
      <p>
        By creating an account, opening a bill link, or otherwise using Evenly (the "Service"), you
        agree to these terms and our <a href="/privacy">Privacy Policy</a>. If you're using the Service
        on behalf of a group, you're confirming you have their authority for the purposes of shared
        bills.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">02</span>
      <h2>Your account</h2>
      <ul>
        <li>You must be at least 13 years old to create an account.</li>
        <li>You're responsible for the accuracy of what you provide and for keeping your account secure.</li>
        <li>One account per person. Accounts aren't transferable.</li>
      </ul>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">03</span>
      <h2>Acceptable use</h2>
      <p>You agree not to:</p>
      <ul>
        <li>Use the Service to harass, defraud, or misrepresent a debt to another person.</li>
        <li>Access another user's account or another group's bills without authorization.</li>
        <li>Reverse-engineer, scrape, or interfere with the Service's normal operation.</li>
        <li>Upload receipts or content you don't have the right to share.</li>
      </ul>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">04</span>
      <h2>Money &amp; settling up</h2>
      <p>
        Evenly calculates who owes what. It does not hold funds, move money, or process payments.
        "Settling up" marks a debt as paid after you've actually paid it through a separate payment
        app, or another method you and the other person agreed on.
      </p>
      <p>
        Evenly isn't responsible for payments sent incorrectly, disputed, or never sent. Any dispute
        about an actual payment is between the people involved, not with Evenly.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">05</span>
      <h2>The web claim link</h2>
      <p>
        A bill link lets someone claim items without creating an account. By claiming items, you're
        confirming to the group that you had them, and that the resulting share is accurate to your
        knowledge. The link and its claims expire 72 hours after the bill was shared.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">06</span>
      <h2>Your content</h2>
      <p>
        You keep ownership of anything you upload, like receipt photos. Uploading it gives Evenly a
        limited license to store, process, and display it back to your group to run the Service, for
        example, scanning a receipt into itemized lines.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">07</span>
      <h2>Disclaimers</h2>
      <p>
        Evenly does its best to itemize and split bills accurately, including receipt scanning, but a
        scan or split may occasionally be wrong. Always confirm amounts that matter before paying. The
        Service is provided without warranties of any kind, to the extent permitted by law.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">08</span>
      <h2>Limitation of liability</h2>
      <p>
        To the maximum extent permitted by law, Evenly isn't liable for indirect, incidental, or
        consequential damages arising from your use of the Service, including money sent to the wrong
        person, disputes between group members, or reliance on an inaccurate split. Because Evenly
        never touches your money directly, our total liability for any claim is limited to what you've
        paid us in the past 12 months. For most users that's zero.
      </p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">09</span>
      <h2>Termination</h2>
      <p>You can delete your account any time from Profile → Delete my account. We may suspend or terminate access for accounts that violate these terms.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">10</span>
      <h2>Changes to these terms</h2>
      <p>We may update these terms as the Service evolves. Material changes will be announced in the app before they take effect. Continued use after that point means you accept the update.</p>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">11</span>
      <h2>Contact</h2>
      <p>Reach the Evenly team at <a href="mailto:tech.octagontechnologies@gmail.com">tech.octagontechnologies@gmail.com</a>.</p>
    </section>
  </LegalDoc>

  <Footer />
</div>
