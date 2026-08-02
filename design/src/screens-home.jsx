// screens-home.jsx — 4 home/group list · 5 new group · 19 archived · 20 join
const { useState: useStateH } = React;

const GROUPS = [
  { emoji: '🏝️', name: 'Tulum Trip', members: 5, status: 'owe', amount: 60, last: 'Andrew added Dinner · $96 · 2h ago', unread: true },
  { emoji: '🏠', name: 'The Mission Flat', members: 3, status: 'owed', amount: 128.4, last: 'You added Internet · $80 · yesterday' },
  { emoji: '💸', name: 'Weekend in Austin', members: 4, status: 'settled', last: 'Maya settled up · 3d ago' },
  { emoji: '🎂', name: "Dad's 60th", members: 6, status: 'owe', amount: 45, last: 'Tyler added Cake · $120 · 5d ago' },
];

function GroupRow({ g, onClick, archived }) {
  return (
    <div className="sc-exp sc-exp--tap" onClick={onClick}>
      <div style={{ width: 46, height: 46, borderRadius: 13, background: 'var(--surface)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 24, flexShrink: 0 }}>{g.emoji}</div>
      <div className="sc-exp__main">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 16, fontWeight: 600, letterSpacing: '-0.1px' }}>
          <span style={{ whiteSpace: 'nowrap' }}>{g.name}</span>
          {g.unread && <span className="sc-dot" style={{ flexShrink: 0 }} />}
        </div>
        <div className="sc-exp__sub">{g.members} members · {g.last}</div>
      </div>
      <div className="col" style={{ alignItems: 'flex-end', gap: 4, flexShrink: 0 }}>
        {archived ? <button className="sc-chip sc-chip--ghost"><Icon name="archive" size={14} />Unarchive</button>
          : g.status === 'settled' ? <Chip variant="blue" icon="check">Settled</Chip>
          : <>
              <span className="sc-tiny sc-muted" style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>{g.status === 'owe' ? 'you owe' : "you're owed"}</span>
              <Chip variant={g.status === 'owed' ? 'solid' : ''}><span className="mono">${g.amount.toFixed(2)}</span></Chip>
            </>}
      </div>
    </div>
  );
}

/* ── 4 · Home / group list ────────────────────────────────── */
function Home({ state = 'populated', onOpenGroup, onNewGroup }) {
  const [arch, setArch] = useStateH(false);
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar
        left={<Avatar name="Alex Rivera" me />}
        title={<span style={{ fontSize: 17 }}>Evenly</span>} center
        right={<button className="sc-iconbtn" style={{ color: 'var(--blue)' }} onClick={onNewGroup}><Icon name="plus" size={24} /></button>}
      />
      {state === 'loading' && <Scroll surface><div style={{ paddingTop: 8 }}>{[0,1,2].map(i => <SkelRow key={i} />)}</div></Scroll>}
      {state === 'empty' && <EmptyState icon="users" title="No groups yet" text="Create a group to start tracking who owes whom on your next trip or shared bill." cta="Create a group" onCta={onNewGroup} />}
      {state === 'populated' && (
        <Scroll surface>
          <div className="col gap16" style={{ padding: 16 }}>
            <div>
              <div className="sc-section-label">Active groups</div>
              <div className="sc-card" style={{ overflow: 'hidden' }}>
                {GROUPS.map((g, i) => <div key={i} style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}><GroupRow g={g} onClick={onOpenGroup} /></div>)}
              </div>
            </div>
            <button className="sc-card sc-card__pad row between" style={{ width: '100%' }} onClick={() => setArch(a => !a)}>
              <span className="row gap8" style={{ fontWeight: 600, color: 'var(--ink-2)' }}><Icon name="archive" size={18} /> Archived (2)</span>
              <Icon name={arch ? 'chevU' : 'chevD'} size={18} style={{ color: 'var(--ink-3)' }} />
            </button>
            {arch && <div className="sc-card" style={{ overflow: 'hidden' }}>
              {[{ emoji: '⛷️', name: 'Tahoe 2025', members: 5, last: 'Settled · Mar 2025' }, { emoji: '🍝', name: 'Supper Club', members: 8, last: 'Settled · Jan 2025' }].map((g, i) => (
                <div key={i} style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}><GroupRow g={g} archived /></div>
              ))}
            </div>}
            <div style={{ height: 8 }} />
          </div>
        </Scroll>
      )}
    </div>
  );
}

