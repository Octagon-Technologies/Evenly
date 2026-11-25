<script lang="ts">
  /** `EvAvatar` (ui/components/EvAvatar.kt): Xs 22 by default, `sm` 28, `md` 36, `lg` 56; `me` is solid blue. */
  import { initial } from '../lib/format.ts';

  interface Props {
    name: string;
    me?: boolean;
    size?: 'xs' | 'sm' | 'md' | 'lg';
  }

  const { name, me = false, size = 'xs' }: Props = $props();
</script>

<span class="av" class:av--me={me} class:av--sm={size === 'sm'} class:av--md={size === 'md'} class:av--lg={size === 'lg'} aria-hidden="true"
  >{initial(name)}</span
>
