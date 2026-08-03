<script lang="ts">
  import { money } from '../lib/format.ts';

  interface Props {
    claimedSubunits: number;
    totalSubunits: number;
    currency: string;
    peopleClaimed: number;
    peopleTotal: number;
  }

  const { claimedSubunits, totalSubunits, currency, peopleClaimed, peopleTotal }: Props = $props();

  const pct = $derived(totalSubunits > 0 ? Math.min(100, Math.round((claimedSubunits / totalSubunits) * 100)) : 0);
</script>

<div class="prog">
  <div
    class="trk"
    role="progressbar"
    aria-valuenow={pct}
    aria-valuemin="0"
    aria-valuemax="100"
    aria-label="How much of the bill is claimed"
  >
    <i style="width:{pct}%"></i>
  </div>
  <div class="prlbl">
    <span><b class="mono">{money(claimedSubunits, currency)}</b> of <span class="mono">{money(totalSubunits, currency)}</span></span>
    <span>{peopleClaimed} of {peopleTotal} claimed</span>
  </div>
</div>
