// screens-group2.jsx — 7 filter · 8 search · 10 conflicts (+ include sheet) · 11 overview
const { useState: useStateG2 } = React;

/* ── 7 · Filter sheet ─────────────────────────────────────── */
function FilterSheet({ onBack }) {
  const [sort, setSort] = useStateG2('Date');
  const [cats, setCats] = useStateG2(['food', 'fun']);
  const [range, setRange] = useStateG2('Trip');
  const toggleCat = (c) => setCats(p => p.includes(c) ? p.filter(x => x !== c) : [...p, c]);
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <DimBackdrop label="Expenses" />
      <Sheet onClose={onBack} title="Sort & filter" maxH="92%">
        <div className="col gap16" style={{ overflow: 'hidden', paddingBottom: 4 }}>
          <div className="col gap8">
            <span className="sc-label">Sort by</span>
            <div className="row gap8" style={{ flexWrap: 'wrap' }}>
              {['Date', 'Amount', 'Payer', 'Category', 'Participant'].map(s => (
                <button key={s} className={'sc-pchip' + (sort === s ? ' sc-pchip--on' : '')} style={{ paddingInline: 14 }} onClick={() => setSort(s)}>{s}</button>
              ))}
            </div>
          </div>
          <div className="sc-divider" />
          <div className="col gap8">
            <span className="sc-label">Member</span>
            <div className="row gap8" style={{ flexWrap: 'wrap' }}>
              {['You', 'Andrew', 'Bob', 'Maya', 'Tyler'].map(m => (
                <button key={m} className="sc-pchip" style={{ paddingInline: 6 }}><Avatar name={m} me={m==='You'} size="xs" />{m}</button>
              ))}
            </div>
          </div>
          <div className="col gap8">
            <span className="sc-label">Category</span>
            <div className="row gap8" style={{ flexWrap: 'wrap' }}>
              {Object.entries(CAT).map(([k, c]) => (
                <button key={k} className={'sc-pchip' + (cats.includes(k) ? ' sc-pchip--on' : '')} style={{ paddingInline: 12 }} onClick={() => toggleCat(k)}><Icon name={c.icon} size={15} />{c.label.split(' ')[0]}</button>
              ))}
            </div>
          </div>
          <div className="col gap8">
            <span className="sc-label">Date range</span>
            <div className="row gap8" style={{ flexWrap: 'wrap' }}>
              {['Trip', '7 days', '30 days', 'This month', 'Custom…'].map(r => (
                <button key={r} className={'sc-pchip' + (range === r ? ' sc-pchip--on' : '')} style={{ paddingInline: 14 }} onClick={() => setRange(r)}>{r}</button>
              ))}
            </div>
          </div>
          <div className="row gap8" style={{ marginTop: 4 }}>
            <Btn variant="secondary">Reset</Btn>
            <Btn>Show 14 results</Btn>
          </div>
        </div>
      </Sheet>
    </div>
  );
}

/* ── 8 · Search overlay ───────────────────────────────────── */
function SearchOverlay({ onBack }) {
  return (
    <div className="sc-screen" style={{ height: '100%' }}>
      <div className="sc-topbar" style={{ gap: 8 }}>
        <div className="sc-input grow row gap8" style={{ height: 44 }}><Icon name="search" size={18} style={{ color: 'var(--ink-3)' }} /><span>tax</span><span style={{ width: 1.5, height: 18, background: 'var(--blue)' }} /></div>
        <button className="sc-btn-text" style={{ height: 'auto' }} onClick={onBack}>Cancel</button>
      </div>
      <Scroll>
        <div style={{ padding: '8px 0' }}>
          <div className="sc-section-label" style={{ padding: '10px 16px 6px' }}>Expenses</div>
          <ExpenseRow cat="food" title="Tax-included dinner" sub="Andrew paid · you owe $24.00" remaining={24} original={96} />
          <ExpenseRow cat="fun" title="Taxi to cenotes" sub="Bob paid · you owe $9.00" remaining={9} original={36} />
          <div className="sc-section-label" style={{ padding: '14px 16px 6px' }}>Members</div>
          <div className="sc-exp sc-exp--tap"><Avatar name="Maya" size="sm" /><div className="sc-exp__main"><div className="sc-exp__title">Maya</div><div className="sc-exp__sub">4 expenses match</div></div><Icon name="chevR" size={16} style={{ color: 'var(--ink-3)' }} /></div>
          <div className="sc-section-label" style={{ padding: '14px 16px 6px' }}>Categories</div>
          <div className="sc-exp sc-exp--tap"><div className="sc-exp__icon"><Icon name="ticket" size={20} /></div><div className="sc-exp__main"><div className="sc-exp__title">Taxes & fees</div><div className="sc-exp__sub">$38.40 across 3 expenses</div></div><Icon name="chevR" size={16} style={{ color: 'var(--ink-3)' }} /></div>
        </div>
      </Scroll>
    </div>
  );
}

