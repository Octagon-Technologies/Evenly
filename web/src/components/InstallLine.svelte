<script lang="ts">
  /**
   * Spec §2.8: one line at the foot of the page — a hairline, a benefit, a link. Not a card, not a
   * button, no icon, nothing to dismiss. The guest is mid-task and did not come here for us; a
   * card-sized pitch competing with the primary action reads as a paywall and is the single most
   * likely thing to make someone abandon the bill.
   *
   * The copy is benefit-first and never mentions "downloading".
   */
  import { storeUrl, PRE_LAUNCH } from '../lib/format.ts';

  interface Props {
    benefit: string;
  }

  const { benefit }: Props = $props();
</script>

<p class="appline">
  {benefit}<br />
  <a href={storeUrl()} rel="noopener noreferrer">{PRE_LAUNCH ? 'Get early access →' : 'Get Evenly →'}</a>
</p>
