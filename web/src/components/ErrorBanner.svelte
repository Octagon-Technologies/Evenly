<script lang="ts">
  /**
   * A failed write shows a retry, never an optimistic success (spec §6). Pretending a claim landed
   * when it did not is worse than a spinner, because the guest walks away believing they are done.
   */
  interface Props {
    message: string;
    onretry?: (() => void) | null;
  }

  const { message, onretry = null }: Props = $props();
</script>

<div class="note note--red" role="alert">
  <b>{message}</b>
  {#if onretry}
    <div class="chips" style="margin-top:10px">
      <button type="button" class="chip chip--pick" onclick={onretry}>Try again</button>
    </div>
  {/if}
</div>
