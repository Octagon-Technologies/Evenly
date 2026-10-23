<script lang="ts">
  /**
   * Typing a name on the invite page. Same rules as the bill flow's `NameEntry` (spec §2.4): an exact
   * duplicate is blocked with one-tap suffixes, a near match is offered as "did you mean them?" and never
   * blocks, and the way back to the list is always on screen.
   *
   * "Join group" is never `disabled` (root AGENTS.md §7): it stays live and says what is missing on tap.
   */
  import ErrorBanner from '../../components/ErrorBanner.svelte';
  import { money, shortDate } from '../../lib/format.ts';
  import type { Candidate } from '../../lib/api.ts';
  import type { JoinStore } from '../../lib/join.svelte.ts';

  const { store }: { store: JoinStore } = $props();

  let name = $state('');
  let blockedFor = $state<string | null>(null);
  let suggestions = $state<string[]>([]);
  let suggestion = $state<Candidate | null>(null);
  let hint = $state<string | null>(null);

  const trimmed = $derived(name.trim().replace(/\s+/g, ' '));
  const isBlocked = $derived(blockedFor !== null && trimmed.toLowerCase() === blockedFor.toLowerCase());
  const canSubmit = $derived(trimmed.length > 0 && !isBlocked);
  /** "I am that Mary" only when Mary is on the list: a blocked name can also belong to an account holder. */
  const blockedIsListed = $derived(
    isBlocked && store.candidates.some((c) => c.name.toLowerCase() === blockedFor!.toLowerCase()),
  );

  async function submit(force = false) {
    if (!canSubmit) {
      hint = trimmed.length === 0 ? 'Type your name first, so the group knows who you are.' : null;
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
    }
  }

  function useSuffix(value: string) {
    name = value;
    blockedFor = null;
    suggestions = [];
  }
</script>

<div class="page">
  <div class="scroll">
    <div class="hdr">
      <div class="eyebrow">{store.header?.groupName}</div>
      <div class="h1">What should the group call you?</div>
    </div>

    {#if store.error}
      <ErrorBanner message={store.error} />
    {/if}

    <div class="field">
      <label class="flbl" for="join-name">Your name</label>
      <!-- svelte-ignore a11y_autofocus -->
      <input
        id="join-name"
        class="inp"
        class:inp--bad={isBlocked}
        type="text"
        maxlength="40"
        autocomplete="name"
        enterkeyhint="go"
        autofocus
        aria-describedby={isBlocked ? 'join-name-block' : undefined}
        bind:value={name}
        onkeydown={(e) => e.key === 'Enter' && submit()}
      />
    </div>

    {#if isBlocked}
      <div class="note note--red" id="join-name-block" role="alert">
        <b>There's already a {blockedFor} in this group.</b><br />
        Add something so people can tell you apart.
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
      <div class="note note--blue">
        <b>There's already a {suggestion.name} here.</b>
        {#if suggestion.evidence.length > 0}
          {@const e = suggestion.evidence[0]}
          They were on {e.title} on {shortDate(e.date)}{e.currency ? ` (${money(e.amountSubunits, e.currency)})` : ''}.
        {/if}
        Is that you?
        <div class="chips" style="margin-top:10px">
          <button type="button" class="chip chip--pick chip--picked" onclick={() => store.claim(suggestion!.userId)}>
            Yes, that's me
          </button>
          <button type="button" class="chip chip--pick" onclick={() => submit(true)}>No, I'm someone else</button>
        </div>
      </div>
    {/if}

    {#if hint}
      <div class="note note--amber" role="alert">{hint}</div>
    {/if}

    <button type="button" class="btn" class:btn--off={!canSubmit} aria-busy={store.busy} onclick={() => submit()}>
      {store.busy ? 'Joining…' : 'Join group'}
    </button>

    {#if store.candidates.length > 0}
      <div class="spacer"></div>
      <button type="button" class="btn btn--ghost btn--sm" onclick={() => (store.phase = 'pick_name')}>
        {blockedIsListed ? `Wait, I am that ${blockedFor}` : "Wait, I'm already in this group"}
      </button>
    {/if}
  </div>
</div>
