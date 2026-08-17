<script lang="ts">
  /**
   * The feedback thank-you screen (`THANKYOU_SCREEN_PROMPT.md`, build-order step 5). Icon, motion and
   * headline key off `type`; the Problem variant drops its email promise when `hasEmail` is false,
   * since an anonymous `/feedback` submitter has nothing for a resolved ticket to be sent to
   * (`THANKYOU_SCREEN_PROMPT.md` Q6). This is the step 5 deliverable only: a standalone screen, not
   * yet wired to the `/feedback` form itself (step 6, a separate session per
   * `ADMIN_FEEDBACK_SPEC.md` §10).
   */
  interface Props {
    type: 'problem' | 'suggestion' | 'question';
    /** Web-only case: false for an anonymous submitter who left no email (spec §4.2's optional Name
     *  field has nothing to notify). Always true from the app, which has no email field at all. */
    hasEmail?: boolean;
    /** Mirrors the app's outbox: the ticket is durably queued but hasn't reached the server yet. */
    queued?: boolean;
    ondone?: (() => void) | null;
  }

  const { type, hasEmail = true, queued = false, ondone = null }: Props = $props();

  const headline = $derived(
    type === 'problem'
      ? "We've got this."
      : type === 'suggestion'
        ? 'Noted, and thank you.'
        : "We'll get you an answer."
  );

  const sub = $derived.by(() => {
    if (queued) return "It hasn't gone out yet. Evenly keeps trying in the background.";
    if (type === 'problem') {
      return hasEmail
        ? "Your report is logged and a real person will read it. We'll email you when it's fixed."
        : 'Your report is logged and a real person will read it.';
    }
    if (type === 'suggestion') {
      return 'Ideas from people who actually use Evenly are how it gets better. We read every one.';
    }
    return "Your question is with us. We'll reply by email.";
  });
</script>

