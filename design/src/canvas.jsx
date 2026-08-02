// canvas.jsx — composes all Evenly screens onto the design canvas.
// DCSection inspects children for element.type === DCArtboard, so Frame() is
// CALLED as a function (returns a DCArtboard element); the stateful state-toggle
// lives in the inner <StateView> component.
const { useState: useStateC } = React;

const PAD = 30;

function StateView({ states, render, width, h }) {
  const [st, setSt] = useStateC(states ? states[0] : null);
  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: PAD, gap: 12 }}>
      {states &&
      <div className="sc sc-state-toggle">
          {states.map((s) => <button key={s} className={st === s ? 'on' : ''} onClick={() => setSt(s)}>{s}</button>)}
        </div>
      }
      <div style={{ width, height: h, flexShrink: 0 }}>{render(st)}</div>
    </div>);

}

// returns a DCArtboard ELEMENT (call as Frame({...}), not <Frame/>)
function Frame({ id, label, h = 844, states, render, width = 390 }) {
  const abW = width + PAD * 2;
  const abH = h + PAD * 2 + (states ? 44 : 0);
  return (
    <DCArtboard id={id} label={label} width={abW} height={abH} style={{ background: 'transparent', boxShadow: 'none' }}>
      <StateView states={states} render={render} width={width} h={h} data-comment-anchor="513fa52af0-button-32-15" />
    </DCArtboard>);

}
const P = (extra) => (node) => <Phone {...extra}>{node}</Phone>;

