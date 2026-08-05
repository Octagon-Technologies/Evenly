<script lang="ts">
  /**
   * The bottom sheet the mockup draws: scrim, grab handle, content.
   *
   * A sheet here always sits between a guest and someone else's money, so it is a real dialog —
   * labelled, focus-trapped at the edges, dismissible with Escape and by tapping the scrim. Getting
   * out must never be harder than getting in.
   */
  import type { Snippet } from 'svelte';

  interface Props {
    title: string;
    onclose: () => void;
    children: Snippet;
  }

  const { title, onclose, children }: Props = $props();

  let panel = $state<HTMLDivElement | null>(null);

  $effect(() => {
    // Send focus into the sheet so a screen reader and a keyboard both land on the new content.
    panel?.focus();
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  });

  function onkeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onclose();
    }
  }
</script>

<svelte:window on:keydown={onkeydown} />

<!-- svelte-ignore a11y_click_events_have_key_events (Escape is handled on the window above) -->
<div class="scrim" onclick={(e) => e.target === e.currentTarget && onclose()} role="presentation">
  <div
    class="sheet"
    role="dialog"
    aria-modal="true"
    aria-label={title}
    tabindex="-1"
    bind:this={panel}
  >
    <div class="grab"></div>
    {@render children()}
  </div>
</div>
