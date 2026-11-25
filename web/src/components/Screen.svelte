<script lang="ts">
  /**
   * A full screen pushed over the current one, with the app's `EvTopBar`: a back (or close) button, a
   * title and an optional subtitle. Used where the app navigates to a screen rather than opening a
   * sheet (expense detail, add/edit expense). Escape goes back.
   */
  import type { Snippet } from 'svelte';
  import Icon from './Icon.svelte';

  interface Props {
    title: string;
    subtitle?: string;
    nav?: 'back' | 'close';
    onback: () => void;
    children: Snippet;
    /** A docked bar under the scroll area (a Save button), above the home indicator. */
    dock?: Snippet;
  }

  const { title, subtitle, nav = 'back', onback, children, dock }: Props = $props();

  let panel = $state<HTMLDivElement | null>(null);
  $effect(() => {
    panel?.focus();
  });

  function onkeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onback();
    }
  }
</script>

<svelte:window on:keydown={onkeydown} />

<div class="screen" role="dialog" aria-modal="true" aria-label={title} tabindex="-1" bind:this={panel}>
  <header class="topbar">
    <button type="button" class="tb-nav" aria-label={nav === 'back' ? 'Back' : 'Close'} onclick={onback}>
      <Icon name={nav === 'back' ? 'back' : 'close'} size={24} />
    </button>
    <div class="tb-titles">
      <div class="tb-title">{title}</div>
      {#if subtitle}<div class="tb-sub">{subtitle}</div>{/if}
    </div>
  </header>
  <div class="screen-scroll">
    {@render children()}
  </div>
  {#if dock}
    <div class="screen-dock">{@render dock()}</div>
  {/if}
</div>

<style>
  .screen {
    position: fixed;
    inset: 0;
    z-index: 15;
    max-width: 480px;
    margin: 0 auto;
    background: var(--page);
    display: flex;
    flex-direction: column;
    animation: push 0.2s ease-out;
  }
  .screen:focus {
    outline: none;
  }
  @keyframes push {
    from {
      transform: translateX(24px);
      opacity: 0;
    }
  }
  .screen-scroll {
    flex: 1;
    overflow-y: auto;
    padding: 16px 16px calc(24px + env(safe-area-inset-bottom));
  }
  .screen-dock {
    flex: none;
    padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
    border-top: 1px solid var(--border);
    background: var(--page);
  }
</style>
