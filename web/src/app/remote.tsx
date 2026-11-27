// The Mac's browser (the agents' Chrome) on the phone: a live picture of a tab. Tap to click, drag to
// scroll, type in the box below. Used to sign in to sites for the agents; a sign-in request from an
// agent opens here (?req=…&target=…) with Done / Can't buttons that tell the agent.
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { Screen } from '../components/Screen';
import { Opt, Sheet } from '../components/Sheet';
import { call } from '../lib/api';
import { openSse } from '../lib/sse';
import { toast, useApp } from '../lib/store';

interface Tab { id: string; title: string; url: string }

export default function Remote() {
  const router = useRouter();
  const params = useLocalSearchParams<{ req?: string; target?: string }>();
  const signins = useApp((s) => s.signins);
  const req = signins.find((r) => r.id === params.req) ?? null;
  const [target, setTarget] = useState(params.target ?? '');
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [pick, setPick] = useState(false);
  const [frame, setFrame] = useState('');
  const [url, setUrl] = useState('');
  const editing = useRef(false);
  const [text, setText] = useState('');
  const [hide, setHide] = useState(true);
  const [live, setLive] = useState(false);
  const [err, setErr] = useState('');
  const img = useRef<HTMLImageElement | null>(null);
  const drag = useRef<{ y: number; x: number; moved: boolean } | null>(null);

  const loadTabs = async () => {
    try {
      const r = await call<{ tabs: Tab[] }>('GET', '/api/remote/tabs');
      setTabs(r.tabs);
      setErr('');
      return r.tabs;
    } catch (e) {
      setErr((e as Error).message);
      return [];
    }
  };

  // No tab named: use the newest one that isn't blank, or open one.
  useEffect(() => {
    if (target) return;
    void loadTabs().then(async (t) => {
      const pickOne = t.find((x) => x.url !== 'about:blank') ?? t[0];
      if (pickOne) setTarget(pickOne.id);
      else {
        const r = await call<{ tab: Tab }>('POST', '/api/remote/open', { url: 'about:blank' }).catch(() => null);
        if (r) setTarget(r.tab.id);
      }
    });
  }, [target]);

  useEffect(() => {
    if (!target) return;
    const w = Math.round(window.innerWidth);
    const h = Math.round(window.innerHeight - 250);
    const link = openSse(
      () => `/api/remote/stream?target=${encodeURIComponent(target)}&w=${w}&h=${h}`,
      {
        onOpen: () => setLive(true),
        onDown: () => setLive(false),
        onEvent: (event, data) => {
          const d = JSON.parse(data);
          if (event === 'frame') setFrame(`data:image/jpeg;base64,${d.data}`);
          else if (event === 'nav' && !editing.current) setUrl(d.url);
          else if (event === 'gone') {
            toast('That tab was closed.');
            setTarget('');
          } else if (event === 'error') setErr(d.error);
        },
      },
      { stallMs: 60_000 },
    );
    return () => link.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target]);

  const input = (body: Record<string, unknown>) =>
    call('POST', '/api/remote/input', { target, ...body }).catch((e) => toast((e as Error).message));

  const at = (clientX: number, clientY: number) => {
    const r = img.current?.getBoundingClientRect();
    if (!r || !r.width || !r.height) return null;
    return { x: (clientX - r.left) / r.width, y: (clientY - r.top) / r.height, scale: r.height };
  };

  const answer = async (state: 'done' | 'cancelled') => {
    if (!req) return;
    await call('POST', '/api/pwa/signin/answer', { id: req.id, state }).catch(() => {});
    toast(state === 'done' ? 'Told the agent you’re signed in.' : 'Told the agent you couldn’t.');
    if (router.canGoBack()) router.back();
    else router.replace('/');
  };

  const sendText = () => {
    if (!text) return;
    void input({ kind: 'text', text });
    setText('');
  };

  return (
    <Screen>
      <div className="bar">
        <button type="button" className="icon" aria-label="Back" onClick={() => (router.canGoBack() ? router.back() : router.replace('/'))}>‹</button>
        <h1 style={{ fontSize: 17 }}>Mac browser</h1>
        <span className={`dot ${live ? 'ok' : 'bad'}`} />
        <button type="button" className="icon" aria-label="Tabs" onClick={() => { void loadTabs(); setPick(true); }}>❐</button>
      </div>
      {req ? (
        <div className="banner">
          <span style={{ flex: 1 }}>Sign in to <b>{req.site}</b>{req.reason ? `: ${req.reason}` : ''}</span>
          <button type="button" onClick={() => void answer('cancelled')}>Can’t</button>
          <button type="button" onClick={() => void answer('done')}>Done</button>
        </div>
      ) : null}
      <div className="remote-url">
        <button type="button" onClick={() => void input({ kind: 'back' })} aria-label="Back a page">←</button>
        <input
          value={url}
          inputMode="url"
          autoCapitalize="off"
          autoCorrect="off"
          onFocus={() => { editing.current = true; }}
          onBlur={() => { editing.current = false; }}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return;
            const u = /^https?:\/\//i.test(url) ? url : `https://${url}`;
            void input({ kind: 'go', url: u });
            (e.target as HTMLInputElement).blur();
          }}
        />
        <button type="button" onClick={() => void input({ kind: 'reload' })} aria-label="Reload">↻</button>
      </div>
      <div className="remote-view">
        {err && !frame ? <div className="empty">{err}</div> : null}
        {frame ? (
          <img
            ref={img}
            src={frame}
            alt=""
            draggable={false}
            onTouchStart={(e) => {
              const t = e.touches[0];
              drag.current = { x: t.clientX, y: t.clientY, moved: false };
            }}
            onTouchMove={(e) => {
              const d = drag.current;
              const t = e.touches[0];
              if (!d) return;
              const dy = d.y - t.clientY;
              if (Math.abs(dy) > 12) {
                d.moved = true;
                const p = at(t.clientX, t.clientY);
                // Picture pixels → page pixels.
                const page = p && img.current ? (dy * (img.current.naturalHeight / 2)) / p.scale : dy;
                void input({ kind: 'scroll', dy: Math.round(page), x: p?.x ?? 0.5, y: p?.y ?? 0.5 });
                d.y = t.clientY;
              }
            }}
            onClick={(e) => {
              if (drag.current?.moved) {
                drag.current = null;
                return;
              }
              const p = at(e.clientX, e.clientY);
              if (p) void input({ kind: 'tap', x: p.x, y: p.y });
            }}
          />
        ) : !err ? (
          <div className="empty">Connecting to the Mac’s browser…</div>
        ) : null}
      </div>
      <div className="remote-type">
        <input
          type={hide ? 'password' : 'text'}
          autoComplete="off"
          autoCapitalize="off"
          autoCorrect="off"
          placeholder="Tap a field above, then type here"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              if (text) sendText();
              else void input({ kind: 'key', key: 'Enter' });
            }
          }}
        />
        <button type="button" onClick={() => setHide(!hide)} aria-label={hide ? 'Show text' : 'Hide text'}>{hide ? '👁' : '•••'}</button>
        <button type="button" onClick={sendText} disabled={!text}>Type</button>
      </div>
      <div className="remote-keys">
        <button type="button" onClick={() => void input({ kind: 'key', key: 'Backspace' })}>⌫</button>
        <button type="button" onClick={() => void input({ kind: 'key', key: 'Tab' })}>Tab</button>
        <button type="button" onClick={() => void input({ kind: 'key', key: 'Enter' })}>Enter ⏎</button>
        <button type="button" onClick={() => void input({ kind: 'scroll', dy: -500 })}>▲</button>
        <button type="button" onClick={() => void input({ kind: 'scroll', dy: 500 })}>▼</button>
      </div>

      <Sheet open={pick} onClose={() => setPick(false)}>
        <h2>Tabs on the Mac</h2>
        {tabs.map((t) => (
          <Opt key={t.id} title={t.title || t.url} sub={t.url} right={t.id === target ? '●' : undefined} onClick={() => { setFrame(''); setTarget(t.id); setPick(false); }} />
        ))}
        <Opt title="New tab" onClick={async () => {
          const r = await call<{ tab: Tab }>('POST', '/api/remote/open', { url: 'about:blank' }).catch(() => null);
          setPick(false);
          if (r) { setFrame(''); setTarget(r.tab.id); setUrl(''); }
        }} />
      </Sheet>
    </Screen>
  );
}
