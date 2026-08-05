// screens-settle.jsx — 14 single-expense settle · 15 settle a person · 16 deep-link confirm
const { useState: useStateS } = React;

const HANDLES = [
  { app: 'Venmo', handle: '@andrew-p', icon: 'wallet' },
  { app: 'Cash App', handle: '$andrewp', icon: 'wallet' },
  { app: 'Zelle', handle: 'andrew@…', icon: 'wallet' },
];

const CURRENCIES = [
  { code: 'USD', name: 'US Dollar' },
  { code: 'EUR', name: 'Euro' },
  { code: 'GBP', name: 'British Pound' },
  { code: 'MXN', name: 'Mexican Peso' },
  { code: 'CAD', name: 'Canadian Dollar' },
  { code: 'AUD', name: 'Australian Dollar' },
];

/* ── 14 · Settle single expense (modal sheet) ─────────────── */
function SettleSingle({ fx = false, onBack, behind, onOpen }) {
  const [app, setApp] = useStateS('Venmo');
  const [curr, setCurr] = useStateS('USD');
  const [currOpen, setCurrOpen] = useStateS(false);

  if (currOpen) {
    return (
      <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
        {behind || <DimBackdrop label="Dinner at La Negra" />}
        <Sheet onClose={() => setCurrOpen(false)} title="Select currency">
          <div className="sc-input row gap8" style={{ marginBottom: 10 }}><Icon name="search" size={18} style={{ color: 'var(--ink-3)' }} /><span className="sc-muted">Search currency…</span></div>
          <div className="col gap4" style={{ paddingBottom: 4 }}>
            {CURRENCIES.map(c => (
              <button key={c.code} className="sc-row sc-row--tap" style={{ borderRadius: 12, background: c.code === curr ? 'var(--blue-tint)' : 'transparent' }} onClick={() => { setCurr(c.code); setCurrOpen(false); }}>
                <span className="grow col" style={{ alignItems: 'flex-start', gap: 2 }}>
                  <span style={{ fontWeight: 600, fontSize: 15 }}>{c.code}</span>
                  <span className="sc-tiny sc-muted">{c.name}</span>
                </span>
                {c.code === curr && <Icon name="check" size={18} style={{ color: 'var(--blue)' }} />}
              </button>
            ))}
          </div>
        </Sheet>
      </div>
    );
  }

  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      {behind || <DimBackdrop label="Dinner at La Negra" />}
      <Sheet onClose={onBack} title="Settle 'Dinner at La Negra'">
        <div className="col gap16" style={{ paddingBottom: 4 }}>
          <div className="row gap12" style={{ alignItems: 'center', justifyContent: 'center' }}>
            <Avatar name="Andrew" size="lg" />
            <div><div style={{ fontWeight: 600 }}>Pay Andrew</div><div className="sc-tiny sc-muted">for your $24.00 share</div></div>
          </div>

          <div className="sc-field">
            <span className="sc-label">Amount</span>
            <div className="sc-input sc-input--mono row" style={{ height: 60, paddingInline: 10, gap: 10 }}>
              <button className="sc-chip sc-chip--ghost" style={{ flexShrink: 0 }} onClick={() => setCurrOpen(true)}>
                <Icon name="globe" size={14} /> {curr} <Icon name="chevD" size={12} />
              </button>
              <span style={{ fontSize: 26, fontWeight: 600, flex: 1 }}>24.00</span>
              <button className="sc-chip sc-chip--blue" style={{ flexShrink: 0 }}>Max</button>
            </div>
            <div className="sc-tiny sc-muted">Paying $24.00 {curr} ≈ £18.36 (rate: 0.765)</div>
          </div>

          <div className="col gap8">
            <span className="sc-label">Pay with</span>
            <div className="row gap8">
              {HANDLES.map(h => (
                <button key={h.app} className={'sc-pchip' + (app === h.app ? ' sc-pchip--on' : '')} style={{ paddingInline: 12 }} onClick={() => setApp(h.app)}>
                  <Icon name="wallet" size={15} />{h.app}
                </button>
              ))}
            </div>
            <div className="sc-tiny sc-muted">{HANDLES.find(h => h.app === app).handle}</div>
          </div>

          <div className="col gap8">
            <Btn icon="link" onClick={onOpen}>Open in {app}</Btn>
            <Btn variant="text" icon="check" onClick={onBack}>Mark paid manually</Btn>
          </div>
        </div>
      </Sheet>
    </div>
  );
}