<div class="page">
  <div class="scroll ty-scroll">
    <div class="ty-icon ty-icon--{type}" aria-hidden="true">
      {#if type === 'problem'}
        <div class="ty-badge">
          <svg viewBox="0 0 24 24" class="ty-check">
            <path d="M5 12.5 10 17 19 7" />
          </svg>
        </div>
      {:else if type === 'suggestion'}
        <div class="ty-badge ty-badge--tint">
          <svg viewBox="0 0 24 24" class="ty-sparkle">
            <path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3z" />
          </svg>
          <i class="ty-spark s1"></i><i class="ty-spark s2"></i><i class="ty-spark s3"></i>
        </div>
      {:else}
        <div class="ty-badge">
          <svg viewBox="0 0 40 40" class="ty-plane-svg">
            <path class="ty-trail" d="M10 26 Q20 30 30 14" />
            <g class="ty-plane">
              <path d="M20 8 4 15l6 2.4L13.6 24l3-6.5L23 22l-3-14Z" />
            </g>
          </svg>
        </div>
      {/if}
    </div>

    <div class="hdr ty-hdr">
      <div class="h2">{headline}</div>
      <div class="sub">{sub}</div>
    </div>
  </div>

  <div class="ty-cta">
    <button
      type="button"
      class="btn"
      class:btn--ghost={type !== 'suggestion'}
      onclick={ondone}
    >
      Done
    </button>
  </div>
</div>

<style>
  .ty-scroll {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    flex: 1;
    text-align: center;
  }

  .ty-hdr {
    text-align: center;
    padding-bottom: 0;
  }
  .ty-hdr .sub {
    max-width: 30ch;
    margin-left: auto;
    margin-right: auto;
  }

  .ty-cta {
    padding: 18px 18px calc(20px + env(safe-area-inset-bottom));
    flex-shrink: 0;
  }

  .ty-icon {
    width: 84px;
    height: 84px;
    margin-bottom: 22px;
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .ty-badge {
    width: 64px;
    height: 64px;
    border-radius: 50%;
    background: var(--surface);
    border: 1.5px solid var(--border-strong);
    display: flex;
    align-items: center;
    justify-content: center;
    animation: ty-settle 0.58s cubic-bezier(0.2, 0.8, 0.3, 1) both;
  }
  .ty-badge--tint {
    background: var(--blue-tint);
    border-color: var(--blue-tint-2);
    animation: ty-lift 0.56s cubic-bezier(0.34, 1.4, 0.4, 1) both;
  }
  @keyframes ty-settle {
    0% {
      transform: translateY(-10px) scale(0.75);
      opacity: 0;
    }
    55% {
      transform: translateY(3px) scale(1.06);
      opacity: 1;
    }
    78% {
      transform: translateY(-1px) scale(0.99);
    }
    100% {
      transform: translateY(0) scale(1);
    }
  }
  @keyframes ty-lift {
    0% {
      transform: translateY(6px) scale(0.7);
      opacity: 0;
      box-shadow: 0 0 0 0 rgba(37, 99, 235, 0);
    }
    60% {
      transform: translateY(-4px) scale(1.08);
      opacity: 1;
      box-shadow: 0 0 0 14px rgba(37, 99, 235, 0.08);
    }
    100% {
      transform: translateY(0) scale(1);
      box-shadow: 0 0 0 0 rgba(37, 99, 235, 0);
    }
  }

  .ty-check {
    width: 30px;
    height: 30px;
  }
  .ty-check path {
    fill: none;
    stroke: var(--blue-press);
    stroke-width: 3;
    stroke-linecap: round;
    stroke-linejoin: round;
    stroke-dasharray: 40;
    stroke-dashoffset: 40;
    animation: ty-draw 0.32s 0.32s ease-out both;
  }
  @keyframes ty-draw {
    to {
      stroke-dashoffset: 0;
    }
  }

  .ty-sparkle {
    width: 28px;
    height: 28px;
    opacity: 0;
    animation: ty-sparkle-in 0.3s 0.2s ease-out both;
  }
  .ty-sparkle path {
    fill: var(--blue);
  }
  @keyframes ty-sparkle-in {
    to {
      opacity: 1;
    }
  }
  .ty-spark {
    position: absolute;
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--blue);
    opacity: 0;
  }
  .ty-spark.s1 {
    top: 14px;
    left: 12px;
    animation: ty-spark-rise 0.5s 0.28s ease-out both;
  }
  .ty-spark.s2 {
    top: 10px;
    right: 14px;
    animation: ty-spark-rise 0.5s 0.38s ease-out both;
  }
  .ty-spark.s3 {
    top: 22px;
    right: 4px;
    animation: ty-spark-rise 0.46s 0.48s ease-out both;
  }
  @keyframes ty-spark-rise {
    0% {
      opacity: 0;
      transform: translateY(0) scale(0.6);
    }
    35% {
      opacity: 1;
    }
    100% {
      opacity: 0;
      transform: translateY(-16px) scale(0.9);
    }
  }

  .ty-plane-svg {
    width: 38px;
    height: 38px;
    overflow: visible;
  }
  .ty-plane {
    transform-origin: 50% 50%;
    animation: ty-fly 0.6s cubic-bezier(0.3, 0.7, 0.25, 1) both;
  }
  .ty-plane path {
    fill: var(--blue-press);
  }
  @keyframes ty-fly {
    0% {
      transform: translate(-10px, 6px) rotate(-14deg);
      opacity: 0;
    }
    45% {
      transform: translate(9px, -8px) rotate(8deg);
      opacity: 1;
    }
    100% {
      transform: translate(0, 0) rotate(0deg);
      opacity: 1;
    }
  }
  .ty-trail {
    fill: none;
    stroke: var(--blue-tint-2);
    stroke-width: 2;
    stroke-linecap: round;
    stroke-dasharray: 3 5;
    stroke-dashoffset: 26;
    animation: ty-trail-draw 0.5s 0.05s ease-out both;
  }
  @keyframes ty-trail-draw {
    to {
      stroke-dashoffset: 0;
    }
  }

  /* Every animation above still leaves the correct end state when skipped, so reduced motion looks
     finished rather than static-and-empty (THANKYOU_SCREEN_PROMPT.md's own accessibility rule). */
  @media (prefers-reduced-motion: reduce) {
    .ty-badge,
    .ty-check path,
    .ty-sparkle,
    .ty-spark,
    .ty-plane,
    .ty-trail {
      animation: none !important;
      opacity: 1 !important;
      transform: none !important;
      stroke-dashoffset: 0 !important;
      box-shadow: none !important;
    }
    .ty-spark {
      opacity: 0 !important;
    }
  }
</style>
