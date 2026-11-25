<script lang="ts">
  /**
   * The few `EvIcons` the web uses, as 24px stroke paths (`ui/components/icon/`). Stroke icons in
   * `currentColor`, so a parent sets the tint the way the app passes `tint`.
   */
  type Name =
    | 'back'
    | 'chevR'
    | 'chevD'
    | 'chevU'
    | 'plus'
    | 'check'
    | 'receipt'
    | 'swap'
    | 'share'
    | 'edit'
    | 'checkCircle'
    | 'close'
    | 'food'
    | 'cart'
    | 'car'
    | 'bed'
    | 'ticket'
    | 'tag'
    | 'bolt'
    | 'sparkle'
    | 'wallet'
    | 'camera';

  interface Props {
    name: Name;
    size?: number;
  }

  const { name, size = 20 }: Props = $props();

  const PATHS: Record<Name, string> = {
    back: 'M15 18l-6-6 6-6',
    chevR: 'M9 6l6 6-6 6',
    chevD: 'M6 9l6 6 6-6',
    chevU: 'M18 15l-6-6-6 6',
    plus: 'M12 5v14M5 12h14',
    check: 'M5 12.5l4.5 4.5L19 7.5',
    receipt: 'M6 3h12v18l-3-2-3 2-3-2-3 2zM9 8h6M9 12h6M9 16h3',
    swap: 'M7 7h12l-3-3M17 17H5l3 3',
    share: 'M12 3v12M8 7l4-4 4 4M5 13v6a2 2 0 002 2h10a2 2 0 002-2v-6',
    edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
    checkCircle: 'M12 21a9 9 0 100-18 9 9 0 000 18zM8 12.5l3 3 5-6',
    close: 'M6 6l12 12M18 6L6 18',
    food: 'M7 3v8M5 3v5a2 2 0 004 0V3M7 11v10M17 3c-2 0-3 2-3 6h3v12',
    cart: 'M3 4h2l2.5 11h10L20 8H6.5M9 20h.01M17 20h.01',
    car: 'M5 16V11l2-5h10l2 5v5M5 16h14M5 16v2M19 16v2M3 11h18M8 13h.01M16 13h.01',
    bed: 'M3 18V7M3 13h18v5M21 13a3 3 0 00-3-3h-7v3M7 11h.01',
    ticket: 'M4 8a2 2 0 002-2h12a2 2 0 002 2v2a2 2 0 000 4v2a2 2 0 00-2 2H6a2 2 0 00-2-2v-2a2 2 0 000-4zM13 6v12',
    tag: 'M3 12V4h8l9 9-8 8zM7.5 7.5h.01',
    bolt: 'M13 3L5 14h6l-1 7 8-11h-6z',
    sparkle: 'M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z',
    camera: 'M4 8h3l2-3h6l2 3h3v11H4zM12 17a4 4 0 100-8 4 4 0 000 8z',
    wallet: 'M4 7a2 2 0 012-2h12v4M4 7v10a2 2 0 002 2h14V9H6a2 2 0 01-2-2zM16 14h.01',
  };
</script>

<svg
  viewBox="0 0 24 24"
  width={size}
  height={size}
  fill="none"
  stroke="currentColor"
  stroke-width="2"
  stroke-linecap="round"
  stroke-linejoin="round"
  aria-hidden="true"
  style="flex:none"><path d={PATHS[name]} /></svg
>
