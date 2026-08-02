// screens-settings.jsx — 17 group settings · 18 profile & settings
const { useState: useStateSet } = React;

function SetRow({ icon, label, value, chip, danger, onClick, last, right }) {
  return (
    <button className="sc-row sc-row--tap" style={{ width: '100%', color: danger ? 'var(--red)' : 'var(--ink)', boxShadow: last ? undefined : '0 1px 0 var(--border) inset' }} onClick={onClick}>
      {icon && <Icon name={icon} size={20} style={{ color: danger ? 'var(--red)' : 'var(--ink-2)' }} />}
      <span className="grow" style={{ fontSize: 15, fontWeight: 600, textAlign: 'left' }}>{label}</span>
      {value && <span style={{ fontSize: 14, color: 'var(--ink-2)' }}>{value}</span>}
      {chip}
      {right !== undefined ? right : (!danger && <Icon name="chevR" size={15} style={{ color: 'var(--ink-3)' }} />)}
    </button>
  );
}
function Group({ label, children }) {
  return <div className="col gap8"><div className="sc-section-label" style={{ padding: '0 4px' }}>{label}</div><div className="sc-card" style={{ overflow: 'hidden' }}>{children}</div></div>;
}

/* ── 17 · Group settings ──────────────────────────────────── */
function GroupSettings({ onBack }) {
  const [reminder, setReminder] = useStateSet('Weekly');
  const members = [
    ['Alex Rivera', 'Admin', 'me'], ['Andrew Park', 'Admin'], ['Bob Lin', ''], ['Maya Kapoor', ''],
    ['Tyler Reed', 'Placeholder'], ['Sam Cole', 'Left'], ['Nina Alvarez', ''], ['Omar Haddad', ''],
    ['Priya Singh', ''], ['Quentin Lee', ''], ['Rosa Mendez', ''],
  ];
  const ROW_H = 57, MAX_VISIBLE = 8;
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar left={<IconBtn name="back" onClick={onBack} />} title="Group settings" />
      <Scroll surface>
        <div className="col gap16" style={{ padding: 16 }}>
          <Group label="About">
            <SetRow icon="sparkle" label="Emoji & name" value="🏝️ Tulum Trip" />
            <SetRow icon="globe" label="Base currency" value="USD" last />
          </Group>
          <div className="sc-tiny sc-muted" style={{ padding: '0 4px', marginTop: -8 }}>Changing base currency re-converts past expenses at today's rate.</div>

          <Group label="Share group">
            <div className="sc-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 12, paddingBlock: 16 }}>
              <div className="row gap12">
                <div style={{ width: 64, height: 64, borderRadius: 12, background: 'var(--surface)', display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: 'inset 0 0 0 1px var(--border)' }}><Icon name="qr" size={40} style={{ color: 'var(--ink)' }} /></div>
                <div className="grow col gap4"><span style={{ fontWeight: 600, fontSize: 14 }}>Invite link</span><span className="sc-tiny mono sc-muted" style={{ wordBreak: 'break-all' }}>split-evenly.app/j/8Kk2-Tulum</span></div>
              </div>
              <div className="row gap8"><Btn variant="secondary" sm icon="copy" style={{ flex: 1 }}>Copy link</Btn><Btn variant="secondary" sm icon="reload" style={{ flex: 1 }}>Rotate</Btn></div>
            </div>
          </Group>

          <Group label={`Members · ${members.length}`}>
            <div style={members.length > MAX_VISIBLE ? { maxHeight: ROW_H * MAX_VISIBLE, overflowY: 'auto' } : undefined}>
              {members.map(([n, role, me], i) => (
                <div key={n} className="sc-exp sc-exp--tap" style={i ? { boxShadow: '0 -1px 0 var(--border)' } : undefined}>
                  <Avatar name={n} me={!!me} size="sm" />
                  <span className="grow" style={{ fontWeight: 600, fontSize: 15 }}>{n}{me && <span className="sc-muted" style={{ fontWeight: 500 }}> · you</span>}</span>
                  {role && <Chip variant={role === 'Admin' ? 'blue' : role === 'Left' ? 'red' : 'amber'}>{role}</Chip>}
                  <Icon name="chevR" size={15} style={{ color: 'var(--ink-3)' }} />
                </div>
              ))}
            </div>
          </Group>

          <Group label="Conflict reminders">
            {['Off', 'Daily', 'Weekly'].map((r, i) => (
              <button key={r} className="sc-row sc-row--tap" style={{ width: '100%', boxShadow: i ? '0 1px 0 var(--border) inset' : undefined }} onClick={() => setReminder(r)}>
                <span className="grow" style={{ fontSize: 15, fontWeight: 600, textAlign: 'left' }}>{r}</span><Radio on={reminder === r} />
              </button>
            ))}
          </Group>

          <Group label="Categories"><SetRow icon="tag" label="Edit categories" value="8 active" last /></Group>

          <Group label="Storage">
            <div className="sc-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 10, paddingBlock: 16 }}>
              <div className="row between"><span style={{ fontSize: 14, fontWeight: 600 }}>Receipts & images</span><span className="mono sc-tiny sc-muted">212 MB of 1 GB</span></div>
              <Progress value={21} />
            </div>
          </Group>

          <Group label="Export">
            <SetRow icon="download" label="Export CSV" />
            <SetRow icon="download" label="Export JSON" />
            <SetRow icon="download" label="Export PDF" last />
          </Group>

          <Group label="Danger zone">
            <SetRow icon="archive" label="Archive group" />
            <SetRow icon="back" label="Leave group" danger last right={null} />
          </Group>
          <div style={{ height: 24 }} />
        </div>
      </Scroll>
    </div>
  );
}

