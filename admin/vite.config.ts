import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

/**
 * Static SPA, deployed as its own Vercel project at `admin.split-evenly.app` (spec §2.2). Separate
 * from `web/` on purpose: a public visitor to split-evenly.app must never download admin code, which
 * is a structural guarantee rather than a policy.
 *
 * `VITE_SUPABASE_ANON_KEY` is the one place this differs from `web/`, which ships no Supabase client
 * at all. Here the client exists for exactly one job, Google OAuth, and reads no table with it. Every
 * byte of data comes from `VITE_ADMIN_FN_URL` after that function's own allowlist check.
 */
export default defineConfig({
  plugins: [svelte()],
  build: {
    target: 'es2022',
    outDir: 'dist',
  },
  server: {
    // 5178, one past the claim flow's 5177, so both can run at once.
    port: Number(process.env.PORT) || 5178,
  },
});
