<script lang="ts">
  /**
   * "Add a screenshot" on a payment (docs/features/payment-proof.md): a big labelled tile, optional, never
   * in the way of recording. On a phone the file input opens the photo library; on a laptop a pasted
   * screenshot (Cmd/Ctrl+V while the sheet is open) works too.
   */
  import { toProofFile, ProofImageError } from '../lib/proofImage.ts';
  import type { ProofFile } from '../lib/api.ts';

  interface Props {
    otherName: string;
    value: ProofFile | null;
    onchange: (file: ProofFile | null) => void;
  }

  const { otherName, value, onchange }: Props = $props();

  let input = $state<HTMLInputElement | null>(null);
  let problem = $state<string | null>(null);
  let reading = $state(false);
  const preview = $derived(value ? `data:${value.mimeType};base64,${value.data}` : null);

  async function take(file: File | null | undefined) {
    if (!file) return;
    problem = null;
    reading = true;
    try {
      onchange(await toProofFile(file));
    } catch (e) {
      problem = e instanceof ProofImageError ? e.message : "That image couldn't be read. Try another one.";
    } finally {
      reading = false;
    }
  }

  function onpaste(event: ClipboardEvent) {
    const file = Array.from(event.clipboardData?.files ?? []).find((f) => f.type.startsWith('image/'));
    if (file) {
      event.preventDefault();
      void take(file);
    }
  }
</script>

<svelte:window {onpaste} />

<input
  bind:this={input}
  type="file"
  accept="image/*"
  hidden
  onchange={(e) => {
    void take((e.currentTarget as HTMLInputElement).files?.[0]);
    (e.currentTarget as HTMLInputElement).value = '';
  }}
/>
<div class="proof" class:proof--has={!!value}>
  <button type="button" class="proof__main" onclick={() => input?.click()} disabled={reading}>
    {#if preview}
      <img class="proof__thumb" src={preview} alt="Payment screenshot" />
    {:else}
      <span class="proof__icon" aria-hidden="true">＋</span>
    {/if}
    <span class="proof__text">
      <b>{reading ? 'Reading…' : value ? 'Screenshot attached' : 'Add a screenshot'}</b>
      <span>{value ? `${otherName} sees it with the payment. Tap to change it.` : `Optional. ${otherName} sees it with the payment.`}</span>
    </span>
  </button>
  {#if value}
    <button type="button" class="proof__x" aria-label="Remove screenshot" onclick={() => onchange(null)}>✕</button>
  {/if}
</div>
{#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}

<style>
  .proof {
    display: flex;
    align-items: center;
    gap: 6px;
    border: 1.5px dashed var(--border-strong);
    border-radius: 14px;
    padding: 4px 6px 4px 4px;
    margin-bottom: 12px;
  }
  .proof--has {
    border-style: solid;
    border-color: var(--border);
    background: var(--surface);
  }
  .proof__main {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px;
    background: none;
    border: 0;
    text-align: left;
    font: inherit;
    color: inherit;
    cursor: pointer;
  }
  .proof__icon {
    width: 36px;
    height: 36px;
    border-radius: 10px;
    background: var(--blue-tint);
    color: var(--blue);
    display: grid;
    place-items: center;
    font-size: 18px;
    flex: none;
  }
  .proof__thumb {
    width: 40px;
    height: 54px;
    border-radius: 8px;
    object-fit: cover;
    flex: none;
  }
  .proof__text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    font-size: 12.5px;
    color: var(--ink-2);
  }
  .proof__text b {
    color: var(--ink);
    font-size: 14px;
  }
  .proof__x {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: 0;
    background: none;
    color: var(--ink-2);
    cursor: pointer;
    flex: none;
  }
</style>