/* ── 10 · Conflicts tab + Include sheet ───────────────────── */
function Conflicts({ sheet = false, onNav, onInclude }) {
  if (sheet) return <IncludeSheet onBack={() => onInclude && onInclude(false)} />;
  const items = [
    { title: 'Dinner at La Negra', amount: 96, by: 'Andrew', who: 'Tyler' },
    { title: 'Cenote day trip', amount: 72, by: 'Bob', who: 'Tyler' },
  ];
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar title="Conflicts" sub="Tyler joined after these expenses" />
      <Banner variant="amber" icon="alert">Decide whether Tyler shares these costs.</Banner>
      <Scroll surface>
        <div className="col gap12" style={{ padding: 16 }}>
          {items.map((it, i) => (
            <div key={i} className="sc-card sc-card__pad col gap12">
              <div className="row between">
                <div><div style={{ fontWeight: 600, fontSize: 15 }}>{it.title}</div><div className="sc-tiny sc-muted" style={{ marginTop: 2 }}>Added by {it.by}</div></div>
                <Amount remaining={it.amount} />
              </div>
              <div className="row gap8">
                <Btn icon="plus" onClick={() => onInclude && onInclude(true)}>Include Tyler</Btn>
                <button className="sc-btn sc-btn-text" style={{ width: 'auto', paddingInline: 14 }}>Skip Tyler</button>
              </div>
            </div>
          ))}
        </div>
      </Scroll>
      <BottomNav active="conflicts" conflicts={2} onNav={onNav} />
    </div>
  );
}

function IncludeSheet({ onBack }) {
  const orig = [['You', 24], ['Andrew', 24], ['Bob', 24], ['Maya', 24]];
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <DimBackdrop label="Conflicts" />
      <Sheet onClose={onBack} title="Include Tyler" sub="Dinner at La Negra · $96.00">
        <div className="col gap16" style={{ paddingBottom: 4 }}>
          <div>
            <div className="sc-section-label">Current split</div>
            <div className="sc-card" style={{ overflow: 'hidden' }}>
              {orig.map(([n, a], i) => (
                <div key={n} className="sc-exp" style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}><Avatar name={n} me={n==='You'} size="sm" /><span className="grow" style={{ fontWeight: 600, fontSize: 15 }}>{n}</span><span className="mono sc-muted">${a.toFixed(2)}</span></div>
              ))}
            </div>
          </div>
          <div className="sc-field">
            <span className="sc-label">Tyler's share</span>
            <div className="sc-input sc-input--mono sc-input--focus row between" style={{ height: 56 }}><span style={{ color: 'var(--ink-3)', fontSize: 22 }}>$</span><span style={{ marginLeft: 'auto', color: 'var(--ink-3)' }}>0.00</span></div>
          </div>
          <div className="row between" style={{ padding: '0 4px' }}>
            <span className="row gap8" style={{ fontWeight: 600, fontSize: 14, color: 'var(--blue-press)' }}><Icon name="info" size={15} /> Remaining to distribute</span>
            <span className="mono" style={{ fontWeight: 700 }}>$96.00</span>
          </div>
          <Btn icon="check">Confirm split</Btn>
        </div>
      </Sheet>
    </div>
  );
}