/* ── 15 · Settle a person (full-screen, 2 steps) ──────────── */
function SettlePerson({ step = 1, onBack }) {
  const BASE_SHARES = [
    { title: 'Dinner at La Negra', sub: 'May 23', amt: 24 },
    { title: 'Cenote day trip', sub: 'May 22', amt: 18 },
    { title: 'Airport taxi', sub: 'May 21', amt: 14.5 },
  ];
  const [checked, setChecked] = useStateS([0, 1, 2]);
  const toggle = (i) => setChecked(p => p.includes(i) ? p.filter(x => x !== i) : [...p, i]);
  const total = BASE_SHARES.filter((_, i) => checked.includes(i)).reduce((a, s) => a + s.amt, 0);
  if (step === 2) return <SettleSingleAsStep total={total} count={checked.length} onBack={onBack} />;
  return (
    <div className="sc-screen" style={{ height: '100%' }}>
      <TopBar left={<IconBtn name="back" onClick={onBack} />} title="Settle with Andrew" sub="in USD" />
      <Scroll surface>
        <div className="col gap16" style={{ padding: 16 }}>
          <div className="sc-tiny sc-muted">Pick which of your shares to pay back. You can settle one, some, or all.</div>
          <div className="sc-card" style={{ overflow: 'hidden' }}>
            {BASE_SHARES.map((s, i) => (
              <div key={i} className="sc-exp sc-exp--tap" style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined} onClick={() => toggle(i)}>
                <Check on={checked.includes(i)} onClick={() => toggle(i)} />
                <div className="sc-exp__main"><div className="sc-exp__title">{s.title}</div><div className="sc-exp__sub">{s.sub}</div></div>
                <span className="mono" style={{ fontWeight: 600, color: checked.includes(i) ? 'var(--ink)' : 'var(--ink-3)' }}>${s.amt.toFixed(2)}</span>
              </div>
            ))}
          </div>
        </div>
      </Scroll>
      <div style={{ padding: 16, borderTop: '1px solid var(--border)', background: 'var(--page)' }}>
        <div className="row between" style={{ marginBottom: 12 }}>
          <span className="sc-muted" style={{ fontWeight: 600 }}>Total to settle</span>
          <span className="mono" style={{ fontSize: 22, fontWeight: 700 }}>${total.toFixed(2)}</span>
        </div>
        <Btn icon="chevR" onClick={onBack} disabled={checked.length === 0}>Continue</Btn>
      </div>
    </div>
  );
}

function SettleSingleAsStep({ total, count = 3, onBack }) {
  return (
    <div className="sc-screen" style={{ height: '100%' }}>
      <TopBar left={<IconBtn name="back" onClick={onBack} />} title="Pay Andrew" sub="Step 2 of 2" />
      <Scroll surface>
        <div className="col gap16" style={{ padding: 16 }}>
          <div className="sc-card sc-card__pad col" style={{ alignItems: 'center', gap: 8 }}>
            <Avatar name="Andrew" size="lg" />
            <div className="mono" style={{ fontSize: 38, fontWeight: 600, letterSpacing: -1 }}>${total.toFixed(2)}</div>
            <div className="sc-tiny sc-muted">across {count} share{count !== 1 ? 's' : ''}</div>
          </div>
          <div className="col gap8">
            <span className="sc-label">Pay with</span>
            <div className="row gap8">{HANDLES.map(h => <button key={h.app} className={'sc-pchip' + (h.app==='Venmo'?' sc-pchip--on':'')} style={{ paddingInline: 12 }}><Icon name="wallet" size={15} />{h.app}</button>)}</div>
          </div>
          <Btn icon="link">Open in Venmo</Btn>
          <Btn variant="text" icon="check">Mark paid manually</Btn>
        </div>
      </Scroll>
    </div>
  );
}

/* ── 16 · Deep-link confirmation sheet ────────────────────── */
function DeepLinkConfirm({ onBack, behind, onYes }) {
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      {behind || <DimBackdrop label="Venmo" appish />}
      <Sheet onClose={onBack}>
        <div className="col gap16" style={{ alignItems: 'center', textAlign: 'center', paddingBottom: 4 }}>
          <div className="sc-empty__icon" style={{ background: 'var(--blue-tint)', color: 'var(--blue)' }}><Icon name="link" size={28} /></div>
          <div><div style={{ fontSize: 18, fontWeight: 700 }}>Settled $24.00 with @andrew-p?</div><div className="sc-tiny sc-muted" style={{ marginTop: 4 }}>We opened Venmo. Did the payment go through?</div></div>
          <div className="col gap8" style={{ width: '100%' }}>
            <Btn icon="check" onClick={onYes}>Yes — mark paid</Btn>
            <button className="sc-row sc-row--tap" style={{ borderRadius: 12, justifyContent: 'center', background: 'var(--surface)', height: 48 }}><Icon name="copy" size={18} style={{ color: 'var(--ink-2)' }} /><span style={{ fontWeight: 600 }}>Copy handle</span></button>
            <Btn variant="text">Not yet</Btn>
          </div>
        </div>
      </Sheet>
    </div>
  );
}

/* faded screen behind a sheet/modal */
function DimBackdrop({ label, appish }) {
  return (
    <div style={{ position: 'absolute', inset: 0, background: appish ? 'var(--blue-press)' : '#C8D0DE', opacity: appish ? 0.12 : 1 }}>
      {!appish && <div style={{ padding: 16, filter: 'blur(1px)', opacity: 0.5 }} className="col gap12">
        <Skel w="50%" h={18} /><Skel h={120} r={16} /><Skel w="40%" h={14} /><SkelRow /><SkelRow />
      </div>}
    </div>
  );
}

window.HANDLES = HANDLES; window.DimBackdrop = DimBackdrop;
Object.assign(window, { SettleSingle, SettlePerson, DeepLinkConfirm });
