import './app.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { store } from './lib/store.svelte.ts';
import { billTokenFromLocation } from './lib/router.ts';

async function boot() {
  // Dev-only walkthrough fixture: `?mock` serves a canned bill in memory so every frame can be
  // walked without a live link. Guarded by `import.meta.env.DEV`, so it is dead code in a build.
  if (import.meta.env.DEV && new URLSearchParams(location.search).has('mock')) {
    const { installMockTransport } = await import('./lib/mock.ts');
    installMockTransport();
  }
  await store.start(billTokenFromLocation());
}

void boot();

export default mount(App, { target: document.getElementById('app')! });