/* ── 11 · Trip overview ───────────────────────────────────── */
function Overview({ onNav }) {
  const byDay = [['Mon', 40], ['Tue', 96], ['Wed', 132], ['Thu', 58], ['Fri', 210], ['Sat', 180], ['Sun', 64]];
  const byMember = [['You', 198], ['Andrew', 264], ['Bob', 142], ['Maya', 210], ['Tyler', 100]];
  const maxD = 220, maxM = 280;
  const bal = [{ from: 'You', to: 'Andrew', amount: 42 }, { from: 'Bob', to: 'You', amount: 14.5, owedToYou: true }, { from: 'Tyler', to: 'Andrew', amount: 26.75 }];
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar title="Overview" right={<button className="sc-chip sc-chip--ghost" style={{ height: 34 }}><Icon name="download" size={15} /> Export</button>} />
      <Scroll surface>
        <div className="col gap16" style={{ padding: 16 }}>
          {/* header */}
          <div className="sc-card sc-card__pad col gap12">
            <div className="row between">
              <span className="row gap8" style={{ alignItems: 'center' }}><span style={{ fontSize: 26 }}>🏝️</span><span style={{ fontSize: 18, fontWeight: 700 }}>Tulum Trip</span></span>
              <Chip variant="ghost">May 21 – 27</Chip>
            </div>
            <div className="row gap8" style={{ flexWrap: 'wrap' }}>
              <StatChip label="Total spend" value="$914.00" />
              <StatChip label="Expenses" value="14" />
              <StatChip label="Per person" value="$182.80" />
            </div>
          </div>
          {/* spend by day */}
          <div className="sc-card sc-card__pad col gap12">
            <div className="sc-section-label" style={{ padding: 0 }}>Spend by day</div>
            <div className="row" style={{ alignItems: 'flex-end', gap: 8, height: 120 }}>
              {byDay.map(([d, v]) => (
                <div key={d} className="grow col" style={{ alignItems: 'center', gap: 6, justifyContent: 'flex-end', height: '100%' }}>
                  <div style={{ width: '100%', maxWidth: 26, height: (v / maxD) * 96, background: v === maxD ? 'var(--blue)' : 'var(--blue-tint-2)', borderRadius: 6 }} />
                  <span className="sc-tiny sc-muted">{d}</span>
                </div>
              ))}
            </div>
          </div>
          {/* spend by member */}
          <div className="sc-card sc-card__pad col gap12">
            <div className="sc-section-label" style={{ padding: 0 }}>Spend by member</div>
            {byMember.map(([n, v]) => (
              <div key={n} className="row gap12" style={{ alignItems: 'center' }}>
                <span style={{ width: 56, fontSize: 13, fontWeight: 600 }}>{n}</span>
                <div className="grow" style={{ height: 22, background: 'var(--surface)', borderRadius: 6, overflow: 'hidden' }}><div style={{ width: (v / maxM) * 100 + '%', height: '100%', background: 'var(--blue)', borderRadius: 6 }} /></div>
                <span className="mono sc-tiny" style={{ fontWeight: 600, width: 44, textAlign: 'right' }}>${v}</span>
              </div>
            ))}
          </div>
          {/* resulting balances */}
          <div>
            <div className="sc-section-label">Resulting balances</div>
            <div className="sc-card" style={{ overflow: 'hidden' }}>
              {bal.map((d, i) => <div key={i} style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}><DebtRow {...d} /></div>)}
            </div>
          </div>
          <Btn variant="secondary" icon="image">Export as image</Btn>
          <div style={{ height: 80 }} />
        </div>
      </Scroll>
      <BottomNav active="overview" conflicts={2} onNav={onNav} />
    </div>
  );
}

function StatChip({ label, value }) {
  return <div style={{ flex: 1, minWidth: 90, background: 'var(--surface)', borderRadius: 12, padding: '10px 12px' }}><div className="sc-tiny sc-muted">{label}</div><div className="mono" style={{ fontSize: 18, fontWeight: 700, marginTop: 2 }}>{value}</div></div>;
}

window.IncludeSheet = IncludeSheet; window.StatChip = StatChip;
Object.assign(window, { FilterSheet, SearchOverlay, Conflicts, Overview });
