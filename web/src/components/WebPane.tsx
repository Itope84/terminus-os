// The Mac's own pages (notebook, gallery, ops) in a frame. Same origin, so the login cookie opens them.
import { useState } from 'react';

export function WebPane({ title, src }: { title: string; src: string }) {
  const [key, setKey] = useState(0);
  return (
    <>
      <div className="bar">
        <h1>{title}</h1>
        <button type="button" className="icon" aria-label="Reload" onClick={() => setKey((k) => k + 1)}>↻</button>
        <button type="button" className="icon" aria-label="Open in Safari" onClick={() => window.open(src, '_blank')}>↗</button>
      </div>
      <iframe key={key} className="frame" src={src} title={title} />
    </>
  );
}
