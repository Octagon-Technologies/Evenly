<script lang="ts">
  /** The two store badges, reused in the hero and the closing band — one copy, so they can't drift. */
  import { APP_STORE_URL, PLAY_STORE_URL } from '../lib/format.ts';
</script>

<div class="site-badges">
  <a class="site-badge" href={APP_STORE_URL}>
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M16.365 1.43c0 1.14-.415 2.09-1.245 2.85-.885.81-1.94 1.28-2.99 1.19-.11-1.11.43-2.145 1.24-2.87.87-.78 2.03-1.24 2.99-1.17zm3.03 17.4c-.53 1.19-.78 1.72-1.46 2.77-.95 1.47-2.29 3.3-3.95 3.31-1.47.02-1.85-.96-3.85-.95-2 .01-2.42.97-3.9.95-1.66-.02-2.93-1.67-3.88-3.14C.13 17.99-.65 13.51 1.05 10.4c.9-1.63 2.5-2.67 4.24-2.7 1.5-.02 2.9 1.01 3.85 1.01.94 0 2.66-1.25 4.49-1.07.76.03 2.9.31 4.27 2.32-.11.07-2.55 1.49-2.52 4.44.03 3.53 3.1 4.7 3.14 4.72-.03.08-.49 1.68-1.6 3.2z"
        fill="currentColor"
      />
    </svg>
    <span><span class="si">Download on the</span><br /><span class="st">App Store</span></span>
  </a>
  <a class="site-badge" href={PLAY_STORE_URL}>
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M4 3l16 9-16 9V3z" fill="currentColor" />
    </svg>
    <span><span class="si">Get it on</span><br /><span class="st">Google Play</span></span>
  </a>
</div>
