import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

/**
 * Static SPA. Every `/b/:token` URL serves the same `index.html` and the token is read from
 * `location.pathname` — see `src/lib/router.ts` and `public/_redirects` for the host-side rewrite.
 *
 * `VITE_WEB_CLAIM_URL` points at the deployed `web-claim` edge function. There is deliberately no
 * Supabase URL or anon key here: the bundle ships neither (spec §4.1).
 */
export default defineConfig({
  plugins: [svelte()],
  build: {
    target: 'es2022',
    outDir: 'dist',
  },
  server: {
    // 5177 by default, because that is the port `USE_LOCAL_WEB_CLAIM_HOST` points the QR at. `PORT`
    // overrides it so a second session can run its own copy without taking the one the QR expects.
    port: Number(process.env.PORT) || 5177,
    // Binds IPv4 too (Vite defaults to IPv6-only localhost), so 127.0.0.1 resolves from the iOS
    // Simulator, which shares the host's network stack rather than having its own loopback.
    host: true,
  },
});
