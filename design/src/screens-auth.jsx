// screens-auth.jsx — 1 sign-in · 2 magic link · 3 onboarding
const { useState: useStateAu } = React;

function Wordmark({ size = 34 }) {
  return (
    <div className="row gap12" style={{ alignItems: 'center' }}>
      <span style={{ width: size * 1.18, height: size * 1.18, borderRadius: size * 0.34, background: 'var(--blue)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <svg width={size * 0.62} height={size * 0.62} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.4" strokeLinecap="round"><path d="M12 3v18M7 8h7.5a2.5 2.5 0 010 5H7M17 16H9.5a2.5 2.5 0 010-5"/></svg>
      </span>
      <span style={{ fontSize: size, fontWeight: 700, letterSpacing: -1 }}>Share<span style={{ color: 'var(--blue)' }}>Cost</span></span>
    </div>
  );
}

/* ── 1 · Sign-in ──────────────────────────────────────────── */
function SignIn() {
  const providers = [
    { id: 'google', label: 'Continue with Google' },
    { id: 'apple', label: 'Continue with Apple' },
    { id: 'facebook', label: 'Continue with Facebook' },
    { id: 'mail', label: 'Continue with Email' },
  ];
  return (
    <div className="sc-screen" style={{ height: '100%', padding: '0 24px' }}>
      <div className="grow col" style={{ justifyContent: 'center', alignItems: 'flex-start', gap: 16 }}>
        <Wordmark size={38} />
        <div style={{ fontSize: 19, color: 'var(--ink-2)', lineHeight: 1.5, maxWidth: 280, fontWeight: 500 }}>
          Track shared expenses.<br />Pay through your own app.
        </div>
      </div>
      <div className="col gap12" style={{ paddingBottom: 8 }}>
        {providers.map(p => (
          <button key={p.id} className="sc-oauth"><Icon name={p.id} size={22} style={{ color: p.id === 'facebook' ? 'var(--blue)' : 'var(--ink)' }} />{p.label}</button>
        ))}
      </div>
      <div className="row" style={{ justifyContent: 'center', gap: 6, padding: '18px 0 12px', fontSize: 12, color: 'var(--ink-3)' }}>
        <span>Privacy</span><span>·</span><span>Terms</span>
      </div>
    </div>
  );
}

/* ── 2 · Magic link ───────────────────────────────────────── */
function MagicLink({ state = 'input' }) {
  return (
    <div className="sc-screen" style={{ height: '100%', padding: '0 24px' }}>
      <TopBar left={<IconBtn name="back" />} style={{ borderBottom: 'none', paddingInline: 0 }} />
      {state === 'sent' ? (
        <div className="grow col" style={{ justifyContent: 'center', alignItems: 'center', textAlign: 'center', gap: 16 }}>
          <div className="sc-empty__icon" style={{ width: 72, height: 72, background: 'var(--blue-tint)', color: 'var(--blue)' }}><Icon name="mail" size={34} /></div>
          <div style={{ fontSize: 22, fontWeight: 700 }}>Check your email</div>
          <div style={{ fontSize: 15, color: 'var(--ink-2)', lineHeight: 1.5, maxWidth: 280 }}>We sent a magic link to <b style={{ color: 'var(--ink)' }}>alex@hey.com</b>. Tap it to sign in — no password needed.</div>
          <button className="sc-btn-text">Resend link</button>
        </div>
      ) : (
        <div className="grow col" style={{ justifyContent: 'center', gap: 24 }}>
          <div className="col gap8"><div style={{ fontSize: 26, fontWeight: 700, letterSpacing: -0.5 }}>Sign in with email</div><div style={{ fontSize: 15, color: 'var(--ink-2)' }}>We'll email you a one-tap link.</div></div>
          <div className="sc-field">
            <span className="sc-label">Email address</span>
            <div className="sc-input sc-input--focus">alex@hey.com</div>
          </div>
          <Btn icon={state === 'loading' ? null : 'send'} disabled={state === 'loading'}>
            {state === 'loading' ? <span className="row gap8"><Spinner /> Sending…</span> : 'Send magic link'}
          </Btn>
        </div>
      )}
    </div>
  );
}

function Spinner({ light = true }) {
  return <span style={{ width: 18, height: 18, borderRadius: '50%', border: '2.5px solid ' + (light ? 'rgba(255,255,255,0.4)' : 'var(--border)'), borderTopColor: light ? '#fff' : 'var(--blue)', display: 'inline-block', animation: 'sc-spin .7s linear infinite' }} />;
}

/* ── 3 · Onboarding carousel ──────────────────────────────── */
function Onboarding({ step: stepInit = 0 }) {
  const [step, setStep] = useStateAu(stepInit);
  const [analytics, setAnalytics] = useStateAu(true);
  const steps = ['name', 'currency', 'handle', 'analytics', 'notify'];
  const next = () => setStep(s => Math.min(s + 1, steps.length - 1));
  const cur = steps[step];

  const Body = () => {
    if (cur === 'name') return (
      <OnbBody icon="user" title="What should we call you?" text="This is how friends see you in groups.">
        <div className="sc-field"><span className="sc-label">Display name</span><div className="sc-input sc-input--focus">Alex Rivera</div></div>
      </OnbBody>
    );
    if (cur === 'currency') return (
      <OnbBody icon="globe" title="Pick your base currency" text="Used as the default for new groups. You can change it per group.">
        <div className="sc-input row gap8" style={{ marginBottom: 12 }}><Icon name="search" size={18} style={{ color: 'var(--ink-3)' }} /><span className="sc-muted">Search currency…</span></div>
        <div className="sc-card" style={{ overflow: 'hidden' }}>
          {[['USD', 'US Dollar', true], ['EUR', 'Euro'], ['GBP', 'British Pound'], ['MXN', 'Mexican Peso']].map(([c, n, on], i) => (
            <div key={c} className="sc-exp" style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}>
              <span className="mono" style={{ fontWeight: 600, width: 44 }}>{c}</span>
              <span className="grow" style={{ fontSize: 15 }}>{n}</span>
              {on && <Icon name="check" size={20} style={{ color: 'var(--blue)' }} />}
            </div>
          ))}
        </div>
      </OnbBody>
    );
    if (cur === 'handle') return (
      <OnbBody icon="wallet" title="Add a payment handle" text="So friends can pay you back in one tap. Optional — you can skip.">
        <div className="col gap8">
          {['Venmo', 'Cash App', 'Zelle', 'PayPal'].map(a => (
            <button key={a} className="sc-input row between"><span className="row gap8"><Icon name="wallet" size={18} style={{ color: 'var(--ink-2)' }} />{a}</span><Icon name="plus" size={18} style={{ color: 'var(--blue)' }} /></button>
          ))}
        </div>
      </OnbBody>
    );
    if (cur === 'analytics') return (
      <OnbBody icon="chart" title="Help improve Evenly" text="Share anonymous usage data. No expense details, ever.">
        <div className="sc-card sc-card__pad row between" style={{ width: '100%', cursor: 'pointer' }} onClick={() => setAnalytics(a => !a)}>
          <span className="col" style={{ alignItems: 'flex-start' }}><span style={{ fontWeight: 600 }}>Anonymous analytics</span><span className="sc-tiny sc-muted">On by default</span></span>
          <Toggle on={analytics} />
        </div>
      </OnbBody>
    );
    return (
      <OnbBody icon="bell" title="Stay in the loop" text="Get notified when someone adds an expense or pays you back.">
        <div className="sc-card sc-card__pad col gap12" style={{ alignItems: 'center', textAlign: 'center' }}>
          <div className="sc-empty__icon" style={{ background: 'var(--blue-tint)', color: 'var(--blue)' }}><Icon name="bell" size={28} /></div>
          <span className="sc-tiny sc-muted">We'll ask your device for permission next.</span>
        </div>
      </OnbBody>
    );
  };

  return (
    <div className="sc-screen" style={{ height: '100%', padding: '0 24px' }}>
      <div className="row between" style={{ padding: '8px 0 0' }}>
        <div className="row gap4">{steps.map((_, i) => <span key={i} style={{ width: i === step ? 22 : 7, height: 7, borderRadius: 99, background: i === step ? 'var(--blue)' : 'var(--border-strong)', transition: 'width .2s' }} />)}</div>
        {step < steps.length - 1 && cur !== 'name' && <button className="sc-btn-text" style={{ height: 'auto' }} onClick={next}>Skip</button>}
      </div>
      <Body />
      <div className="col gap8" style={{ paddingBottom: 8 }}>
        <Btn icon={cur === 'notify' ? 'check' : 'chevR'} onClick={cur === 'notify' ? null : next}>{cur === 'notify' ? 'Enable & finish' : cur === 'handle' ? 'Continue' : 'Continue'}</Btn>
      </div>
    </div>
  );
}

function OnbBody({ icon, title, text, children }) {
  return (
    <div className="grow col" style={{ justifyContent: 'center', gap: 20, paddingBottom: 12 }}>
      <div className="col gap16">
        <div className="sc-empty__icon" style={{ width: 56, height: 56, background: 'var(--blue-tint)', color: 'var(--blue)' }}><Icon name={icon} size={28} /></div>
        <div className="col gap8"><div style={{ fontSize: 26, fontWeight: 700, letterSpacing: -0.5 }}>{title}</div><div style={{ fontSize: 15, color: 'var(--ink-2)', lineHeight: 1.5 }}>{text}</div></div>
      </div>
      {children}
    </div>
  );
}

window.Wordmark = Wordmark; window.Spinner = Spinner;
Object.assign(window, { SignIn, MagicLink, Onboarding });