/* ── 5 · New group sheet ──────────────────────────────────── */
function NewGroup({ onBack }) {
  const emojis = ['💸', '🏝️', '🏠', '🍝', '✈️', '🎉', '⛷️', '🎂'];
  const [sel, setSel] = useStateH('💸');
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <DimBackdrop label="Home" />
      <Sheet onClose={onBack} title="New group">
        <div className="col gap16" style={{ paddingBottom: 4 }}>
          <div className="col" style={{ alignItems: 'center', gap: 12 }}>
            <div style={{ width: 72, height: 72, borderRadius: 22, background: 'var(--surface)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 38, boxShadow: 'inset 0 0 0 1px var(--border)' }}>{sel}</div>
            <div className="row gap8" style={{ flexWrap: 'wrap', justifyContent: 'center' }}>
              {emojis.map(e => <button key={e} onClick={() => setSel(e)} style={{ width: 40, height: 40, borderRadius: 11, fontSize: 22, background: sel === e ? 'var(--blue-tint)' : 'var(--surface)', boxShadow: sel === e ? 'inset 0 0 0 2px var(--blue)' : 'inset 0 0 0 1px var(--border)' }}>{e}</button>)}
            </div>
          </div>
          <div className="sc-field"><span className="sc-label">Group name</span><div className="sc-input sc-input--focus">Lisbon with the crew</div></div>
          <div className="sc-field"><span className="sc-label">Base currency</span><button className="sc-input row between"><span className="row gap8"><Icon name="globe" size={18} style={{ color: 'var(--ink-2)' }} /> USD — US Dollar</span><Icon name="chevD" size={16} style={{ color: 'var(--ink-3)' }} /></button></div>
          <Btn icon="check">Create group</Btn>
        </div>
      </Sheet>
    </div>
  );
}

/* ── 19 · Archived groups ─────────────────────────────────── */
function Archived({ onBack }) {
  const arch = [
    { emoji: '⛷️', name: 'Tahoe 2025', members: 5, last: 'Settled · Mar 2025' },
    { emoji: '🍝', name: 'Supper Club', members: 8, last: 'Settled · Jan 2025' },
    { emoji: '🏕️', name: 'Big Sur camping', members: 4, last: 'Settled · Aug 2024' },
  ];
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar left={<IconBtn name="back" onClick={onBack} />} title="Archived" sub="3 groups" />
      <Scroll surface>
        <div className="col gap16" style={{ padding: 16 }}>
          <div className="sc-card" style={{ overflow: 'hidden' }}>
            {arch.map((g, i) => <div key={i} style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}><GroupRow g={g} archived /></div>)}
          </div>
          <div className="sc-tiny sc-muted" style={{ textAlign: 'center' }}>Archived groups stay read-only until you unarchive them.</div>
        </div>
      </Scroll>
    </div>
  );
}

/* ── 20 · Join group sheet ────────────────────────────────── */
function JoinGroup({ already = false, onBack }) {
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <DimBackdrop label="Invite" />
      <Sheet onClose={onBack}>
        <div className="col gap16" style={{ alignItems: 'center', textAlign: 'center', paddingBottom: 4 }}>
          <div style={{ width: 80, height: 80, borderRadius: 24, background: 'var(--surface)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 42, boxShadow: 'inset 0 0 0 1px var(--border)' }}>🏝️</div>
          <div><div style={{ fontSize: 20, fontWeight: 700 }}>Tulum Trip</div><div className="sc-tiny sc-muted" style={{ marginTop: 4 }}>5 members · Andrew, Bob, Maya +2</div></div>
          <div className="sc-av-stack" style={{ marginTop: -4 }}>{['Andrew','Bob','Maya','Tyler'].map(n => <Avatar key={n} name={n} size="sm" />)}</div>
          {already ? (
            <div className="col gap8" style={{ width: '100%' }}>
              <div className="sc-chip sc-chip--blue" style={{ alignSelf: 'center' }}><Icon name="check" size={14} /> You're already in this group</div>
              <Btn icon="chevR" onClick={onBack}>Open group</Btn>
            </div>
          ) : (
            <div className="col gap8" style={{ width: '100%' }}><Btn icon="users">Join group</Btn><Btn variant="text">Not now</Btn></div>
          )}
        </div>
      </Sheet>
    </div>
  );
}

window.GROUPS = GROUPS; window.GroupRow = GroupRow;
Object.assign(window, { Home, NewGroup, Archived, JoinGroup });
