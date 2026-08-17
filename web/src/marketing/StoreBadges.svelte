<script lang="ts">
  /**
   * The "get Evenly" call to action, reused in the hero and the closing band. One copy, so they
   * can't drift.
   *
   * It has two forms, chosen by `PRE_LAUNCH` in `format.ts`:
   *
   * - **Before launch** a single "Get early access" button to `/waitlist`. Deliberately NOT the store
   *   badges pointed at the waitlist: Apple's and Google's badge guidelines both expect their badge
   *   to link to the store listing, and a badge reading "Download on the App Store" that lands on an
   *   email form is a promise the page cannot keep.
   * - **After launch** Apple's and Google's official badge artwork, which is what both platforms
   *   require for a store-download link (not a hand-built button). `variant="light"` (default) is
   *   Apple's black badge for light backgrounds; `variant="dark"` swaps in Apple's white badge for
   *   the dark band, matching what Google's badge already renders as (black pill, always).
   */
  import { appStoreLink, playStoreLink, WAITLIST_URL, PRE_LAUNCH } from '../lib/format.ts';
  import appStoreBlack from './assets/app-store-badge.svg';
  import appStoreWhite from './assets/app-store-badge-white.svg';
  import googlePlayBadge from './assets/google-play-badge.png';

  export let variant: 'light' | 'dark' = 'light';
</script>

{#if PRE_LAUNCH}
  <div class="site-badges">
    <a class="site-cta-btn" class:site-cta-on-dark={variant === 'dark'} href={WAITLIST_URL}>
      Get early access
    </a>
  </div>
{:else}
  <div class="site-badges">
    <a class="site-badge" href={appStoreLink()}>
      <img
        src={variant === 'dark' ? appStoreWhite : appStoreBlack}
        alt="Download on the App Store"
        height="40"
      />
    </a>
    <a class="site-badge" href={playStoreLink()}>
      <img src={googlePlayBadge} alt="Get it on Google Play" height="40" />
    </a>
  </div>
{/if}
