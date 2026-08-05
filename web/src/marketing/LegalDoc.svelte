<script lang="ts">
  /** Shared shell for Privacy and Terms: eyebrow, serif title, meta line, italic pull-quote summary,
      then whatever numbered sections the page passes in. */
  import type { Snippet } from 'svelte';

  const {
    title,
    meta,
    summary,
    children,
  }: { title: string; meta: string; summary: string; children: Snippet } = $props();
</script>

<div class="site-doc">
  <div class="site-doc-eyebrow">Legal</div>
  <h1>{title}</h1>
  <div class="site-doc-meta">{meta}</div>
  <p class="site-doc-summary">"{summary}"</p>
  {@render children()}
</div>
