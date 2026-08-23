// Thin wrapper over the `window.__posthog` global set up (or not) by the snippet in `index.html`.
// A no-op when PostHog isn't configured or hasn't finished its async import yet — a claim guest
// interacting before that import resolves just means that one event is missed, never an error.
declare global {
  interface Window {
    __posthog?: { capture(event: string, properties?: Record<string, unknown>): void };
  }
}

export function capture(event: string, properties?: Record<string, unknown>): void {
  window.__posthog?.capture(event, properties);
}
