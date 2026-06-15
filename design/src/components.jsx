// components.jsx — ShareCost shared UI primitives. Exports to window.
// Depends on: Icon (icons.jsx)

const { useState } = React;

/* ── shared demo data ─────────────────────────────────────── */
const CAT = {
  food:   { icon: 'food',   label: 'Food & Drink' },
  groc:   { icon: 'cart',   label: 'Groceries' },
  transit:{ icon: 'car',    label: 'Transport' },
  stay:   { icon: 'bed',    label: 'Lodging' },
  fun:    { icon: 'ticket', label: 'Activities' },
  utils:  { icon: 'bolt',   label: 'Utilities' },
  home:   { icon: 'home',   label: 'Household' },
  gift:   { icon: 'gift',   label: 'Gifts' },
};

/* ── status bar ───────────────────────────────────────────── */
function StatusBar({ dark = false, time = '9:41' }) {
  const c = dark ? '#fff' : '#0B1220';
  return (
    <div className={'sc-status' + (dark ? ' sc-status--ondark' : '')}>
      <span className="sc-status__time">{time}</span>
      <div className="sc-status__icons">
        <svg width="18" height="11" viewBox="0 0 18 11"><rect x="0" y="7" width="3" height="4" rx=".6" fill={c}/><rect x="4.5" y="4.8" width="3" height="6.2" rx=".6" fill={c}/><rect x="9" y="2.4" width="3" height="8.6" rx=".6" fill={c}/><rect x="13.5" y="0" width="3" height="11" rx=".6" fill={c}/></svg>
        <svg width="16" height="11" viewBox="0 0 16 11"><path d="M8 3c2 0 3.8.8 5.2 2.1l1-1A9 9 0 008 1.3 9 9 0 001.8 4.1l1 1A7.3 7.3 0 018 3z" fill={c}/><path d="M8 6.2c1.1 0 2.1.4 2.9 1.2l1-1A5.6 5.6 0 008 4.7a5.6 5.6 0 00-3.9 1.6l1 1A4.1 4.1 0 018 6.2z" fill={c}/><circle cx="8" cy="9.4" r="1.3" fill={c}/></svg>
        <svg width="25" height="12" viewBox="0 0 25 12"><rect x="0.5" y="0.5" width="21" height="11" rx="3" stroke={c} strokeOpacity=".4" fill="none"/><rect x="2" y="2" width="16" height="8" rx="1.6" fill={c}/><path d="M23 4v4c.8-.3 1.3-1 1.3-2S23.8 4.3 23 4z" fill={c} fillOpacity=".5"/></svg>
      </div>
    </div>
  );
}

/* ── phone frame ──────────────────────────────────────────── */
function Phone({ children, height = 844, dark = false, time = '9:41', shadow = true, statusbar = true }) {
  return (
    <div className={'sc sc-phone' + (shadow ? ' sc-phone--shadow' : '')} style={{ height }}>
      {statusbar && <StatusBar dark={dark} time={time} />}
      <div className="sc-screen" style={{ flex: 1, minHeight: 0 }}>{children}</div>
      <div className={'sc-home' + (dark ? ' sc-home--ondark' : '')} />
    </div>
  );
}

/* ── top bar ──────────────────────────────────────────────── */
function TopBar({ left, title, sub, right, center, style }) {
  return (
    <div className="sc-topbar" style={style}>
      {left}
      {center ? <div className="grow" style={{ textAlign: 'center' }}>
        <div className="sc-topbar__title">{title}</div>
        {sub && <div className="sc-topbar__sub">{sub}</div>}
      </div> : <div className="grow">
        {title && <div className="sc-topbar__title">{title}</div>}
        {sub && <div className="sc-topbar__sub">{sub}</div>}
      </div>}
      {right}
    </div>
  );
}
function IconBtn({ name, onClick, active, size = 22, badge }) {
  return (
    <button className={'sc-iconbtn' + (active ? ' sc-iconbtn--active' : '')} onClick={onClick} style={{ position: 'relative' }}>
      <Icon name={name} size={size} />
      {badge ? <span style={{ position: 'absolute', top: 6, right: 6, width: 8, height: 8, borderRadius: 4, background: 'var(--blue)' }} /> : null}
    </button>
  );
}

/* ── avatar ───────────────────────────────────────────────── */
function Avatar({ name = '?', me = false, size = '', tone }) {
  const initials = name.split(' ').map(s => s[0]).slice(0, 2).join('').toUpperCase();
  const cls = 'sc-av' + (me ? ' sc-av--me' : '') + (size ? ' sc-av--' + size : '');
  return <span className={cls} style={tone && !me ? { background: tone.bg, color: tone.fg, boxShadow: 'none' } : undefined}>{initials}</span>;
}

