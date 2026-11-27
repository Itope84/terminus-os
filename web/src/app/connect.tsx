// First run: paste the server token once. It's exchanged for a year-long HttpOnly cookie, so the page
// never keeps it.
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Screen } from '../components/Screen';
import { loadChats, useApp } from '../lib/store';

export default function Connect() {
  const router = useRouter();
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  const go = async () => {
    setBusy(true);
    setErr('');
    try {
      const r = await fetch('/terminus-login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: token.trim() }), credentials: 'same-origin' });
      if (r.status === 401) throw new Error('That token isn’t right.');
      if (!r.ok) throw new Error(`The Mac answered ${r.status}.`);
      await loadChats();
      useApp.getState().set({ auth: 'in' });
      router.replace('/');
    } catch (e) {
      setErr((e as Error).message === 'Failed to fetch' ? 'Can’t reach the Mac.' : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen>
      <div className="body">
        <div className="connect">
          <img src="/app/icon-180.png" alt="" />
          <h1>Terminus</h1>
          <p>Your agents on the Mac, from your phone. Paste the server token once; it stays signed in on this device.</p>
          <p style={{ fontSize: 13 }}>On the Mac: <code>cat ~/.config/terminus/browser/token</code></p>
          <input className="field" type="password" autoComplete="off" placeholder="Server token" value={token} onChange={(e) => setToken(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && token.trim() && void go()} />
          {err ? <p style={{ color: 'var(--err)' }}>{err}</p> : null}
          <button type="button" className="btn" disabled={!token.trim() || busy} onClick={() => void go()}>{busy ? 'Checking…' : 'Sign in'}</button>
        </div>
      </div>
    </Screen>
  );
}
