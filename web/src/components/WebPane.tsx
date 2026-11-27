// The Mac's own pages (notebook, gallery, ops) in a frame. Same origin, so the login cookie opens them.
// Each page has its own title and controls, so there's no bar of ours on top: only the status-bar
// space, which a framed page can't read for itself.
export function WebPane({ title, src }: { title: string; src: string }) {
  return (
    <>
      <div style={{ height: 'var(--safe-top)', background: 'var(--window)' }} />
      <iframe className="frame" src={src} title={title} />
    </>
  );
}
