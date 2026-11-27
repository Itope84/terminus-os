// Chats: needs-you first, then pinned, then by day. Filters: Recent, Unread, Schedules, Archived.
import { useRouter } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Opt, Sheet } from '../../components/Sheet';
import { call } from '../../lib/api';
import { newChatId } from '../../lib/ids';
import { isStandalone, pushState } from '../../lib/push';
import { defaultBackend, deleteChat, updateChat, useApp } from '../../lib/store';
import { forgetThread, outboxCount } from '../../lib/thread';
import type { Chat } from '../../lib/types';
import { SUPPORTED_BACKENDS } from '../../lib/types';

type Filter = 'recent' | 'unread' | 'schedules' | 'archived';
const TILE = ['#D9CFAE', '#C4D4C5', '#C9D6DE', '#DCC3C3', '#D3C8E2', '#CBD8B8', '#E0CFA8', '#BFD0D4'];
const TILE_DARK = ['#5A4E33', '#3D5647', '#3A4E5E', '#5E4038', '#4A3F5E', '#44584A', '#5C4E2E', '#37505A'];

const when = (at: number) => {
  const d = new Date(at);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (now.getTime() - at < 6 * 86_400_000) return d.toLocaleDateString([], { weekday: 'short' });
  return d.toLocaleDateString([], { day: 'numeric', month: 'short' });
};

function bucket(at: number): string {
  const d = new Date(at);
  const now = new Date();
  const days = Math.floor((new Date(now.toDateString()).getTime() - new Date(d.toDateString()).getTime()) / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  if (days < 7) return 'This week';
  if (days < 31) return 'This month';
  return 'Older';
}

interface ScheduleRow { chat: string; name: string; cron?: string; everyMin?: number; at?: string; next?: number; retired: boolean }

/** "next Sun 18:30 · every 60 min" (the Android app's third line). */
function scheduleLine(r: ScheduleRow): string {
  if (r.retired) return 'finished';
  const parts: string[] = [];
  if (r.next) {
    const d = new Date(r.next);
    const sameDay = d.toDateString() === new Date().toDateString();
    parts.push(`next ${sameDay ? '' : `${d.toLocaleDateString([], { weekday: 'short' })} `}${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`);
  }
  if (r.everyMin) parts.push(r.everyMin % 1440 === 0 ? `every ${r.everyMin / 1440 === 1 ? 'day' : `${r.everyMin / 1440} days`}` : r.everyMin % 60 === 0 ? `every ${r.everyMin / 60 === 1 ? 'hour' : `${r.everyMin / 60} h`}` : `every ${r.everyMin} min`);
  else if (r.cron) parts.push('repeats');
  else if (r.at) parts.push('once');
  return parts.join(' · ');
}

const hash = (s: string) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);

function Row({ c, onOpen, onLong, dark, third }: { c: Chat; onOpen: () => void; onLong: () => void; dark: boolean; third?: string }) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const initials = c.kind === 'inbox' ? (c.id.startsWith('schedule:') ? '⏱' : '🔔') : (c.title.match(/\p{L}|\p{N}/u)?.[0] ?? '·').toUpperCase();
  const tiles = dark ? TILE_DARK : TILE;
  return (
    <div
      className="chatrow"
      onClick={onOpen}
      onContextMenu={(e) => {
        e.preventDefault();
        onLong();
      }}
      onTouchStart={() => {
        timer = setTimeout(onLong, 550);
      }}
      onTouchEnd={() => timer && clearTimeout(timer)}
      onTouchMove={() => timer && clearTimeout(timer)}
    >
      <div className="avatar" style={{ background: tiles[hash(c.id) % tiles.length] }}>{initials}</div>
      <div className="mid">
        <div className="top">
          <b>{c.pinned ? '📌 ' : ''}{c.title}</b>
          <time>{when(c.updatedAt)}</time>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <div className="pre" style={{ flex: 1 }}>{c.preview || (c.kind === 'inbox' ? 'Deliveries' : 'No messages yet')}</div>
          {c.needsYou ? <span className="needs">needs you</span> : c.unread ? <span className="badge">{c.unread}</span> : null}
        </div>
        {third ? <div className="third">{third}</div> : null}
      </div>
    </div>
  );
}

