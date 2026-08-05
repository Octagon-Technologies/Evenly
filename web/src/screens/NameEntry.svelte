<script lang="ts">
  /**
   * Frame 2 — typing a name, with the §2.4 uniqueness block.
   *
   * **Exact duplicates are blocked, not warned.** The whole feature depends on pointing at people;
   * two identical names make "who did you share the plate with?" unanswerable at the moment it
   * matters, and unanswerable forever afterwards in the ledger. A warning would be dismissed; the
   * block costs one tap.
   *
   * Continue is never `disabled` — root AGENTS.md §7: no silent dead ends. It stays live, dims, and
   * says what is missing when tapped.
   *
   * The escape hatch back to "I am that Purity" is always on screen, because the likeliest cause of a
   * collision is that it genuinely is her and she missed the evidence list.
   */
  import BillHeader from '../components/BillHeader.svelte';
  import ErrorBanner from '../components/ErrorBanner.svelte';
  import { money, shortDate } from '../lib/format.ts';
  import type { Candidate } from '../lib/api.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  const { store }: { store: ClaimStore } = $props();

  let name = $state('');
  let blockedFor = $state<string | null>(null);
  let suggestions = $state<string[]>([]);
  let suggestion = $state<Candidate | null>(null);
  let hint = $state<string | null>(null);

  const trimmed = $derived(name.trim().replace(/\s+/g, ' '));
  /** The block is re-evaluated as she types, so fixing it clears the error without another round trip. */
  const isBlocked = $derived(blockedFor !== null && trimmed.toLowerCase() === blockedFor.toLowerCase());
  const canSubmit = $derived(trimmed.length > 0 && !isBlocked);

  async function submit(force = false) {
    if (!canSubmit) {
      hint = trimmed.length === 0 ? 'Type a name first, so people can point at your plate.' : null;
      return;
    }
    hint = null;
    const result = await store.submitName(trimmed, force);
    if (result.kind === 'blocked') {
      blockedFor = trimmed;
      suggestions = result.suggestions;
      suggestion = null;
    } else if (result.kind === 'suggestion') {
      suggestion = result.candidate;
    } else {
      await store.enterClaiming();
    }
  }

  async function acceptSuggestion(candidate: Candidate) {
    const { won } = await store.claimPlaceholder(candidate.userId);
    if (won) await store.enterClaiming();
  }

  function useSuffix(value: string) {
    name = value;
    blockedFor = null;
    suggestions = [];
  }
</script>

<div class="page">
  <div class="scroll">
    {#if store.header}
      <BillHeader header={store.header} title="What should we call you?" />
    {/if}

    {#if store.error}
      <ErrorBanner message={store.error} />
    {/if}

    <div class="field">
      <label class="flbl" for="name">Your name</label>
      <input
        id="name"
        class="inp"
        class:inp--bad={isBlocked}
        type="text"
        maxlength="40"
        autocomplete="name"
        enterkeyhint="go"
        aria-describedby={isBlocked ? 'name-block' : undefined}
        bind:value={name}
        onkeydown={(e) => e.key === 'Enter' && submit()}
      />
    </div>

    {#if isBlocked}
      <div class="note note--red" id="name-block" role="alert">
        <b>There's already a {blockedFor} in this group.</b><br />
        Two people with one name makes it impossible to tell who a plate belongs to. Add something to
        tell you apart.
        {#if suggestions.length > 0}
          <div class="chips" style="margin-top:10px">
            {#each suggestions as s (s)}
              <button type="button" class="chip chip--pick" onclick={() => useSuffix(s)}>{s}</button>
            {/each}
          </div>
        {/if}
      </div>
    {/if}

    {#if suggestion}
      <!-- Fuzzy match SUGGESTS, never blocks (§3.2) — a false-positive block is a dead end. -->
      <div class="note note--blue">
        <b>There's already a {suggestion.name} here.</b>
        {#if suggestion.evidence.length > 0}
          {@const e = suggestion.evidence[0]}
          She claimed {e.title} on {shortDate(e.date)} ({money(e.amountSubunits, store.currency)}).
        {/if}
        Did you mean her?
        <div class="chips" style="margin-top:10px">
          <button type="button" class="chip chip--pick chip--picked" onclick={() => acceptSuggestion(suggestion!)}>
            Yes, that's me
          </button>
          <button type="button" class="chip chip--pick" onclick={() => submit(true)}>
            No, I'm someone else
          </button>
        </div>
      </div>
    {/if}

    {#if hint}
      <div class="note note--amber" role="alert">{hint}</div>
    {/if}

    <button
      type="button"
      class="btn"
      class:btn--off={!canSubmit}
      aria-busy={store.busy}
      onclick={() => submit()}
    >
      {store.busy ? 'Saving…' : 'Continue'}
    </button>

    {#if store.candidates.length > 0}
      <div class="spacer"></div>
      <button type="button" class="btn btn--ghost btn--sm" onclick={() => (store.phase = 'pick_name')}>
        {isBlocked ? `Wait, I am that ${blockedFor}` : "Wait, I'm already in this group"}
      </button>
    {/if}

    <p class="sub center" style="margin-top:16px">Have Evenly? Open the bill there instead.</p>
  </div>
</div>
