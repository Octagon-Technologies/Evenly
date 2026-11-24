<script lang="ts">
  /**
   * A receipt or payment screenshot full screen, the web twin of the app's `ReceiptViewerScreen`. A
   * payment screenshot is captioned with its payment, so the image never floats free of its amount.
   */
  interface Props {
    url: string;
    title: string;
    caption?: string | null;
    detail?: string | null;
    onclose: () => void;
    /** Shown only to whoever may take the image off. */
    onremove?: (() => void) | null;
  }

  const { url, title, caption = null, detail = null, onclose, onremove = null }: Props = $props();
  let confirming = $state(false);

  function onkeydown(event: KeyboardEvent) {
    // Capture phase + stopImmediatePropagation: the sheet underneath also closes on Escape, and one key
    // press should close only the image.
    if (event.key === 'Escape') {
      event.stopImmediatePropagation();
      onclose();
    }
  }
</script>

<svelte:window onkeydowncapture={onkeydown} />

<div class="viewer" role="dialog" aria-modal="true" aria-label={title}>
  <div class="viewer__bar">
    <button type="button" class="viewer__btn" aria-label="Close" onclick={onclose}>✕</button>
    <span class="viewer__title">{title}</span>
    {#if onremove}
      <button type="button" class="viewer__btn viewer__btn--text" onclick={() => (confirming ? onremove() : (confirming = true))}>
        {confirming ? 'Remove it' : 'Remove'}
      </button>
    {:else}
      <span class="viewer__btn" aria-hidden="true"></span>
    {/if}
  </div>
  <a class="viewer__img" href={url} target="_blank" rel="noopener noreferrer">
    <img src={url} alt={title} />
  </a>
  {#if caption}
    <div class="viewer__cap">
      <b>{caption}</b>
      {#if detail}<span>{detail}</span>{/if}
    </div>
  {/if}
</div>

<style>
  .viewer {
    position: fixed;
    inset: 0;
    z-index: 60;
    background: #0e1726;
    color: #fff;
    display: flex;
    flex-direction: column;
  }
  .viewer__bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 14px;
  }
  .viewer__title {
    font-size: 14px;
    font-weight: 600;
  }
  .viewer__btn {
    min-width: 44px;
    height: 44px;
    border-radius: 22px;
    border: 0;
    background: rgba(255, 255, 255, 0.2);
    color: #fff;
    font: inherit;
    cursor: pointer;
  }
  .viewer__btn--text {
    padding: 0 14px;
    font-size: 13px;
    font-weight: 600;
  }
  span.viewer__btn {
    background: none;
  }
  .viewer__img {
    flex: 1;
    min-height: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 8px 16px;
  }
  .viewer__img img {
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
    border-radius: 10px;
  }
  .viewer__cap {
    padding: 14px 18px 22px;
    display: flex;
    flex-direction: column;
    gap: 3px;
    font-size: 13px;
  }
  .viewer__cap span {
    color: #a7afc2;
  }
</style>
