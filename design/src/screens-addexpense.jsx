// screens-addexpense.jsx — 13 · Add / Edit expense with live split math
const { useState: useStateA } = React;

function AddExpense({ onBack, onSave, initialSplit = '%' }) {
  const members = ['You', 'Andrew', 'Bob', 'Maya'];
  const total = 99.95;
  const [split, setSplit] = useStateA(initialSplit);

  // demo split data per mode
  const pct = { You: 25, Andrew: 25, Bob: 25, Maya: 24.95 };
  const pctTotal = Object.values(pct).reduce((a, b) => a + b, 0);
  const exact = { You: 25, Andrew: 25, Bob: 20, Maya: 25 };
  const exactTotal = Object.values(exact).reduce((a, b) => a + b, 0);
  const off = total - exactTotal;

  return (
    <div className="sc-screen" style={{ height: '100%' }}>
      <TopBar
        left={<IconBtn name="close" onClick={onBack} />}
        title="New expense"
        right={<button className="sc-btn sc-btn-primary sc-btn--sm" style={{ paddingInline: 16 }} onClick={onSave}>Save</button>}
      />
      <Scroll surface>
        <div className="col gap16" style={{ padding: 16 }}>
          {/* amount */}
          <div className="sc-card sc-card__pad col" style={{ alignItems: 'center', gap: 10 }}>
            <div className="row gap8" style={{ alignItems: 'baseline' }}>
              <span className="mono" style={{ fontSize: 22, color: 'var(--ink-3)', fontWeight: 600 }}>$</span>
              <span className="mono" style={{ fontSize: 52, fontWeight: 600, letterSpacing: -1.5, lineHeight: 1 }}>99.95</span>
            </div>
            <button className="sc-chip sc-chip--ghost"><Icon name="globe" size={14} /> USD <Icon name="chevD" size={13} /></button>
          </div>

          <Field label="Title"><div className="sc-input">Group dinner — La Negra</div></Field>

          <Field label="Paid by">
            <button className="sc-input row between" style={{ width: '100%' }}>
              <span className="row gap8"><Avatar name="You" me size="sm" /> You</span>
              <Icon name="chevD" size={16} style={{ color: 'var(--ink-3)' }} />
            </button>
            <div className="sc-tiny sc-muted">Can also be "Someone outside the group"</div>
          </Field>

          {/* participants */}
          <div className="col gap8">
            <span className="sc-label">Participants</span>
            <div className="row gap8" style={{ flexWrap: 'wrap' }}>
              {members.map(m => <span key={m} className="sc-pchip sc-pchip--on"><Avatar name={m} me={m==='You'} size="xs" />{m}<Icon name="check" size={14} /></span>)}
              <span className="sc-pchip" style={{ color: 'var(--ink-3)' }}><Avatar name="?" size="xs" />Tyler</span>
              <button className="sc-pchip" style={{ color: 'var(--blue)', paddingInline: 14 }}><Icon name="plus" size={15} /> Add</button>
            </div>
          </div>

          {/* split */}
          <div className="col gap8">
            <span className="sc-label">Split</span>
            <Segmented opts={['Even', 'Share', '%', 'Exact']} value={split} onChange={setSplit} />

            <div className="sc-card" style={{ overflow: 'hidden', marginTop: 4 }}>
              {members.map((m, i) => (
                <div key={m} className="sc-exp" style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}>
                  <Avatar name={m} me={m==='You'} size="sm" />
                  <span className="grow" style={{ fontSize: 15, fontWeight: 600 }}>{m}</span>
                  {split === 'Even' && <span className="mono" style={{ fontWeight: 600 }}>$24.99</span>}
                  {split === 'Share' && <span className="row gap8"><span className="sc-chip">1×</span><span className="mono sc-muted">$24.99</span></span>}
                  {split === '%' && <span className="row gap8"><span className="sc-input sc-input--mono" style={{ height: 38, width: 78, justifyContent: 'flex-end' }}>{pct[m].toFixed(2)}%</span></span>}
                  {split === 'Exact' && <span className="sc-input sc-input--mono" style={{ height: 38, width: 92, justifyContent: 'flex-end' }}>${exact[m].toFixed(2)}</span>}
                </div>
              ))}
            </div>

            {/* live math footer per mode */}
            {split === '%' && (
              <div className="row between" style={{ padding: '4px 4px 0' }}>
                <span className="row gap8" style={{ fontSize: 13, fontWeight: 600, color: pctTotal === 100 ? 'var(--blue)' : 'var(--amber)' }}>
                  <Icon name={pctTotal === 100 ? 'checkCircle' : 'info'} size={15} /> <span>Total: {pctTotal.toFixed(2)}%</span>
                </span>
                {pctTotal !== 100 && <button className="sc-btn-text" style={{ height: 'auto', fontSize: 13 }}>Distribute remainder</button>}
              </div>
            )}
            {split === 'Exact' && (
              <div className="row between" style={{ padding: '4px 4px 0' }}>
                <span className="row gap8" style={{ fontSize: 13, fontWeight: 600, color: Math.abs(off) < 0.005 ? 'var(--blue)' : 'var(--red)' }}>
                  <Icon name={Math.abs(off) < 0.005 ? 'checkCircle' : 'alert'} size={15} />
                  <span>{Math.abs(off) < 0.005 ? 'Splits add up' : `Off by $${Math.abs(off).toFixed(2)}`}</span>
                </span>
                {Math.abs(off) >= 0.005 && <span className="mono sc-tiny sc-muted">$95.00 of $99.95</span>}
              </div>
            )}
          </div>

          {/* meta rows */}
          <div className="sc-card" style={{ overflow: 'hidden' }}>
            <MetaRow icon="calendar" label="Date" value="Today, May 23" />
            <MetaRow icon="tag" label="Category" value="Food & Drink · Dinner" />
            <MetaRow icon="edit" label="Notes" value="Add a note" muted last />
          </div>

          {/* receipts */}
          <div className="col gap8">
            <span className="sc-label">Receipts</span>
            <div className="row gap12">
              <Placeholder w={80} h={80} label="receipt" />
              <button className="sc-iconbtn" style={{ width: 80, height: 80, borderRadius: 12, background: 'var(--surface)', flexDirection: 'column', gap: 4, color: 'var(--ink-2)', fontSize: 12, fontWeight: 600 }}><Icon name="camera" size={22} /> Add</button>
            </div>
          </div>

          <div className="row gap8" style={{ justifyContent: 'center', color: 'var(--ink-3)', fontSize: 12, fontWeight: 600, paddingTop: 4 }}>
            <Icon name="check" size={14} /> Draft saved
          </div>
          <div style={{ height: 8 }} />
        </div>
      </Scroll>
    </div>
  );
}

function Field({ label, children }) {
  return <div className="sc-field"><span className="sc-label">{label}</span>{children}</div>;
}
function MetaRow({ icon, label, value, muted, last }) {
  return (
    <button className="sc-row sc-row--tap" style={{ width: '100%' }}>
      <Icon name={icon} size={20} style={{ color: 'var(--ink-2)' }} />
      <span className="grow" style={{ fontSize: 15, fontWeight: 600, textAlign: 'left' }}>{label}</span>
      <span style={{ fontSize: 14, color: muted ? 'var(--ink-3)' : 'var(--ink-2)' }}>{value}</span>
      <Icon name="chevR" size={15} style={{ color: 'var(--ink-3)' }} />
    </button>
  );
}

window.Field = Field;
Object.assign(window, { AddExpense });
