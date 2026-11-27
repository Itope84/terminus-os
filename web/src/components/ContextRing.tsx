// How full the context window is: a thin ring, with a tick at the auto-compact point.
export function ContextRing({ used, window: win, compactAt, onClick }: { used: number; window: number | null; compactAt: number; onClick: () => void }) {
  const frac = win ? Math.min(1, used / win) : 0;
  const r = 11;
  const c = 2 * Math.PI * r;
  const color = frac > 0.85 ? 'var(--err)' : frac > 0.6 ? 'var(--warn)' : 'var(--ok)';
  const tick = win && compactAt ? (compactAt / win) * 360 - 90 : null;
  return (
    <button type="button" className="icon" aria-label="Context" onClick={onClick}>
      <svg className="ring" viewBox="0 0 28 28">
        <circle cx="14" cy="14" r={r} fill="none" stroke="var(--line)" strokeWidth="3" />
        {win ? <circle cx="14" cy="14" r={r} fill="none" stroke={color} strokeWidth="3" strokeDasharray={`${frac * c} ${c}`} transform="rotate(-90 14 14)" strokeLinecap="round" /> : null}
        {tick !== null ? <line x1="14" y1="1" x2="14" y2="5" stroke="var(--muted)" strokeWidth="1.5" transform={`rotate(${tick + 90} 14 14)`} /> : null}
      </svg>
    </button>
  );
}