/* ── chips ────────────────────────────────────────────────── */
function Chip({ children, variant = '', icon, lg, style }) {
  return (
    <span className={'sc-chip' + (variant ? ' sc-chip--' + variant : '') + (lg ? ' sc-chip--lg' : '')} style={style}>
      {icon && <Icon name={icon} size={lg ? 16 : 14} />}
      {children}
    </span>
  );
}

/* ── amount (remaining over original) ─────────────────────── */
function Amount({ remaining, original, currency = '$', strike = true, size }) {
  const fmt = (n) => currency + Number(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (
    <div className="sc-amt">
      <div className="sc-amt__remain mono" style={size ? { fontSize: size } : undefined}>{fmt(remaining)}</div>
      {original != null && original !== remaining &&
        <div className={'sc-amt__orig mono' + (strike ? '' : ' sc-amt__orig--plain')}>{fmt(original)}</div>}
    </div>
  );
}

/* ── expense row ──────────────────────────────────────────── */
function ExpenseRow({ cat = 'food', title, sub, remaining, original, currency = '$', unread, onClick, pending, settled }) {
  const c = CAT[cat] || CAT.food;
  return (
    <div className={'sc-exp' + (onClick ? ' sc-exp--tap' : '')} onClick={onClick}>
      <div className="sc-exp__icon" style={settled ? { background: 'var(--blue-tint)', color: 'var(--blue)' } : undefined}>
        <Icon name={settled ? 'check' : c.icon} size={20} />
      </div>
      <div className="sc-exp__main">
        <div className="sc-exp__title">{title}</div>
        <div className="sc-exp__sub">{sub}</div>
        {pending && <div style={{ marginTop: 5 }}><span className="sc-pending"><Icon name="reload" size={11} /> Pending sync</span></div>}
      </div>
      <div className="sc-exp__right">
        {settled
          ? <Chip variant="blue" icon="check">Settled</Chip>
          : <Amount remaining={remaining} original={original} currency={currency} />}
        {unread && <span className="sc-dot" />}
        <Icon name="chevR" size={16} style={{ color: 'var(--ink-3)' }} />
      </div>
    </div>
  );
}

/* ── debt row ─────────────────────────────────────────────── */
function DebtRow({ from, to, amount, currency = '$', onClick, owedToYou }) {
  return (
    <div className={'sc-exp' + (onClick ? ' sc-exp--tap' : '')} onClick={onClick}>
      <div className="sc-av-stack">
        <Avatar name={from} size="sm" me={from === 'You'} />
        <Avatar name={to} size="sm" me={to === 'You'} />
      </div>
      <div className="sc-exp__main">
        <div className="sc-exp__title" style={{ fontWeight: 600 }}>
          <span>{from}</span> <span className="sc-muted" style={{ fontWeight: 500 }}>owes</span> <span>{to}</span>
        </div>
        <div className="sc-exp__sub">{owedToYou ? "You're owed this" : 'Tap to settle'}</div>
      </div>
      <div className="sc-exp__right">
        <Chip variant={owedToYou ? 'solid' : ''}><span className="mono">{currency}{Number(amount).toFixed(2)}</span></Chip>
        <Icon name="chevR" size={16} style={{ color: 'var(--ink-3)' }} />
      </div>
    </div>
  );
}

/* ── buttons ──────────────────────────────────────────────── */
function Btn({ children, variant = 'primary', icon, onClick, disabled, sm, style }) {
  return (
    <button className={'sc-btn sc-btn-' + variant + (sm ? ' sc-btn--sm' : '')} onClick={onClick} disabled={disabled} style={style}>
      {icon && <Icon name={icon} size={sm ? 16 : 20} />}{children}
    </button>
  );
}

/* ── FAB ──────────────────────────────────────────────────── */
function Fab({ label = 'Add expense', onClick, round }) {
  return (
    <button className={'sc-fab' + (round ? ' sc-fab--round' : '')} onClick={onClick}>
      <Icon name="plus" size={22} />{!round && label}
    </button>
  );
}

/* ── bottom nav ───────────────────────────────────────────── */
function BottomNav({ active = 'expenses', conflicts = 0, onNav }) {
  const items = [
    { id: 'expenses', label: 'Expenses', icon: 'receipt' },
    { id: 'balances', label: 'Balances', icon: 'swap' },
    ...(conflicts > 0 ? [{ id: 'conflicts', label: 'Conflicts', icon: 'flag', badge: conflicts }] : []),
    { id: 'overview', label: 'Overview', icon: 'chart' },
  ];
  return (
    <div className="sc-bnav">
      {items.map(it => (
        <button key={it.id} className={'sc-bnav__item' + (active === it.id ? ' sc-bnav__item--on' : '')} onClick={() => onNav && onNav(it.id)}>
          <Icon name={it.icon} size={23} stroke={active === it.id ? 1.9 : 1.6} />
          {it.label}
          {it.badge ? <span className="sc-bnav__badge">{it.badge}</span> : null}
        </button>
      ))}
    </div>
  );
}

/* ── sub-tabs ─────────────────────────────────────────────── */
function SubTabs({ tabs, active, onTab }) {
  return (
    <div className="sc-subtabs">
      {tabs.map(t => (
        <div key={t} className={'sc-subtab' + (active === t ? ' sc-subtab--on' : '')} onClick={() => onTab && onTab(t)}>{t}</div>
      ))}
    </div>
  );
}

/* ── segmented control ────────────────────────────────────── */
function Segmented({ opts, value, onChange }) {
  return (
    <div className="sc-seg">
      {opts.map(o => (
        <div key={o} className={'sc-seg__opt' + (value === o ? ' sc-seg__opt--on' : '')} onClick={() => onChange && onChange(o)}>{o}</div>
      ))}
    </div>
  );
}

/* ── toggle / radio / check ───────────────────────────────── */
function Toggle({ on, onClick }) { return <button className={'sc-toggle' + (on ? ' sc-toggle--on' : '')} onClick={onClick} />; }
function Radio({ on }) { return <span className={'sc-radio' + (on ? ' sc-radio--on' : '')} />; }
function Check({ on, onClick }) { return <button className={'sc-check' + (on ? ' sc-check--on' : '')} onClick={onClick}>{on && <Icon name="check" size={16} />}</button>; }

/* ── sheet / modal ────────────────────────────────────────── */
function Sheet({ children, title, sub, onClose, scrim = true, maxH }) {
  const body = (
    <div className="sc-sheet" style={maxH ? { maxHeight: maxH } : undefined} onClick={e => e.stopPropagation()}>
      <div className="sc-sheet__grab" />
      {title && <div className="sc-sheet__title">{title}</div>}
      {sub && <div className="sc-sheet__sub">{sub}</div>}
      {children}
    </div>
  );
  if (!scrim) return body;
  return <div className="sc-scrim" onClick={onClose}>{body}</div>;
}
function Modal({ children, onClose }) {
  return <div className="sc-scrim sc-scrim--center" onClick={onClose}><div className="sc-modal" onClick={e => e.stopPropagation()}>{children}</div></div>;
}

/* ── banner ───────────────────────────────────────────────── */
function Banner({ variant = 'offline', icon, children }) {
  return <div className={'sc-banner sc-banner--' + variant}>{icon && <Icon name={icon} size={15} />}{children}</div>;
}

/* ── empty state ──────────────────────────────────────────── */
function EmptyState({ icon = 'receipt', title, text, cta, onCta }) {
  return (
    <div className="sc-empty">
      <div className="sc-empty__icon"><Icon name={icon} size={30} /></div>
      <div className="sc-empty__title">{title}</div>
      <div className="sc-empty__text">{text}</div>
      {cta && <div style={{ marginTop: 6, width: '100%', maxWidth: 240 }}><Btn onClick={onCta}>{cta}</Btn></div>}
    </div>
  );
}

/* ── progress bar ─────────────────────────────────────────── */
function Progress({ value }) { return <div className="sc-progress"><div className="sc-progress__fill" style={{ width: Math.min(100, value) + '%' }} /></div>; }

/* ── skeleton helpers ─────────────────────────────────────── */
function Skel({ w = '100%', h = 12, r = 8, style }) { return <div className="sc-skel" style={{ width: w, height: h, borderRadius: r, ...style }} />; }
function SkelRow() {
  return (
    <div className="sc-exp">
      <Skel w={40} h={40} r={11} />
      <div className="sc-exp__main col gap8"><Skel w={'60%'} h={13} /><Skel w={'40%'} h={11} /></div>
      <div className="col gap4" style={{ alignItems: 'flex-end' }}><Skel w={54} h={15} /><Skel w={38} h={10} /></div>
    </div>
  );
}

/* ── list scroll region ───────────────────────────────────── */
function Scroll({ children, surface, style }) {
  return <div className={'sc-body' + (surface ? '' : '')} style={{ flex: 1, overflow: 'hidden', background: surface ? 'var(--surface)' : 'var(--page)', ...style }}>{children}</div>;
}

Object.assign(window, {
  CAT, StatusBar, Phone, TopBar, IconBtn, Avatar, Chip, Amount, ExpenseRow, DebtRow,
  Btn, Fab, BottomNav, SubTabs, Segmented, Toggle, Radio, Check, Sheet, Modal, Banner,
  EmptyState, Progress, Skel, SkelRow, Scroll,
});
