<script lang="ts">
  /**
   * `/j/:token`, the group invite page: join the group from the link, with no app and no account.
   * Mockup: `design/web-group-join-mockup.html`. State lives in `lib/join.svelte.ts`.
   *
   * Someone with the app installed never sees this page (the link opens the app as a universal link /
   * App Link), so the page is built for the friend without it. "Open in Evenly" stays as the secondary
   * action for the cases where the OS hands the link to the browser anyway (some in-app browsers).
   *
   * The landing proves the group is real before asking for anything (emoji, name, who is in it), the
   * same rule as the bill page's header (spec §3.1).
   */
  import { onMount, untrack } from 'svelte';
  import ErrorBanner from '../components/ErrorBanner.svelte';
  import InstallLine from '../components/InstallLine.svelte';
  import JoinPick from '../screens/join/JoinPick.svelte';
  import JoinName from '../screens/join/JoinName.svelte';
  import { JoinStore } from '../lib/join.svelte.ts';

  const { token }: { token: string } = $props();
  // The route is resolved once per page load, so the token never changes under this component.
  const store = new JoinStore(untrack(() => token));

  onMount(() => {
    store.load();
  });

  const who = $derived.by(() => {
    const h = store.header;
    if (!h || h.memberCount === 0) return null;
    const names = h.memberNames.slice(0, 2);
    const rest = h.memberCount - names.length;
    if (rest <= 0) return names.join(' and ');
    return `${names.join(', ')} and ${rest} ${rest === 1 ? 'other' : 'others'}`;
  });
</script>

<div class="claim-shell">
  {#if store.phase === 'loading'}
    <div class="page">
      <div class="scroll center" style="padding-top:80px">
        <div class="spin" style="margin:0 auto 14px"></div>
        <p class="sub">Opening the invite…</p>
      </div>
    </div>
  {:else if store.phase === 'landing' && store.header}
    <div class="page">
      <div class="scroll">
        <div class="join-emoji" aria-hidden="true">{store.header.emoji}</div>
        <div class="hdr" style="padding-top:0">
          <div class="eyebrow">You're invited</div>
          <div class="h1">Join {store.header.groupName} on Evenly</div>
          <div class="sub">Split bills with friends and settle up, without the awkward maths.</div>
        </div>
        {#if who}
          <div class="join-faces" aria-hidden="true">
            {#each store.header.memberNames as n, i (i)}
              <span class="join-av">{n.charAt(0).toUpperCase()}</span>
            {/each}
          </div>
          <p class="sub">{who} {store.header.memberCount === 1 ? 'is' : 'are'} in this group</p>
        {/if}

        <div class="spacer"></div>

        <button type="button" class="btn" onclick={() => store.start()}>Join {store.header.groupName}</button>
        <a class="btn btn--ghost btn--sm join-open" href={`splitevenly://j/${token}`}>I have the app, open it</a>
        <p class="sub center" style="margin-top:12px">No account or download needed to join.</p>
      </div>
    </div>
  {:else if store.phase === 'pick_name'}
    <JoinPick {store} />
  {:else if store.phase === 'name_entry'}
    <JoinName {store} />
  {:else if store.phase === 'joined' && store.header}
    <div class="page">
      <div class="scroll">
        <div class="join-check" aria-hidden="true">✓</div>
        <div class="hdr" style="padding-top:0">
          <div class="eyebrow">{store.header.groupName}</div>
          <div class="h1">{store.returning ? `You're already in, ${store.me}` : `You're in, ${store.me}`}</div>
          <div class="sub">The group can now add you to expenses.</div>
        </div>
        <div class="note note--green">
          When someone shares a bill with you, open its link and tap what you had. This browser will
          remember you.
        </div>
        {#if store.error}
          <ErrorBanner message={store.error} />
        {/if}

        <div class="spacer"></div>

        <button type="button" class="btn btn--ghost btn--sm" onclick={() => store.forget()}>
          Not {store.me}? Use a different name
        </button>
        <InstallLine benefit="See what you owe and settle up in the app." />
      </div>
    </div>
  {:else if store.phase === 'claimed_elsewhere'}
    <div class="page">
      <div class="scroll">
        <div class="hdr" style="padding-bottom:10px">
          <div class="h2">You're already in this group on Evenly</div>
          <div class="sub">This browser's name was linked to an Evenly account, so the group lives in the app now.</div>
        </div>
        <a class="btn" href={`splitevenly://j/${token}`}>Open Evenly</a>
        <div class="spacer"></div>
        <button type="button" class="btn btn--ghost btn--sm" onclick={() => store.forget()}>
          That wasn't me. Join with my own name
        </button>
      </div>
    </div>
  {:else if store.phase === 'failed'}
    <div class="page">
      <div class="scroll">
        <div class="hdr" style="padding-bottom:10px">
          <div class="h2">Couldn't open the invite</div>
          <div class="sub">Check your connection and try again.</div>
        </div>
        <button type="button" class="btn" onclick={() => store.load()}>Try again</button>
      </div>
    </div>
  {:else}
    <div class="page">
      <div class="scroll">
        <div class="hdr" style="padding-bottom:10px">
          <div class="h2">This invite link doesn't work anymore</div>
          <div class="sub">
            The group may have been deleted, or the link was changed. Ask whoever invited you to send it again.
          </div>
        </div>
        <InstallLine benefit="Split bills with friends and settle up." />
      </div>
    </div>
  {/if}
</div>

<style>
  .join-emoji {
    width: 56px;
    height: 56px;
    border-radius: 16px;
    background: var(--blue-tint);
    display: grid;
    place-items: center;
    font-size: 28px;
    margin: 8px 0 14px;
  }
  .join-check {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    background: var(--green-tint);
    color: var(--green);
    display: grid;
    place-items: center;
    font-size: 26px;
    font-weight: 700;
    margin: 8px 0 14px;
  }
  .join-faces {
    display: flex;
    margin: 4px 0 6px;
  }
  .join-av {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    background: var(--blue-tint);
    color: var(--blue);
    border: 2px solid var(--page);
    font-size: 12px;
    font-weight: 600;
    display: grid;
    place-items: center;
    margin-left: -6px;
  }
  .join-av:first-child {
    margin-left: 0;
  }
  .join-open {
    display: flex;
    align-items: center;
    justify-content: center;
    text-decoration: none;
    margin-top: 8px;
  }
</style>
