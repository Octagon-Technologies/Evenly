// screens-expense.jsx — Expense detail (12) + Add/Edit with split math (13)
const { useState: useStateE } = React;

/* ── 12 · Expense detail ──────────────────────────────────── */
function ExpenseDetail({ state = 'populated', onBack, onSettleThis }) {
  const [over, setOver] = useStateE(false);
  const split = [
    { name: 'You', share: 24, remaining: 24, paid: 0, of: 24, me: true },
    { name: 'Andrew', share: 24, remaining: 0, paid: 24, of: 24, payer: true },
    { name: 'Bob', share: 24, remaining: 24, paid: 0, of: 24 },
    { name: 'Maya', share: 24, remaining: 8, paid: 16, of: 24 },
  ];
  if (state === 'error') return <ErrorScreen onBack={onBack} />;
  return (
    <div className="sc-screen" style={{ height: '100%' }}>
      <TopBar
        left={<IconBtn name="back" onClick={onBack} />}
        title="Dinner at La Negra" sub="Food & Drink"
        right={<IconBtn name="more" onClick={() => setOver(true)} />}
      />
      {state === 'loading' ? (
        <Scroll><div className="sc-body__pad col gap16">
          <Skel h={132} r={16} />
          <Skel w="40%" h={14} /><SkelRow /><SkelRow /><SkelRow />
        </div></Scroll>
      ) : (
      <Scroll surface>
        <div className="sc-body__pad col gap16">
          {/* amount header card */}
          <div className="sc-card sc-card__pad">
            <div className="row between" style={{ alignItems: 'flex-start' }}>
              <div>
                <div className="sc-amt__orig mono" style={{ fontSize: 14, textAlign: 'left' }}>$96.00</div>
                <div className="mono" style={{ fontSize: 40, fontWeight: 600, letterSpacing: -1, lineHeight: 1 }}>$48.00</div>
                <div className="sc-tiny sc-muted" style={{ marginTop: 4 }}>remaining of original</div>
              </div>
              <Chip variant="blue" icon="food" lg>Food</Chip>
            </div>
            <div className="sc-divider" style={{ margin: '14px 0' }} />
            <div className="row between">
              <span className="row gap8"><Avatar name="Andrew" size="sm" /><span style={{ fontSize: 14 }}><b>Andrew</b> paid</span></span>
              <span className="sc-tiny sc-muted">May 23 · 8:40 PM</span>
            </div>
          </div>

          {/* receipts */}
          <div>
            <div className="sc-section-label">Receipts</div>
            <div className="row gap12" style={{ overflow: 'hidden' }}>
              {[0,1].map(i => <Placeholder key={i} w={84} h={108} label="receipt" />)}
              <button className="sc-iconbtn" style={{ width: 84, height: 108, borderRadius: 12, background: 'var(--surface)', flexDirection: 'column', gap: 4, color: 'var(--ink-2)', fontSize: 12, fontWeight: 600 }}><Icon name="camera" size={22} /> Add</button>
            </div>
          </div>

          {/* split breakdown */}
          <div>
            <div className="sc-section-label">Split between 4 · even</div>
            <div className="sc-card" style={{ overflow: 'hidden' }}>
              {split.map((s, i) => (
                <div key={s.name} className="sc-exp" style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}>
                  <Avatar name={s.name} me={s.me} size="sm" />
                  <div className="sc-exp__main">
                    <div className="sc-exp__title" style={{ fontWeight: 600 }}>{s.name}{s.payer && <span className="sc-muted" style={{ fontWeight: 500, fontSize: 13 }}> · paid</span>}{s.me && <span className="sc-muted" style={{ fontWeight: 500, fontSize: 13 }}> · you</span>}</div>
                    <div className="row gap8" style={{ marginTop: 5, alignItems: 'center' }}>
                      <div style={{ flex: 1, maxWidth: 110 }}><Progress value={(s.paid / s.of) * 100} /></div>
                      <span className="sc-tiny sc-muted mono">Paid ${s.paid} of ${s.of}</span>
                    </div>
                  </div>
                  <div className="col" style={{ alignItems: 'flex-end', gap: 6 }}>
                    <div className="mono" style={{ fontWeight: 600, fontSize: 15 }}>${s.remaining.toFixed(2)}</div>
                    {s.me && s.remaining > 0 && <button className="sc-btn sc-btn-primary sc-btn--sm" onClick={onSettleThis}>Settle this</button>}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* comments */}
          <div>
            <div className="sc-section-label">Comments</div>
            <div className="col gap12">
              <Comment name="Maya" text="I already sent Andrew $16 in cash 🙌" time="2h" />
              <Comment name="You" me text="Nice — I'll settle my half tonight." time="1h" />
            </div>
            <div className="row gap8" style={{ marginTop: 12 }}>
              <div className="sc-input grow" style={{ height: 44 }}><span className="sc-muted">Add a comment…</span></div>
              <button className="sc-iconbtn" style={{ background: 'var(--blue)', color: '#fff', width: 44, height: 44 }}><Icon name="send" size={18} /></button>
            </div>
          </div>

          <Collapsible title="History" icon="history" sub="3 events" />
          <Collapsible title="Refunds" icon="refund" sub="None yet" />
          <div style={{ height: 24 }} />
        </div>
      </Scroll>
      )}
      {over && <Modal onClose={() => setOver(false)}>
        <div className="col">
          {[['edit','Edit'],['refund','Issue refund'],['camera','Add receipt'],['share','Share']].map(([ic,l]) => (
            <button key={l} className="sc-row sc-row--tap" style={{ borderRadius: 10 }}><Icon name={ic} size={20} style={{ color: 'var(--ink-2)' }} /><span className="grow" style={{ fontSize: 15, fontWeight: 500 }}>{l}</span></button>
          ))}
          <button className="sc-row sc-row--tap" style={{ borderRadius: 10, color: 'var(--red)' }}><Icon name="trash" size={20} /><span className="grow" style={{ fontSize: 15, fontWeight: 600, textAlign: 'left' }}>Delete</span></button>
        </div>
      </Modal>}
    </div>
  );
}

function Comment({ name, text, time, me }) {
  return (
    <div className="row gap8" style={{ alignItems: 'flex-start', flexDirection: me ? 'row-reverse' : 'row' }}>
      <Avatar name={name} me={me} size="sm" />
      <div style={{ maxWidth: '78%' }}>
        <div style={{ background: me ? 'var(--blue)' : 'var(--surface)', color: me ? '#fff' : 'var(--ink)', borderRadius: 14, padding: '9px 13px', fontSize: 14, lineHeight: 1.4 }}>{text}</div>
        <div className="sc-tiny sc-muted" style={{ marginTop: 3, textAlign: me ? 'right' : 'left' }}>{name} · {time}</div>
      </div>
    </div>
  );
}

function Collapsible({ title, icon, sub }) {
  const [open, setOpen] = useStateE(false);
  return (
    <div className="sc-card" style={{ overflow: 'hidden' }}>
      <button className="sc-row sc-row--tap" style={{ width: '100%' }} onClick={() => setOpen(o => !o)}>
        <Icon name={icon} size={20} style={{ color: 'var(--ink-2)' }} />
        <span className="grow" style={{ fontSize: 15, fontWeight: 600, textAlign: 'left' }}>{title}</span>
        <span className="sc-tiny sc-muted">{sub}</span>
        <Icon name={open ? 'chevU' : 'chevD'} size={16} style={{ color: 'var(--ink-3)' }} />
      </button>
      {open && <div style={{ padding: '0 16px 14px', boxShadow: '0 1px 0 var(--border) inset' }}><div className="sc-tiny sc-muted" style={{ paddingTop: 12 }}>Andrew added this expense · May 23, 8:40 PM</div></div>}
    </div>
  );
}

function Placeholder({ w, h, label }) {
  return (
    <div style={{ width: w, height: h, borderRadius: 12, background: 'repeating-linear-gradient(135deg, #F0F3F8 0 8px, #F6F8FB 8px 16px)', boxShadow: 'inset 0 0 0 1px var(--border)', display: 'flex', alignItems: 'flex-end', justifyContent: 'center', paddingBottom: 8 }}>
      <span className="mono" style={{ fontSize: 10, color: 'var(--ink-3)' }}>{label}</span>
    </div>
  );
}

function ErrorScreen({ onBack }) {
  return (
    <div className="sc-screen" style={{ height: '100%' }}>
      <TopBar left={<IconBtn name="back" onClick={onBack} />} title="" />
      <div className="sc-empty">
        <div className="sc-empty__icon" style={{ color: 'var(--red)', background: 'var(--red-tint)' }}><Icon name="alert" size={30} /></div>
        <div className="sc-empty__title">Couldn't load this expense</div>
        <div className="sc-empty__text">Something went wrong on our side. Check your connection and try again.</div>
        <div className="col gap8" style={{ width: '100%', maxWidth: 240, marginTop: 6 }}>
          <Btn icon="reload">Reload</Btn>
          <Btn variant="text" icon="mail">Send feedback</Btn>
        </div>
      </div>
    </div>
  );
}

window.Placeholder = Placeholder;
window.ErrorScreen = ErrorScreen;
Object.assign(window, { ExpenseDetail });
