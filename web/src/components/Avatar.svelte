<script lang="ts">
  import { initial } from '../lib/format.ts';

  interface Props {
    name: string;
  }

  const { name }: Props = $props();
</script>

<span class="av" aria-hidden="true">{initial(name)}</span>
