// Root: sign-in gate, the service worker, the app-wide live link, approvals (they can arrive on any
// screen) and the toast line.
import { Stack, usePathname, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { useCssVars } from '../components/Screen';
import { Sheet } from '../components/Sheet';
import '../components/ui.css';
import { call, onSignedOut } from '../lib/api';
import { kickEvents, startEvents, stopEvents } from '../lib/events';
import { loadBackends, loadChats, useApp } from '../lib/store';
import { flushOutbox } from '../lib/thread';
import type { Approval } from '../lib/types';

if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/app/sw.js', { scope: '/app/' }).catch(() => {});
}

function ApprovalSheet() {
  const approvals = useApp((s) => s.approvals);
  const a: Approval | undefined = approvals[0];
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!a) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [a]);
  const decide = async (decision: 'allow' | 'deny') => {
    if (!a) return;
    setBusy(true);
    try {
      await call('POST', '/api/approvals/decide', { id: a.id, decision });
    } catch {
      /* already answered or timed out */
    }
    useApp.getState().set({ approvals: useApp.getState().approvals.filter((x) => x.id !== a.id) });
    setBusy(false);
  };
  const left = a ? Math.max(0, Math.round((a.expiresAt - now) / 1000)) : 0;
  return (
    <Sheet open={!!a} onClose={() => {}}>
      {a ? (
        <>
          <h2>{a.source} asks</h2>
          <p style={{ color: 'var(--ink)', fontSize: 16 }}>{a.title}</p>
          {a.detail ? <p style={{ whiteSpace: 'pre-wrap' }}>{a.detail}</p> : null}
          <p>{left > 0 ? `Answer within ${left >= 60 ? `${Math.ceil(left / 60)} min` : `${left}s`}; no answer means no.` : 'Timed out.'}{approvals.length > 1 ? ` · ${approvals.length - 1} more waiting` : ''}</p>
          <div className="btnrow">
            <button type="button" className="btn danger" disabled={busy} onClick={() => void decide('deny')}>Deny</button>
            <button type="button" className="btn" disabled={busy} onClick={() => void decide('allow')}>Allow</button>
          </div>
        </>
      ) : null}
    </Sheet>
  );
}

function Toast() {
  const t = useApp((s) => s.toast);
  const [shown, setShown] = useState<string | null>(null);
  useEffect(() => {
    if (!t) return;
    setShown(t.text);
    const h = setTimeout(() => setShown(null), Math.min(7000, 2500 + t.text.length * 40));
    return () => clearTimeout(h);
  }, [t]);
  return shown ? <div className="toast" onClick={() => setShown(null)}>{shown}</div> : null;
}

export default function Root() {
  const auth = useApp((s) => s.auth);
  const router = useRouter();
  const path = usePathname();
  const vars = useCssVars();

  // Am I signed in? (The cookie is HttpOnly, so ask the server.)
  useEffect(() => {
    const off = onSignedOut(() => {
      useApp.getState().set({ auth: 'out' });
      stopEvents();
    });
    loadChats()
      .then(() => useApp.getState().set({ auth: 'in' }))
      .catch((e) => {
        // Offline: trust the cached list and carry on; a 401 says signed out.
        if ((e as { status?: number }).status === 401) useApp.getState().set({ auth: 'out' });
        else useApp.getState().set({ auth: Object.keys(useApp.getState().chats).length ? 'in' : 'out' });
      });
    return () => {
      off();
    };
  }, []);

  useEffect(() => {
    if (auth === 'in') {
      startEvents();
      void loadBackends();
      void flushOutbox();
    }
    if (auth === 'out' && path !== '/connect') router.replace('/connect');
  }, [auth, path, router]);

  // Notification taps while the app is open: the service worker posts the chat to open.
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return;
    const onMsg = (e: MessageEvent) => {
      if (e.data?.type === 'open' && typeof e.data.url === 'string') {
        const to = e.data.url.replace(/^\/app/, '') || '/';
        router.push(to as never);
      }
    };
    navigator.serviceWorker.addEventListener('message', onMsg);
    const onOnline = () => {
      kickEvents();
      void flushOutbox();
    };
    window.addEventListener('online', onOnline);
    return () => {
      navigator.serviceWorker.removeEventListener('message', onMsg);
      window.removeEventListener('online', onOnline);
    };
  }, [router]);

  return (
    <div style={{ ...vars, position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', background: 'var(--window)' }}>
      <Stack screenOptions={{ headerShown: false, animation: 'slide_from_right', contentStyle: { backgroundColor: 'transparent' } }} />
      <ApprovalSheet />
      <Toast />
    </div>
  );
}
