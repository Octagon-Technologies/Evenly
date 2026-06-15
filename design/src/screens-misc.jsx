// screens-misc.jsx — 21 reconcile (+ confirm) · 22 state kit
const { useState: useStateM } = React;

/* ── 21 · Reconcile past activity ─────────────────────────── */
function Reconcile({ confirm = false, onBack }) {
  const people = [
    { name: 'Alex R.', n: 3 },
    { name: 'A. Rivera', n: 2 },
    { name: 'Alejandro', n: 1 },
  ];
  const [sel, setSel] = useStateM([0, 1]);
  const [query, setQuery] = useStateM('');
  if (confirm) return <ReconcileConfirm onBack={onBack} />;
  const toggle = (i) => setSel(p => p.includes(i) ? p.filter(x => x !== i) : [...p, i]);
  const visible = people.filter(p => p.name.toLowerCase().includes(query.toLowerCase()));
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar left={<IconBtn name="close" onClick={onBack} />} title="Reconcile" sub="Tulum Trip" />
      <Scroll surface>
        <div className="col gap12" style={{ padding: 16 }}>
          <div className="col gap4">
            <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: -0.4 }}>Are any of these you?</div>
            <div style={{ fontSize: 14, color: 'var(--ink-2)', lineHeight: 1.5 }}>Before you joined, the group logged these names. Claim the ones that are you to merge your history.</div>
          </div>
          {/* search bar */}
          <div className="sc-input row gap8" style={{ height: 44 }}>
            <Icon name="search" size={18} style={{ color: 'var(--ink-3)', flexShrink: 0 }} />
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Search your name…"
              style={{ border: 'none', outline: 'none', background: 'transparent', flex: 1, fontSize: 15, fontFamily: 'var(--font)', color: 'var(--ink)' }}
            />
            {query && <button onClick={() => setQuery('')} style={{ color: 'var(--ink-3)', fontSize: 18, lineHeight: 1, flexShrink: 0 }}>×</button>}
          </div>
          {/* compact list */}
          <div className="sc-card" style={{ overflow: 'hidden' }}>
            {visible.length === 0 && (
              <div className="sc-exp"><span className="sc-muted" style={{ fontSize: 14 }}>No names match "{query}"</span></div>
            )}
            {visible.map((p, vi) => {
              const i = people.indexOf(p);
              const on = sel.includes(i);
              return (
                <button key={i} onClick={() => toggle(i)} className="sc-exp sc-exp--tap" style={{ width: '100%', textAlign: 'left', background: on ? 'var(--blue-tint)' : 'var(--page)', boxShadow: vi > 0 ? '0 -1px 0 var(--border)' : 'none' }}>
                  <span className={'sc-check' + (on ? ' sc-check--on' : '')}>{on && <Icon name="check" size={16} />}</span>
                  <Avatar name={p.name} size="sm" tone={on ? { bg: 'var(--blue)', fg: '#fff' } : undefined} />
                  <span className="grow" style={{ fontWeight: 600, fontSize: 15 }}>{p.name}</span>
                  <span className="sc-chip" style={{ background: on ? 'rgba(37,99,235,0.1)' : 'var(--surface)' }}>{p.n} expenses</span>
                  <Icon name="chevR" size={16} style={{ color: 'var(--ink-3)' }} />
                </button>
              );
            })}
          </div>
        </div>
      </Scroll>
      <div style={{ padding: 16, borderTop: '1px solid var(--border)', background: 'var(--page)' }} className="col gap8">
        <Btn icon="check" onClick={() => onBack && onBack()}>These are me ({sel.length})</Btn>
        <Btn variant="text">None of these are me</Btn>
      </div>
    </div>
  );
}

function ReconcileConfirm({ onBack }) {
  const claimed = [['Dinner at La Negra', 24], ['Airport taxi', 14.5], ['Cenote day trip', 18], ['Supermarket run', 9.4], ['Beach drinks', 12]];
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <DimBackdrop label="Reconcile" />
      <Modal onClose={onBack}>
        <div className="col gap16">
          <div className="col gap8" style={{ alignItems: 'center', textAlign: 'center' }}>
            <div className="sc-empty__icon" style={{ background: 'var(--blue-tint)', color: 'var(--blue)' }}><Icon name="swap" size={26} /></div>
            <div style={{ fontSize: 18, fontWeight: 700 }}>Claim 5 expenses?</div>
            <div className="sc-tiny sc-muted">These will move onto your balance in Tulum Trip.</div>
          </div>
          <div className="sc-card" style={{ overflow: 'hidden', maxHeight: 200, overflowY: 'hidden' }}>
            {claimed.map(([t, a], i) => (
              <div key={t} className="sc-exp" style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}><div className="sc-exp__icon" style={{ width: 32, height: 32 }}><Icon name="receipt" size={16} /></div><span className="grow" style={{ fontSize: 14, fontWeight: 600 }}>{t}</span><span className="mono sc-tiny sc-muted">${a.toFixed(2)}</span></div>
            ))}
          </div>
          <div className="col gap8"><Btn icon="check">Yes, claim them</Btn><Btn variant="text" onClick={onBack}>Cancel</Btn></div>
        </div>
      </Modal>
    </div>
  );
}

/* ── 22 · State kit ───────────────────────────────────────── */
function StateKitOffline() {
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar title={<span className="row gap8"><span style={{ fontSize: 22 }}>🏝️</span> Tulum Trip</span>} right={<div className="row gap4"><IconBtn name="search" /><IconBtn name="filter" /></div>} />
      <Banner variant="offline" icon="wifiOff">Offline — your changes will sync.</Banner>
      <SubTabs tabs={['Active', 'All', 'Settled']} active="Active" />
      <Scroll surface>
        <div className="sc-dayhead">Today</div>
        <ExpenseRow cat="food" title="Dinner at La Negra" sub="Andrew paid · you owe $24.00" remaining={24} original={96} pending />
        <div style={{ boxShadow: '0 -1px 0 var(--border)' }}><ExpenseRow cat="transit" title="Airport taxi" sub="You paid · Bob owes $14.50" remaining={14.5} original={58} pending /></div>
        <div style={{ boxShadow: '0 -1px 0 var(--border)' }}><ExpenseRow cat="fun" title="Cenote day trip" sub="Bob paid · you owe $18.00" remaining={18} original={72} /></div>
      </Scroll>
    </div>
  );
}

function StateKitSkeleton() {
  return (
    <div className="sc-screen" style={{ height: '100%' }}>
      <div className="sc-topbar"><Skel w={150} h={20} /><div className="grow" /><Skel w={40} h={40} r={12} /></div>
      <div className="sc-subtabs" style={{ paddingBottom: 12 }}><Skel w={56} h={16} /><Skel w={40} h={16} style={{ marginLeft: 12 }} /><Skel w={60} h={16} style={{ marginLeft: 12 }} /></div>
      <div style={{ paddingTop: 8 }}>{[0,1,2,3,4,5].map(i => <SkelRow key={i} />)}</div>
    </div>
  );
}

Object.assign(window, { Reconcile, ReconcileConfirm, StateKitOffline, StateKitSkeleton });
