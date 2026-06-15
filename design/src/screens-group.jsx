// screens-group.jsx — Group: Expenses, Filter, Search, Balances, Conflicts, Overview
const { useState: useStateG } = React;

/* shared demo group */
const GROUP = {
  emoji: '🏝️', name: 'Tulum Trip', members: ['You', 'Andrew', 'Bob', 'Maya', 'Tyler'],
};

const DAYS = [
  { label: 'Today', items: [
    { id: 1, cat: 'food', title: 'Dinner at La Negra', sub: 'Andrew paid · you owe $24.00', remaining: 24, original: 96, unread: true },
    { id: 2, cat: 'transit', title: 'Airport taxi', sub: 'You paid · Bob owes $14.50', remaining: 14.5, original: 58, owe: false },
  ]},
  { label: 'Wed, May 22', items: [
    { id: 3, cat: 'stay', title: 'Beach villa — night 2', sub: 'Maya paid · you owe $0 of $60', remaining: 0, original: 60, settled: true },
    { id: 4, cat: 'fun', title: 'Cenote day trip', sub: 'Bob paid · you owe $18.00', remaining: 18, original: 72 },
    { id: 5, cat: 'groc', title: 'Supermarket run', sub: 'You paid · 4 owe you', remaining: 33.2, original: 41.5 },
  ]},
];

function GroupExpenses({ state = 'populated', tab = 'Active', onAdd, onOpenExpense, onNav, onFilter, onSearch, drafts = 1, offline = false }) {
  const [sub, setSub] = useStateG(tab);
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar
        title={<span className="row gap8" style={{ alignItems: 'center' }}><span style={{ fontSize: 22 }}>{GROUP.emoji}</span> {GROUP.name}</span>}
        right={<div className="row gap4"><IconBtn name="search" onClick={onSearch} /><IconBtn name="filter" onClick={onFilter} /></div>}
      />
      {offline && <Banner variant="offline" icon="wifiOff">Offline — your changes will sync.</Banner>}
      <SubTabs tabs={['Active', 'All', 'Settled']} active={sub} onTab={setSub} />

      {state === 'loading' && (
        <Scroll surface><div style={{ paddingTop: 8 }}>{[0,1,2,3,4].map(i => <SkelRow key={i} />)}</div></Scroll>
      )}

      {state === 'empty' && (
        <EmptyState icon="receipt" title="No expenses yet" text="Add the first shared cost and ShareCost tracks who owes whom." cta="Add expense" onCta={onAdd} />
      )}

      {state === 'populated' && (
        <Scroll surface>
          {drafts > 0 && (
            <div className="row between" style={{ margin: '10px 16px 4px', padding: '10px 14px', background: 'var(--blue-tint)', borderRadius: 12 }}>
              <span className="row gap8" style={{ color: 'var(--blue-press)', fontWeight: 600, fontSize: 14 }}><Icon name="edit" size={16} /> <span>Drafts ({drafts})</span></span>
              <Icon name="chevR" size={16} style={{ color: 'var(--blue)' }} />
            </div>
          )}
          {DAYS.map(day => (
            <div key={day.label}>
              <div className="sc-dayhead">{day.label}</div>
              <div className="sc-list">
                {day.items.filter(it => !it.settled).map((it, i) => (
                  <div key={it.id} className={i ? 'sc-row__div' : ''} style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}>
                    <ExpenseRow {...it} onClick={() => onOpenExpense && onOpenExpense(it)} />
                  </div>
                ))}
                {day.items.some(it => it.settled) && <SettledTray items={day.items.filter(it => it.settled)} onOpenExpense={onOpenExpense} />}
              </div>
            </div>
          ))}
          <div style={{ height: 150 }} />
        </Scroll>
      )}

      {state !== 'loading' && <Fab label="Add expense" onClick={onAdd} />}
      <BottomNav active="expenses" conflicts={1} onNav={onNav} />
    </div>
  );
}

