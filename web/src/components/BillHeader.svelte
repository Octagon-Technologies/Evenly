<script lang="ts">
  /**
   * Spec §3.1: the header always shows group name, venue, item count, bill total and claim progress
   * **before** asking for anything. The page has to prove it is real before it asks for a name — a
   * stranger's link demanding your name with nothing to show for itself is a phishing page.
   */
  import type { BillHeader } from '../lib/api.ts';
  import { expiresIn, money } from '../lib/format.ts';

  interface Props {
    header: BillHeader;
    title: string;
    /** Optional line under the venue row. */
    sub?: string;
    /** Landing screens prove the bill exists; the claim list has its own progress bar instead. */
    showProof?: boolean;
  }

  const { header, title, sub, showProof = true }: Props = $props();

  /*
   * The claim list hides the grand total on purpose. Its progress bar measures claimed food against
   * the food on the bill, and a grand total that includes tax and tip sitting directly above it reads
   * as two contradictory answers to "how big is this bill?". The landing screens show the real total
   * (that is what proves the page is real); the summary shows what you owe.
   */

  const pct = $derived(
    header.participantCount > 0 ? Math.round((header.claimedCount / header.participantCount) * 100) : 0,
  );
</script>

<div class="hdr">
  <div class="eyebrow">{header.groupName}</div>
  <div class="h1">{title}</div>
  <div class="sub">
    {header.title} · {header.itemCount}
    {header.itemCount === 1 ? 'item' : 'items'}{#if showProof}{' · '}<b class="mono"
        >{money(header.totalSubunits, header.currency)}</b
      >{/if}
  </div>
  {#if sub}<div class="sub">{sub}</div>{/if}
</div>

{#if showProof && header.participantCount > 0}
  <div class="prog" style="margin-bottom:14px">
    <div
      class="trk"
      role="progressbar"
      aria-valuenow={pct}
      aria-valuemin="0"
      aria-valuemax="100"
      aria-label="How many people have finished claiming"
    >
      <i style="width:{pct}%"></i>
    </div>
    <div class="prlbl">
      <span>{header.claimedCount} of {header.participantCount} finished</span>
      <span>{expiresIn(header.expiresAt)}</span>
    </div>
  </div>
{/if}