function App() {
  return (
    <DesignCanvas>

      {/* ───────── START HERE ───────── */}
      <DCSection id="start" title="Evenly" subtitle="Complete mobile UI · blue-led monochrome · light-first financial ledger">
        <DCArtboard id="system" label="Design system" width={820} height={760}>
          <div style={{ width: '100%', height: '100%', overflow: 'hidden', background: '#fff' }}><SystemOverview /></div>
        </DCArtboard>
      </DCSection>

      {/* ───────── AUTH & ONBOARDING ───────── */}
      <DCSection id="auth" title="Auth & onboarding" subtitle="First run — sign in, magic link, and the setup carousel">
        {Frame({ id: 'signin', label: 'Sign in', render: () => <Phone><SignIn /></Phone> })}
        {Frame({ id: 'magic', label: 'Email magic link', states: ['input', 'loading', 'sent'], render: (s) => <Phone><MagicLink state={s} /></Phone> })}
        {Frame({ id: 'onb-name', label: 'Onboarding · name', render: () => <Phone><Onboarding step={0} /></Phone> })}
        {Frame({ id: 'onb-cur', label: 'Onboarding · currency', render: () => <Phone><Onboarding step={1} /></Phone> })}
        {Frame({ id: 'onb-pay', label: 'Onboarding · payment handle', render: () => <Phone><Onboarding step={2} /></Phone> })}
        {Frame({ id: 'onb-analytics', label: 'Onboarding · analytics', render: () => <Phone><Onboarding step={3} /></Phone> })}
        {Frame({ id: 'onb-notify', label: 'Onboarding · notifications', render: () => <Phone><Onboarding step={4} /></Phone> })}
      </DCSection>

      {/* ───────── HOME ───────── */}
      <DCSection id="home" title="Home" subtitle="Group list, the new-group sheet, and archived groups">
        {Frame({ id: 'home', label: 'Home · group list', states: ['populated', 'empty', 'loading'], render: (s) => <Phone><Home state={s} /></Phone> })}
        {Frame({ id: 'newgroup', label: 'New group sheet', render: () => <Phone><NewGroup /></Phone> })}
        {Frame({ id: 'archived', label: 'Archived groups', render: () => <Phone><Archived /></Phone> })}
        {Frame({ id: 'join', label: 'Join group', render: () => <Phone><JoinGroup /></Phone> })}
        {Frame({ id: 'join2', label: 'Join · already a member', render: () => <Phone><JoinGroup already /></Phone> })}
      </DCSection>

      {/* ───────── CORE LEDGER ───────── */}
      <DCSection id="core" title="Core ledger" subtitle="Group expenses, the split editor, an expense, and settling up — the heart of the product">
        {Frame({ id: 'grp-exp', label: 'Group · Expenses', states: ['populated', 'empty', 'loading'], render: (s) => <Phone><GroupExpenses state={s} /></Phone> })}
        {Frame({ id: 'add-pct', label: 'Add expense · % split', h: 1160, render: () => <Phone height={1160}><AddExpense initialSplit="%" /></Phone> })}
        {Frame({ id: 'add-exact', label: 'Add expense · Exact split', h: 1120, render: () => <Phone height={1120}><AddExpense initialSplit="Exact" /></Phone> })}
        {Frame({ id: 'exp-detail', label: 'Expense detail', h: 1320, states: ['populated', 'loading', 'error'], render: (s) => <Phone height={s === 'populated' ? 1320 : 844}><ExpenseDetail state={s} /></Phone> })}
        {Frame({ id: 'settle-1', label: 'Settle one expense', render: () => <Phone><SettleSingle /></Phone> })}
        {Frame({ id: 'settle-fx', label: 'Settle · FX disclosure', render: () => <Phone><SettleSingle fx /></Phone> })}
        {Frame({ id: 'settle-person', label: 'Settle a person · step 1', render: () => <Phone><SettlePerson step={1} /></Phone> })}
        {Frame({ id: 'settle-person2', label: 'Settle a person · step 2', render: () => <Phone><SettlePerson step={2} /></Phone> })}
        {Frame({ id: 'deeplink', label: 'Deep-link confirm', render: () => <Phone><DeepLinkConfirm /></Phone> })}
      </DCSection>

      {/* ───────── GROUP TABS ───────── */}
      <DCSection id="grouptabs" title="Group tabs" subtitle="Balances, conflicts, overview, and the filter / search overlays">
        {Frame({ id: 'balances', label: 'Balances', states: ['populated', 'empty'], render: (s) => <Phone><GroupBalances state={s} /></Phone> })}
        {Frame({ id: 'conflicts', label: 'Conflicts', render: () => <Phone><Conflicts /></Phone> })}
        {Frame({ id: 'include', label: 'Include member sheet', render: () => <Phone><Conflicts sheet /></Phone> })}
        {Frame({ id: 'overview', label: 'Trip overview', h: 1500, render: () => <Phone height={1500}><Overview /></Phone> })}
        {Frame({ id: 'filter', label: 'Filter sheet', render: () => <Phone><FilterSheet /></Phone> })}
        {Frame({ id: 'search', label: 'Search overlay', render: () => <Phone><SearchOverlay /></Phone> })}
      </DCSection>

      {/* ───────── SETTINGS ───────── */}
      <DCSection id="settings" title="Settings & profile" subtitle="Group settings, your profile, and account preferences">
        {Frame({ id: 'grp-settings', label: 'Group settings', h: 1560, render: () => <Phone height={1560}><GroupSettings /></Phone> })}
        {Frame({ id: 'profile', label: 'Profile & settings', h: 1320, render: () => <Phone height={1320}><Profile /></Phone> })}
      </DCSection>

      {/* ───────── JOIN & RECONCILE ───────── */}
      <DCSection id="reconcile" title="Join & reconcile" subtitle="Claiming pre-existing activity when you join a group late">
        {Frame({ id: 'reconcile', label: 'Reconcile activity', render: () => <Phone><Reconcile /></Phone> })}
        {Frame({ id: 'reconcile2', label: 'Reconcile · confirm', render: () => <Phone><Reconcile confirm /></Phone> })}
      </DCSection>

      {/* ───────── STATE KIT ───────── */}
      <DCSection id="statekit" title="State kit" subtitle="Cross-cutting states — offline, pending sync, hard error, skeleton loading">
        {Frame({ id: 'sk-offline', label: 'Offline + pending sync', render: () => <Phone><StateKitOffline /></Phone> })}
        {Frame({ id: 'sk-skel', label: 'Skeleton loading', render: () => <Phone><StateKitSkeleton /></Phone> })}
        {Frame({ id: 'sk-error', label: 'Hard error', render: () => <Phone><ErrorScreen /></Phone> })}
        {Frame({ id: 'sk-empty', label: 'Empty state', render: () => <Phone><GroupExpenses state="empty" /></Phone> })}
      </DCSection>

    </DesignCanvas>);

}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);