function SettledTray({ items, onOpenExpense }) {
  const [open, setOpen] = useStateG(false);
  return (
    <div style={{ boxShadow: '0 -1px 0 var(--border)' }}>
      <div className="sc-exp sc-exp--tap" onClick={() => setOpen(o => !o)}>
        <div className="sc-exp__icon" style={{ background: 'var(--blue-tint)', color: 'var(--blue)' }}><Icon name="check" size={20} /></div>
        <div className="sc-exp__main"><div className="sc-exp__title" style={{ color: 'var(--ink-2)' }}>Settled ({items.length})</div></div>
        <Icon name={open ? 'chevU' : 'chevD'} size={16} style={{ color: 'var(--ink-3)' }} />
      </div>
      {open && items.map(it => (
        <div key={it.id} style={{ boxShadow: '0 -1px 0 var(--border)' }}><ExpenseRow {...it} onClick={() => onOpenExpense && onOpenExpense(it)} /></div>
      ))}
    </div>
  );
}

/* ── Balances tab ─────────────────────────────────────────── */
function GroupBalances({ state = 'populated', onNav, onSettle }) {
  const debts = [
    { from: 'You', to: 'Andrew', amount: 42.0 },
    { from: 'Bob', to: 'You', amount: 14.5, owedToYou: true },
    { from: 'You', to: 'Maya', amount: 18.0 },
    { from: 'Tyler', to: 'Andrew', amount: 26.75 },
  ];
  const spend = [
    { cat: 'stay', label: 'Lodging', amt: 420, pct: 46 },
    { cat: 'food', label: 'Food & Drink', amt: 268, pct: 29 },
    { cat: 'fun', label: 'Activities', amt: 142, pct: 16 },
    { cat: 'transit', label: 'Transport', amt: 84, pct: 9 },
  ];
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar title="Balances" right={<button className="sc-chip sc-chip--ghost" style={{ height: 34 }}><Icon name="globe" size={15} /> USD <Icon name="chevD" size={14} /></button>} />
      {state === 'empty' ? (
        <EmptyState icon="checkCircle" title="All settled in this currency. 🎉" text="No one owes anyone right now. Nice work keeping the books clean." />
      ) : (
        <Scroll surface>
          <div className="sc-body__pad col gap16">
            <div>
              <div className="sc-section-label">Who owes whom</div>
              <div className="sc-card" style={{ overflow: 'hidden' }}>
                {debts.map((d, i) => (
                  <div key={i} style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}><DebtRow {...d} onClick={() => onSettle && onSettle(d)} /></div>
                ))}
              </div>
              <div className="sc-tiny sc-muted" style={{ padding: '8px 4px' }}>Balances are shown as plain pairs, never netted or simplified.</div>
            </div>
            <div>
              <div className="sc-section-label">Spending by category</div>
              <div className="sc-card sc-card__pad col gap16">
                {spend.map(s => (
                  <div key={s.cat} className="col gap8">
                    <div className="row between">
                      <span className="row gap8" style={{ fontSize: 14, fontWeight: 600 }}><Icon name={CAT[s.cat].icon} size={17} style={{ color: 'var(--ink-2)' }} /> {s.label}</span>
                      <span className="mono" style={{ fontSize: 14, fontWeight: 600 }}>${s.amt.toFixed(2)}</span>
                    </div>
                    <Progress value={s.pct} />
                  </div>
                ))}
                <div className="sc-divider" />
                <div className="row between"><span className="sc-muted" style={{ fontSize: 14 }}>Total group spend</span><span className="mono" style={{ fontWeight: 700 }}>$914.00</span></div>
              </div>
            </div>
            <div style={{ height: 80 }} />
          </div>
        </Scroll>
      )}
      <BottomNav active="balances" conflicts={1} onNav={onNav} />
    </div>
  );
}

window.GROUP = GROUP;
Object.assign(window, { GroupExpenses, GroupBalances });
