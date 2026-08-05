<script lang="ts">
  /**
   * Revoked, deleted, or never real (spec §3.1, E28–E30). **No bill data**: the whole point of a
   * revocable link is that revoking it stops showing the bill, so this screen knows nothing and says
   * nothing except how to get unstuck.
   */
  import InstallLine from '../components/InstallLine.svelte';

  interface Props {
    /** `no_token` reaches the same screen — a mistyped or truncated link is indistinguishable from a dead one. */
    reason: 'gone' | 'no_token' | 'claimed_elsewhere';
    name?: string | null;
  }

  const { reason, name = null }: Props = $props();
</script>

<div class="page">
  <div class="scroll">
    {#if reason === 'claimed_elsewhere'}
      <div class="hdr" style="padding-bottom:10px">
        <div class="h2">{name ?? 'That name'} is on Evenly now</div>
        <div class="sub">
          Someone linked this name to an Evenly account, so it's theirs to manage from the app.
          Everything you claimed stayed on the bill.
        </div>
      </div>
      <div class="note note--blue">
        If that was you, open the bill in Evenly. If it wasn't, ask whoever shared the link to add you
        again.
      </div>
      <InstallLine benefit="Your bills and who you split them with, in one place." />
    {:else}
      <div class="hdr" style="padding-bottom:10px">
        <div class="h2">This link no longer works</div>
        <div class="sub">
          {reason === 'no_token'
            ? "That address isn't a bill link. Links look like split-evenly.app/b/… — try scanning the QR again."
            : 'It was turned off, or the bill it pointed at is gone.'}
        </div>
      </div>
      <div class="note note--blue">Ask whoever shared it for a new one.</div>
      <InstallLine benefit="Split a bill without passing a link around at all." />
    {/if}
  </div>
</div>
