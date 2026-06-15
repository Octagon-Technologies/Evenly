// icons.jsx — thin single-weight line icons, monochrome (inherit currentColor)
// <Icon name="plus" size={20} />
const ICON_PATHS = {
  plus:        '<path d="M12 5v14M5 12h14"/>',
  minus:       '<path d="M5 12h14"/>',
  search:      '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4-4"/>',
  filter:      '<path d="M3 5h18M6 12h12M10 19h4"/>',
  sort:        '<path d="M7 4v16M7 20l-3-3M7 4l3 3M17 20V4M17 4l3 3M17 20l-3-3"/>',
  chevR:       '<path d="M9 6l6 6-6 6"/>',
  chevL:       '<path d="M15 6l-6 6 6 6"/>',
  chevD:       '<path d="M6 9l6 6 6-6"/>',
  chevU:       '<path d="M6 15l6-6 6 6"/>',
  back:        '<path d="M19 12H5M5 12l6-6M5 12l6 6"/>',
  close:       '<path d="M6 6l12 12M18 6L6 18"/>',
  more:        '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
  check:       '<path d="M5 12l5 5L20 6"/>',
  checkCircle: '<circle cx="12" cy="12" r="9"/><path d="M8 12l3 3 5-6"/>',
  user:        '<circle cx="12" cy="8" r="3.5"/><path d="M5 20c0-3.9 3.1-6 7-6s7 2.1 7 6"/>',
  users:       '<circle cx="9" cy="8" r="3"/><path d="M3 19c0-3.3 2.7-5 6-5s6 1.7 6 5"/><path d="M16 5.5a3 3 0 010 5.8M21 19c0-2.6-1.4-4.2-3.5-4.8"/>',
  receipt:     '<path d="M6 3h12v18l-2.5-1.5L13 21l-2.5-1.5L8 21l-2-1.5V3z"/><path d="M9 8h6M9 12h6"/>',
  calendar:    '<rect x="4" y="5" width="16" height="16" rx="2.5"/><path d="M4 9h16M8 3v4M16 3v4"/>',
  tag:         '<path d="M3 12l8-8 9 1 1 9-8 8-10-10z"/><circle cx="15.5" cy="8.5" r="1.4"/>',
  food:        '<path d="M6 3v8a2 2 0 002 2v8M6 3v5M9 3v5M8 8h1M16 3c-1.5 0-2.5 2-2.5 5s1 3 2.5 3v9"/>',
  car:         '<path d="M5 13l1.5-4.5A2 2 0 018.4 7h7.2a2 2 0 011.9 1.5L19 13M4 13h16v4H4zM7 17v2M17 17v2"/><circle cx="7.5" cy="14.5" r=".6"/><circle cx="16.5" cy="14.5" r=".6"/>',
  home:        '<path d="M4 11l8-7 8 7M6 9.5V20h12V9.5"/>',
  cart:        '<circle cx="9" cy="20" r="1.3"/><circle cx="17" cy="20" r="1.3"/><path d="M3 4h2l2.2 11h10l1.8-8H6"/>',
  ticket:      '<path d="M4 8a2 2 0 012-2h12a2 2 0 012 2 2 2 0 000 4 2 2 0 000 4 2 2 0 01-2 2H6a2 2 0 01-2-2 2 2 0 000-4 2 2 0 000-4z"/><path d="M14 6v12" stroke-dasharray="2 2"/>',
  bolt:        '<path d="M13 3L5 13h6l-1 8 8-10h-6l1-8z"/>',
  bed:         '<path d="M3 18v-7a2 2 0 012-2h14a2 2 0 012 2v7M3 14h18M7 9V7a1 1 0 011-1h3v3"/>',
  gift:        '<rect x="4" y="9" width="16" height="11" rx="1.5"/><path d="M4 13h16M12 9v11M12 9c-2-4-6-3-6-1s4 1 6 1c2 0 6 1 6-1s-4-3-6 1z"/>',
  gear:        '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M5 5l2 2M17 17l2 2M2 12h3M19 12h3M5 19l2-2M17 7l2-2"/>',
  bell:        '<path d="M6 9a6 6 0 0112 0c0 5 2 6 2 6H4s2-1 2-6zM10 20a2 2 0 004 0"/>',
  lock:        '<rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 018 0v3"/>',
  share:       '<circle cx="6" cy="12" r="2.5"/><circle cx="17" cy="6" r="2.5"/><circle cx="17" cy="18" r="2.5"/><path d="M8.2 10.8l6.6-3.6M8.2 13.2l6.6 3.6"/>',
  qr:          '<rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><path d="M14 14h3v3M20 14v6M17 20h3"/>',
  link:        '<path d="M9 15l6-6M10.5 6.5l1.8-1.8a4 4 0 015.6 5.6L15.5 12M13.5 17.5l-1.8 1.8a4 4 0 01-5.6-5.6L8.5 12"/>',
  download:    '<path d="M12 4v11M12 15l-4-4M12 15l4-4M5 19h14"/>',
  upload:      '<path d="M12 20V9M12 9L8 13M12 9l4 4M5 5h14"/>',
  archive:     '<rect x="4" y="5" width="16" height="4" rx="1"/><path d="M5 9v9a2 2 0 002 2h10a2 2 0 002-2V9M10 13h4"/>',
  trash:       '<path d="M5 7h14M9 7V5a1 1 0 011-1h4a1 1 0 011 1v2M7 7l1 13h8l1-13"/>',
  edit:        '<path d="M4 20h4L19 9l-4-4L4 16v4z"/><path d="M14 6l4 4"/>',
  refund:      '<path d="M4 9h11a5 5 0 010 10h-3M4 9l4-4M4 9l4 4"/>',
  copy:        '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 012-2h8"/>',
  camera:      '<rect x="3" y="7" width="18" height="13" rx="2.5"/><circle cx="12" cy="13.5" r="3.5"/><path d="M8 7l1.5-3h5L16 7"/>',
  image:       '<rect x="3" y="5" width="18" height="14" rx="2.5"/><circle cx="8.5" cy="10" r="1.8"/><path d="M21 16l-5-5L5 19"/>',
  comment:     '<path d="M4 6a2 2 0 012-2h12a2 2 0 012 2v8a2 2 0 01-2 2H9l-5 4V6z"/>',
  clock:       '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  history:     '<path d="M4 12a8 8 0 108-8 8 8 0 00-7 4M4 4v4h4"/><path d="M12 8v4l3 2"/>',
  wifiOff:     '<path d="M3 3l18 18M8.5 13.5a6 6 0 017 0M5 10a11 11 0 0114 0M12 18h.01"/>',
  alert:       '<path d="M12 3l9 16H3l9-16zM12 10v4M12 17h.01"/>',
  info:        '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  wallet:      '<rect x="3" y="6" width="18" height="13" rx="2.5"/><path d="M3 10h18M16 14h2"/>',
  percent:     '<circle cx="7.5" cy="7.5" r="2.5"/><circle cx="16.5" cy="16.5" r="2.5"/><path d="M6 18L18 6"/>',
  calculator:  '<rect x="5" y="3" width="14" height="18" rx="2.5"/><path d="M8 7h8M8 11h.01M12 11h.01M16 11h.01M8 15h.01M12 15h.01M16 15v3M8 18h4"/>',
  sparkle:     '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3z"/>',
  swap:        '<path d="M7 7h11M18 7l-3-3M18 7l-3 3M17 17H6M6 17l3-3M6 17l3 3"/>',
  split:       '<path d="M12 4v6M12 10l-5 5M12 10l5 5M7 15v5M17 15v5"/>',
  globe:       '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.5 2.4 2.5 14.6 0 17M12 3.5c-2.5 2.4-2.5 14.6 0 17"/>',
  chart:       '<path d="M4 20V4M4 20h16M8 16v-4M12 16V8M16 16v-7"/>',
  flag:        '<path d="M6 21V4M6 4h11l-2 4 2 4H6"/>',
  mail:        '<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M4 7l8 6 8-6"/>',
  apple:       '<path d="M16 12c0-2 1.5-3 1.6-3.1A3.7 3.7 0 0014.5 7c-1.3 0-2 .7-2.5.7S10.7 7 9.5 7C7.4 7 6 8.8 6 11.4c0 3 2.2 6.6 3.8 6.6.8 0 1.3-.6 2.2-.6s1.3.6 2.2.6c1.3 0 2.8-2.4 3.3-3.6-1.4-.6-1.5-2.2-1.5-2.4zM13 5.5c.6-.8.5-1.9.5-2-.9 0-1.7.6-2 1-.5.5-.6 1.4-.5 1.9.9.1 1.6-.4 2-.9z"/>',
  google:      '<path d="M21 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.1a4.4 4.4 0 01-1.9 2.9v2.4h3.1c1.8-1.7 2.7-4.2 2.7-7.1z" fill="currentColor" stroke="none"/><path d="M12 21c2.4 0 4.5-.8 6-2.2l-3.1-2.4c-.8.6-1.9 1-2.9 1a5 5 0 01-4.8-3.5H4v2.4A9 9 0 0012 21z" fill="currentColor" stroke="none"/><path d="M7.2 13.9a5.4 5.4 0 010-3.4V8.1H4a9 9 0 000 8.1l3.2-2.3z" fill="currentColor" stroke="none"/><path d="M12 6.5c1.3 0 2.5.5 3.5 1.4l2.6-2.6A9 9 0 004 8.1l3.2 2.4A5 5 0 0112 6.5z" fill="currentColor" stroke="none"/>',
  facebook:    '<path d="M14 8h2V5h-2a3 3 0 00-3 3v2H9v3h2v6h3v-6h2.2l.8-3H14V8.5c0-.3.2-.5.5-.5z" fill="currentColor" stroke="none"/>',
  reload:      '<path d="M20 12a8 8 0 11-2.3-5.6M20 4v4h-4"/>',
  pin:         '<path d="M12 21s7-6 7-11a7 7 0 10-14 0c0 5 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/>',
  star:        '<path d="M12 3l2.6 5.6 6 .8-4.4 4.2 1.1 6L12 17l-5.3 2.6 1.1-6L3.4 9.4l6-.8L12 3z"/>',
  eyeOff:      '<path d="M3 3l18 18M10.6 10.6a2 2 0 002.8 2.8M6.7 6.8A10.5 10.5 0 002 12s3.5 6 10 6a9.8 9.8 0 004.6-1.1M9.5 5.2A10.6 10.6 0 0112 5c6.5 0 10 7 10 7a18 18 0 01-2.2 3"/>',
  send:        '<path d="M4 12l16-7-7 16-2.5-6.5L4 12z"/>',
};

function Icon({ name, size = 22, stroke = 1.6, style = {}, className = '' }) {
  const p = ICON_PATHS[name];
  return (
    <svg
      className={className}
      width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round"
      style={{ flexShrink: 0, ...style }}
      dangerouslySetInnerHTML={{ __html: p }}
    />
  );
}

window.Icon = Icon;
window.ICON_PATHS = ICON_PATHS;
