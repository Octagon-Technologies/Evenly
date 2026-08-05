// overview.jsx — "Start here" design-system spec sheet (opens the canvas)
function Swatch({ c, name, hex, dark }) {
  return (
    <div className="col gap8" style={{ minWidth: 0 }}>
      <div style={{ height: 56, borderRadius: 12, background: c, boxShadow: 'inset 0 0 0 1px rgba(11,18,32,0.08)' }} />
      <div><div style={{ fontSize: 12, fontWeight: 600 }}>{name}</div><div className="mono" style={{ fontSize: 11, color: 'var(--ink-3)' }}>{hex}</div></div>
    </div>
  );
}

function SystemOverview() {
  return (
    <div className="sc" style={{ width: '100%', background: 'var(--page)', padding: 40, display: 'flex', flexDirection: 'column', gap: 32 }}>
      <div className="row between" style={{ alignItems: 'flex-start', flexWrap: 'wrap', gap: 24 }}>
        <div className="col gap16" style={{ maxWidth: 460 }}>
          <Wordmark size={36} />
          <div style={{ fontSize: 17, color: 'var(--ink-2)', lineHeight: 1.6 }}>
            A free shared-expense <b style={{ color: 'var(--ink)' }}>tracker</b> — never a wallet. Evenly records who owes whom and hands settlement to your own payment app. Its signature move: pay back <b style={{ color: 'var(--ink)' }}>one expense</b>, not your whole balance — so every amount shows a large <b style={{ color: 'var(--ink)' }}>remaining</b> over a small, muted <b style={{ color: 'var(--ink)' }}>original</b>.
          </div>
        </div>
        <div className="col gap8" style={{ background: 'var(--surface)', borderRadius: 16, padding: 20, minWidth: 240 }}>
          <div className="sc-section-label" style={{ padding: 0 }}>Principles</div>
          {['Blue-led monochrome — one color, expressed by weight', 'Balances shown as plain pairs, never netted', 'Hairline borders over heavy shadows', 'Tabular mono figures, right-aligned', 'Calm blue for success — never green'].map(p => (
            <div key={p} className="row gap8" style={{ fontSize: 13, alignItems: 'flex-start' }}><Icon name="check" size={15} style={{ color: 'var(--blue)', marginTop: 1, flexShrink: 0 }} /><span style={{ lineHeight: 1.4 }}>{p}</span></div>
          ))}
        </div>
      </div>

      <div className="sc-divider" />

      {/* color */}
      <div className="col gap12">
        <div className="sc-section-label" style={{ padding: 0 }}>Color · blue-led monochrome</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(8, 1fr)', gap: 14 }}>
          <Swatch c="#2563EB" name="Blue / primary" hex="#2563EB" />
          <Swatch c="#1E40AF" name="Blue pressed" hex="#1E40AF" />
          <Swatch c="#DBE6FF" name="Tint" hex="#DBE6FF" />
          <Swatch c="#EFF4FF" name="Tint soft" hex="#EFF4FF" />
          <Swatch c="#0B1220" name="Ink" hex="#0B1220" />
          <Swatch c="#5B6577" name="Ink 2" hex="#5B6577" />
          <Swatch c="#F6F8FB" name="Surface" hex="#F6F8FB" />
          <Swatch c="#E5484D" name="Reserved red" hex="#E5484D" />
        </div>
      </div>

      <div className="row gap16" style={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
        {/* type */}
        <div className="col gap12" style={{ flex: 1, minWidth: 300 }}>
          <div className="sc-section-label" style={{ padding: 0 }}>Type · IBM Plex Sans + Plex Mono</div>
          <div className="col gap8" style={{ background: 'var(--surface)', borderRadius: 16, padding: 20 }}>
            <div style={{ fontSize: 28, fontWeight: 700, letterSpacing: -0.6 }}>Large title 28/700</div>
            <div style={{ fontSize: 18, fontWeight: 600 }}>Section title 18/600</div>
            <div style={{ fontSize: 15 }}>Body 15/400 — calm and quiet.</div>
            <div className="sc-tiny sc-muted">Caption 12 · muted</div>
            <div className="sc-divider" style={{ margin: '4px 0' }} />
            <div className="row between"><span className="sc-muted" style={{ fontSize: 13 }}>Amount · remaining / original</span>
              <Amount remaining={48} original={96} /></div>
          </div>
        </div>
        {/* components */}
        <div className="col gap12" style={{ flex: 1, minWidth: 300 }}>
          <div className="sc-section-label" style={{ padding: 0 }}>Core components</div>
          <div className="col gap10" style={{ background: 'var(--surface)', borderRadius: 16, padding: 16 }}>
            <div className="row gap8" style={{ flexWrap: 'wrap' }}>
              <Chip variant="solid">you're owed</Chip><Chip>you owe</Chip><Chip variant="blue" icon="check">Settled</Chip><Chip variant="amber">conflict</Chip>
            </div>
            <div className="row gap8"><div style={{ flex: 1 }}><Btn sm>Primary</Btn></div><div style={{ flex: 1 }}><Btn sm variant="secondary">Secondary</Btn></div><Btn sm variant="text">Text</Btn></div>
            <div className="sc-card" style={{ overflow: 'hidden' }}>
              <DebtRow from="You" to="Andrew" amount={42} />
              <div style={{ boxShadow: '0 -1px 0 var(--border)' }}><DebtRow from="Bob" to="You" amount={14.5} owedToYou /></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

window.SystemOverview = SystemOverview;
