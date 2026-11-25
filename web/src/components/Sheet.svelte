<script lang="ts">
  /**
   * `EvSheetSurface` (ui/components/EvContainers.kt), iOS branch: a floating card with a drag handle,
   * a centred title row and an optional centred sub line. No close button, as in the app: tap outside,
   * Escape, or the sheet's own Done.
   *
   * A sheet here always sits between a guest and someone else's money, so it is a real dialog —
   * labelled, focus-trapped at the edges, dismissible with Escape and by tapping the scrim. Getting
   * out must never be harder than getting in.
   */
  import type { Snippet } from 'svelte';

  interface Props {
    title: string;
    sub?: string;
    /** False when the content draws its own heading (a hero amount, say). */
    showTitle?: boolean;
    onclose: () => void;
    children: Snippet;
  }

  const { title, sub, showTitle = true, onclose, children }: Props = $props();

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
    {#if showTitle}<div class="sheet-title">{title}</div>{/if}
    {#if sub}<p class="sheet-sub">{sub}</p>{/if}
    {@render children()}
  </div>
</div>
