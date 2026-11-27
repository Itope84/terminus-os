// Five tabs, like the Android app: Chats, Echo, Notes, Files, Ops.
import { Slot, usePathname, useRouter } from 'expo-router';
import { Screen } from '../../components/Screen';
import { useApp } from '../../lib/store';

const TABS = [
  { path: '/', label: 'Chats', icon: '💬' },
  { path: '/echo', label: 'Echo', icon: '🎙' },
  { path: '/notes', label: 'Notes', icon: '📓' },
  { path: '/files', label: 'Files', icon: '🗂' },
  { path: '/ops', label: 'Ops', icon: '📈' },
];

export default function TabsLayout() {
  const path = usePathname();
  const router = useRouter();
  const unread = useApp((s) => Object.values(s.chats).reduce((n, c) => n + (c.archivedAt ? 0 : c.unread ?? 0), 0));
  return (
    <Screen>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, position: 'relative' }}>
        <Slot />
      </div>
      <nav className="tabs">
        {TABS.map((t) => (
          <button key={t.path} type="button" className={path === t.path ? 'on' : ''} onClick={() => router.replace(t.path as never)}>
            <span className="i">{t.icon}</span>
            {t.label}
            {t.path === '/' && unread ? <span className="badge" style={{ position: 'absolute', marginLeft: 26, marginTop: -2 }}>{unread}</span> : null}
          </button>
        ))}
      </nav>
    </Screen>
  );
}
