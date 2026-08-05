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

export default mount(App, { target: document.getElementById('app')! });
