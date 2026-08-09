<script lang="ts">
  import Header from './Header.svelte';
  import Footer from './Footer.svelte';
  import LegalDoc from './LegalDoc.svelte';

  const FAQS: { q: string; a: string }[] = [
    {
      q: 'How do I start a group?',
      a: 'Open Evenly, tap the + on the Groups tab, name it, and invite friends by sharing the invite link. Everyone who joins can add expenses right away.',
    },
    {
      q: 'How does splitting an expense work?',
      a: 'Add an expense, then choose evenly, by exact share, or itemized off a receipt photo. Itemized splitting lets each person claim only what they actually had.',
    },
    {
      q: "Someone doesn't have the app. Can they still pay?",
      a: 'Yes. Share a bill as a web link, no account required. They open it in any browser, type their first name, tap the items they had, and see what they owe.',
    },
    {
      q: 'How do I settle up?',
      a: "Evenly tracks who owes what, but doesn't move money itself. Tap Settle Up to see the simplest set of payments, then pay through whatever app you already use (Venmo, Cash App, Zelle) and mark it as settled.",
    },
    {
      q: 'Can I edit or delete an expense after adding it?',
      a: 'Yes, any group member can edit or undo a change. Edits that move the total are logged and everyone in the group is notified.',
    },
    {
      q: 'How do I delete my account?',
      a: 'Open Settings → Account → Delete Account in the app. See our [Delete Your Account](/delete-account) page for the full details, including what data is kept and why.',
    },
    {
      q: 'Is my data private?',
      a: "We only collect what's needed to split bills and reach you about them, and we never sell your data or show ads. Read the full [Privacy Policy](/privacy) for details.",
    },
  ];
</script>

<div class="site-root">
  <Header current="support" />

  <LegalDoc
    eyebrow="Support"
    title="Help &amp; Support"
    meta="Evenly, by Octagon Technologies"
    summary="Answers to the most common questions, plus a direct line to the team if you're still stuck."
  >
    <section class="site-doc-section">
      <span class="site-sn">01</span>
      <h2>Frequently asked questions</h2>
      <ul class="site-faq">
        {#each FAQS as faq}
          <li class="site-faq-item">
            <details>
              <summary>{faq.q}</summary>
              <p>
                {#each faq.a.split(/(\[[^\]]+\]\([^)]+\))/g) as part}
                  {#if part.startsWith('[')}
                    {@const m = /\[([^\]]+)\]\(([^)]+)\)/.exec(part)}
                    <a href={m?.[2]}>{m?.[1]}</a>
                  {:else}
                    {part}
                  {/if}
                {/each}
              </p>
            </details>
          </li>
        {/each}
      </ul>
    </section>

    <section class="site-doc-section">
      <span class="site-sn">02</span>
      <h2>Still need help?</h2>
      <p>
        Email us at <a href="mailto:tech.octagontechnologies@gmail.com">tech.octagontechnologies@gmail.com</a>
        and we'll get back to you, usually within a couple of days. Tell us what you were trying to do,
        what happened instead, and which device you're on, it helps us track things down faster.
      </p>
    </section>
  </LegalDoc>

  <Footer />
</div>
