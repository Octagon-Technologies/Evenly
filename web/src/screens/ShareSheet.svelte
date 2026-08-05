<script lang="ts">
  /**
   * Frame 6 — "Who had the juice with you?" (spec §3.4).
   *
   * Faces first, typing last: everyone on the bill is one tap, and `＋ Someone else` sits last and
   * smallest because it is the only path that can mint a duplicate person — so the §2.4 uniqueness
   * block applies there too.
   *
   * The resulting per-person amount is shown **live, before the commit**, for the same reason the
   * join sheet shows Mary's number: nobody should discover what they agreed to afterwards.
   *
   * This sheet is also where a guest takes herself off a line (§2.7, E12) — one gesture, whether she
   * got onto it by claiming or by joining.
   */
  import Sheet from '../components/Sheet.svelte';
  import Avatar from '../components/Avatar.svelte';
  import { money } from '../lib/format.ts';
  import type { LineView } from '../lib/lines.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  interface Props {
    store: ClaimStore;
    line: LineView;
    onclose: () => void;
  }

  const { store, line, onclose }: Props = $props();

  const others = $derived(store.peopleOnBill.filter((p) => p.userId !== store.identity?.userId));

  // Seeded from the line as it stands and then owned by this sheet: a poll landing mid-edit must not
  // silently re-tick someone the guest just un-ticked. Capturing the initial value is the point.
  // svelte-ignore state_referenced_locally
  let picked = $state<string[]>(line.chips.filter((c) => !c.isMe).map((c) => c.userId));
  let newNames = $state<string[]>([]);
  let typingName = $state(false);
  let draftName = $state('');
  let hint = $state<string | null>(null);

  const wayCount = $derived(1 + picked.length + newNames.length);
  /*
   * A share covers ONE unit of the line, not the whole line — `join_item_portion` creates a
   * single-unit portion. On a line of 2 katsu curries at $32, sharing splits one $16 plate, not $32.
   * Dividing the line total here would have quoted every guest double on any multi-unit line.
   */
  const unitSubunits = $derived(
    line.quantity > 0 ? Math.round(line.lineTotalSubunits / line.quantity) : line.lineTotalSubunits,
  );
  const eachSubunits = $derived(Math.round(unitSubunits / Math.max(wayCount, 1)));

  /** Says which unit is being split before it says the money — on a line of 2, that is the question. */
  const summary = $derived(
    line.quantity > 1
      ? wayCount === 1
        ? `One of the ${line.quantity}, just you`
        : `One of the ${line.quantity}, split ${wayCount} ways`
      : wayCount === 1
        ? 'Just you'
        : `Split ${wayCount} ways`,
  );

  function toggle(userId: string) {
    picked = picked.includes(userId) ? picked.filter((id) => id !== userId) : [...picked, userId];
  }

  function addName() {
    const name = draftName.trim().replace(/\s+/g, ' ');
    if (!name) {
      hint = 'Type their name first.';
      return;
    }
    newNames = [...newNames, name];
    draftName = '';
    typingName = false;
    hint = null;
  }

  async function commit() {
    const result = await store.shareLine(line.itemId, picked, newNames);
    if (result === 'blocked') {
      hint = 'Someone here already has that name. Add a surname or an initial so plates can be told apart.';
      return;
    }
    if (result === 'overclaimed') {
      hint = "That's more of this than were ordered. Take someone off, or close and check the line.";
      return;
    }
    onclose();
  }

  async function takeMeOff() {
    await store.leave(line.itemId);
    onclose();
  }
</script>

<Sheet title="Who had the {line.label}" {onclose}>
  <div class="h2">Who had the {line.label} with you?</div>
  <p class="sub" style="margin-bottom:13px">Tap anyone already here.</p>

  <div class="chips" style="gap:7px">
    <span class="chip chip--pick chip--picked">
      <Avatar name={store.identity?.name ?? 'You'} />You
    </span>
    {#each others as person (person.userId)}
      <button
        type="button"
        class="chip chip--pick"
        class:chip--picked={picked.includes(person.userId)}
        aria-pressed={picked.includes(person.userId)}
        onclick={() => toggle(person.userId)}
      >
        <Avatar name={person.name} />{person.name}
      </button>
    {/each}
    {#each newNames as name (name)}
      <button
        type="button"
        class="chip chip--pick chip--picked"
        onclick={() => (newNames = newNames.filter((n) => n !== name))}
      >
        <Avatar {name} />{name} ✕
      </button>
    {/each}
    {#if !typingName}
      <button type="button" class="chip chip--pick chip--add" onclick={() => (typingName = true)}>
        ＋ Someone else
      </button>
    {/if}
  </div>

  {#if typingName}
    <div class="field" style="margin-top:12px">
      <label class="flbl" for="share-name">Their name</label>
      <input
        id="share-name"
        class="inp"
        type="text"
        maxlength="40"
        enterkeyhint="done"
        bind:value={draftName}
        onkeydown={(e) => e.key === 'Enter' && addName()}
      />
      <div class="chips">
        <button type="button" class="chip chip--pick" onclick={addName}>Add them</button>
        <button type="button" class="chip chip--pick" onclick={() => ((typingName = false), (draftName = ''))}>
          Cancel
        </button>
      </div>
    </div>
  {/if}

  <div class="note note--blue" style="margin-top:16px">
    {summary} · <b class="mono">{money(eachSubunits, store.currency)}</b>{wayCount > 1 ? ' each' : ''}
  </div>

  {#if hint}
    <div class="note note--amber" role="alert">{hint}</div>
  {/if}
  {#if store.error}
    <div class="note note--red" role="alert">{store.error}</div>
  {/if}

  <button type="button" class="btn" aria-busy={store.busy} onclick={commit}>Done</button>

  {#if line.mine}
    <div class="spacer"></div>
    <button type="button" class="btn btn--ghost btn--sm" onclick={takeMeOff}>
      Take me off this line
    </button>
  {/if}
</Sheet>
