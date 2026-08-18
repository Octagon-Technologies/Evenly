import './app.css';
import './marketing.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { store } from './lib/store.svelte.ts';
import { resolveRoute } from './lib/router.ts';

async function boot() {
  // Dev-only walkthrough fixture: `?mock` serves a canned bill in memory so every frame can be
  // walked without a live link. Guarded by `import.meta.env.DEV`, so it is dead code in a build.
  if (import.meta.env.DEV && new URLSearchParams(location.search).has('mock')) {
    const { installMockTransport } = await import('./lib/mock.ts');
    installMockTransport();
  }
  // The marketing pages (home, privacy, terms) have no bill session to open — only a `/b/:token`
  // route starts one. Starting it regardless would leave the store mid-fetch under pages that never
  // read `store.phase`, for no reason.
  const route = resolveRoute();
  if (route.kind === 'bill') await store.start(route.token);
}

void boot();

// `prerender.mjs` ships each marketing route's HTML inside `#app` so a crawler (and a slow
// connection) sees the page without running this bundle. That markup is a *prerender*, not
// hydration state: empty it, then mount, and the two never have to agree. Hydrating instead would
// save a few milliseconds and risk a mismatch on a page whose hero is an animation.
const target = document.getElementById('app')!;
target.replaceChildren();

export default mount(App, { target });