export default function Chats() {
  const router = useRouter();
  const chats = useApp((s) => s.chats);
  const online = useApp((s) => s.online);
  const backends = useApp((s) => s.backends);
  const signins = useApp((s) => s.signins);
  const [filter, setFilter] = useState<Filter>('recent');
  const [q, setQ] = useState('');
  const [menu, setMenu] = useState<Chat | null>(null);
  const [pick, setPick] = useState(false);
  const [push, setPush] = useState<string>('on');
  const [rows, setRows] = useState<Record<string, string>>({});
  const dark = typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches;

  useEffect(() => {
    void pushState().then(setPush).catch(() => {});
    call<{ rows: ScheduleRow[] }>('GET', '/api/schedule-rows')
      .then((r) => {
        const out: Record<string, string> = {};
        for (const row of r.rows ?? []) out[row.chat] = scheduleLine(row);
        setRows(out);
      })
      .catch(() => {});
  }, []);

  const list = useMemo(() => {
    const all = Object.values(chats);
    const needle = q.trim().toLowerCase();
    let shown = all.filter((c) => {
      if (needle) return `${c.title} ${c.preview ?? ''}`.toLowerCase().includes(needle);
      if (filter === 'archived') return !!c.archivedAt;
      if (c.archivedAt) return false;
      if (filter === 'unread') return !!c.unread || !!c.needsYou;
      if (filter === 'schedules') return c.id.startsWith('schedule:');
      return true;
    });
    shown = shown.sort((a, b) => b.updatedAt - a.updatedAt);
    const groups: { name: string; items: Chat[] }[] = [];
    const add = (name: string, c: Chat) => {
      let g = groups.find((x) => x.name === name);
      if (!g) groups.push((g = { name, items: [] }));
      g.items.push(c);
    };
    for (const c of shown) add(c.needsYou ? 'Needs you' : c.pinned && filter === 'recent' && !needle ? 'Pinned' : bucket(c.updatedAt), c);
    const order = ['Needs you', 'Pinned', 'Today', 'Yesterday', 'This week', 'This month', 'Older'];
    return groups.sort((a, b) => order.indexOf(a.name) - order.indexOf(b.name));
  }, [chats, filter, q]);

  const start = async (backend: string) => {
    setPick(false);
    const id = newChatId();
    const model = useApp.getState().defaults?.defaultModels?.[backend] || undefined;
    await updateChat(id, { title: 'New chat', backend, model, kind: 'chat', touch: true });
    router.push(`/chat/${id}`);
  };

  const waiting = outboxCount();

  return (
    <>
      <div className="bar">
        <h1>Terminus</h1>
        <span className={`dot ${online ? 'ok' : 'bad'}`} title={online ? 'Connected to the Mac' : 'Can’t reach the Mac'} />
        <button type="button" className="icon" aria-label="Mac browser" onClick={() => router.push('/remote')}>🌐</button>
        <button type="button" className="icon" aria-label="Settings" onClick={() => router.push('/settings')}>⚙︎</button>
      </div>
      {!online ? <div className="banner warn">Can’t reach the Mac. Is Tailscale on? Messages you send will wait.</div> : null}
      {signins.map((r) => (
        <div key={r.id} className="banner warn">
          <span style={{ flex: 1 }}>An agent needs you to sign in to <b>{r.site}</b></span>
          <button type="button" onClick={() => router.push(`/remote?req=${encodeURIComponent(r.id)}${r.target ? `&target=${encodeURIComponent(r.target)}` : ''}` as never)}>Sign in</button>
        </div>
      ))}
      {waiting ? <div className="banner">{waiting} message{waiting === 1 ? '' : 's'} waiting to send</div> : null}
      {push !== 'on' && push !== 'unsupported' ? (
        <div className="banner">
          <span style={{ flex: 1 }}>{isStandalone() ? 'Turn on notifications to hear when replies land.' : 'Add Terminus to the Home Screen for notifications.'}</span>
          <button type="button" onClick={() => router.push('/settings')}>Set up</button>
        </div>
      ) : null}
      <div className="body">
        <div className="search">
          <input type="search" placeholder="Search chats" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="filters">
          {(['recent', 'unread', 'schedules', 'archived'] as Filter[]).map((f) => (
            <button key={f} type="button" className={`chip${filter === f ? ' on' : ''}`} onClick={() => setFilter(f)}>
              {f[0].toUpperCase() + f.slice(1)}
            </button>
          ))}
        </div>
        {list.length === 0 ? <div className="empty" style={{ marginTop: '20vh' }}>{q ? 'Nothing matches.' : filter === 'recent' ? 'No chats yet. Tap + to start one.' : 'Nothing here.'}</div> : null}
        {list.map((g) => (
          <div key={g.name}>
            <div className="section">{g.name}</div>
            {g.items.map((c) => (
              <Row key={c.id} c={c} dark={!!dark} third={rows[c.id]} onOpen={() => router.push(`/chat/${encodeURIComponent(c.id)}`)} onLong={() => setMenu(c)} />
            ))}
          </div>
        ))}
        <div style={{ height: 90 }} />
      </div>
      <button type="button" className="fab" aria-label="New chat" onClick={() => setPick(true)}>＋</button>

      <Sheet open={pick} onClose={() => setPick(false)}>
        <h2>New chat with</h2>
        {SUPPORTED_BACKENDS.map((id) => {
          const b = backends.find((x) => x.id === id);
          const ready = !b || (b.available && b.authenticated);
          return (
            <Opt
              key={id}
              title={b?.label ?? (id === 'claude' ? 'Claude' : 'OpenCode')}
              sub={ready ? (id === defaultBackend() ? 'Default' : undefined) : 'Not ready on the Mac'}
              disabled={!ready}
              onClick={() => void start(id)}
            />
          );
        })}
      </Sheet>

      <Sheet open={!!menu} onClose={() => setMenu(null)}>
        {menu ? (
          <>
            <h2>{menu.title}</h2>
            <Opt title={menu.pinned ? 'Unpin' : 'Pin'} onClick={() => { void updateChat(menu.id, { pinned: !menu.pinned }); setMenu(null); }} />
            <Opt title={menu.archivedAt ? 'Unarchive' : 'Archive'} onClick={() => { void updateChat(menu.id, { archivedAt: (menu.archivedAt ? null : Date.now()) as unknown as number }); setMenu(null); }} />
            {menu.unread || menu.needsYou ? <Opt title="Mark as read" onClick={() => { void call('POST', '/api/pwa/chats/read', { id: menu.id }); setMenu(null); }} /> : null}
            <Opt title="Delete" danger onClick={() => { void deleteChat(menu); forgetThread(menu.id); setMenu(null); }} />
          </>
        ) : null}
      </Sheet>
    </>
  );
}