/* ── 18 · Profile & settings ──────────────────────────────── */
function Profile({ onBack }) {
  const [analytics, setAnalytics] = useStateSet(true);
  const [appearance, setAppearance] = useStateSet('System');
  return (
    <div className="sc-screen sc-screen--surface" style={{ height: '100%' }}>
      <TopBar left={<IconBtn name="back" onClick={onBack} />} title="Profile" />
      <Scroll surface>
        <div className="col gap16" style={{ padding: 16 }}>
          <div className="sc-card sc-card__pad row gap12" style={{ alignItems: 'center' }}>
            <Avatar name="Alex Rivera" me size="lg" />
            <div className="grow"><div style={{ fontSize: 17, fontWeight: 700 }}>Alex Rivera</div><div className="sc-tiny sc-muted">alex@hey.com</div></div>
            <button className="sc-iconbtn"><Icon name="edit" size={20} style={{ color: 'var(--ink-2)' }} /></button>
          </div>

          <Group label="Preferences">
            <SetRow icon="globe" label="Base currency" value="USD" />
            <SetRow icon="wallet" label="Payment apps" value="Venmo +2" last />
          </Group>

          <Group label="Notifications">
            <NotifRow label="New expenses" on />
            <NotifRow label="Someone pays you" on />
            <NotifRow label="Conflict reminders" on={false} last />
          </Group>

          <Group label="Privacy">
            <div className="sc-row" style={{ width: '100%', cursor: 'pointer' }} onClick={() => setAnalytics(a => !a)}>
              <Icon name="chart" size={20} style={{ color: 'var(--ink-2)' }} />
              <span className="grow col" style={{ alignItems: 'flex-start' }}><span style={{ fontSize: 15, fontWeight: 600 }}>Anonymous analytics</span><span className="sc-tiny sc-muted">Never includes expense details</span></span>
              <Toggle on={analytics} />
            </div>
          </Group>

          <Group label="Appearance">
            <div className="sc-row" style={{ paddingBlock: 12 }}><div style={{ width: '100%' }}><Segmented opts={['System', 'Light', 'Dark']} value={appearance} onChange={setAppearance} /></div></div>
          </Group>

          <Group label="Support">
            <SetRow icon="comment" label="Send feedback" />
            <SetRow icon="lock" label="Privacy policy" />
            <SetRow icon="info" label="Terms of service" last />
          </Group>

          <Group label="Account">
            <SetRow icon="back" label="Sign out" />
            <SetRow icon="trash" label="Delete account" danger last right={null} />
          </Group>
          <div className="sc-tiny sc-muted" style={{ textAlign: 'center' }}>Evenly v2.4.0</div>
          <div style={{ height: 16 }} />
        </div>
      </Scroll>
    </div>
  );
}

function NotifRow({ label, on, last }) {
  const [v, setV] = useStateSet(on);
  return (
    <div className="sc-row" style={{ width: '100%', cursor: 'pointer', boxShadow: last ? undefined : '0 1px 0 var(--border) inset' }} onClick={() => setV(x => !x)}>
      <span className="grow" style={{ fontSize: 15, fontWeight: 600, textAlign: 'left' }}>{label}</span><Toggle on={v} />
    </div>
  );
}

window.SetRow = SetRow; window.SettingsGroup = Group;
Object.assign(window, { GroupSettings, Profile });
