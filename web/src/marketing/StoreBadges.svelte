<script lang="ts">
  /**
   * The two store badges, reused in the hero and the closing band — one copy, so they can't drift.
   * These are Apple's and Google's official badge artwork (fetched from their own badge-generator
   * endpoints), not a hand-built button — both platforms require their unmodified badge for
   * store-download links. `variant="light"` (default) is Apple's black badge for use on light
   * backgrounds; `variant="dark"` swaps in Apple's white badge for the dark band, matching what
   * Google's badge already renders as (black pill, always).
   */
  import { APP_STORE_URL, PLAY_STORE_URL } from '../lib/format.ts';
  import appStoreBlack from './assets/app-store-badge.svg';
  import appStoreWhite from './assets/app-store-badge-white.svg';
  import googlePlayBadge from './assets/google-play-badge.png';

  export let variant: 'light' | 'dark' = 'light';
</script>

<div class="site-badges">
  <a class="site-badge" href={APP_STORE_URL}>
    <img
      src={variant === 'dark' ? appStoreWhite : appStoreBlack}
      alt="Download on the App Store"
      height="40"
    />
  </a>
  <a class="site-badge" href={PLAY_STORE_URL}>
    <img src={googlePlayBadge} alt="Get it on Google Play" height="40" />
  </a>
</div>
