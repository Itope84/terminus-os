// Settings: notifications on this device, the default agent and model for new chats, sign out.
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { Screen } from '../components/Screen';
import { Opt, Sheet } from '../components/Sheet';
import { call } from '../lib/api';
import { isStandalone, pushState, turnOffNotifications, turnOnNotifications } from '../lib/push';
import { loadBackends, toast, useApp } from '../lib/store';
import type { Model } from '../lib/types';
import { SUPPORTED_BACKENDS } from '../lib/types';

const BUILD = process.env.EXPO_PUBLIC_BUILD ?? 'dev';

export default function Settings() {
  const router = useRouter();
  const backends = useApp((s) => s.backends);
  const defaults = useApp((s) => s.defaults);
  const [push, setPush] = useState<string>('…');
  const [devices, setDevices] = useState<number | null>(null);
  const [pickBackend, setPickBackend] = useState(false);
  const [pickModel, setPickModel] = useState<string | null>(null);
  const [models, setModels] = useState<Model[]>([]);

  const refresh = () => {
    void pushState().then(setPush).catch(() => setPush('unsupported'));
    call<{ devices: number }>('GET', '/api/pwa/push')
      .then((r) => setDevices(r.devices))
      .catch(() => {});
  };
  useEffect(refresh, []);

  useEffect(() => {
    if (!pickModel) return;
    setModels([]);
    call<{ models: Model[] }>('GET', `/api/models?backend=${pickModel}`)
      .then((r) => setModels(r.models ?? []))
      .catch(() => toast('Couldn’t list models.'));
  }, [pickModel]);

  const saveDefaults = async (patch: Record<string, unknown>) => {
    try {
      await call('POST', '/api/defaults', patch);
      await loadBackends();
    } catch (e) {
      toast(`Not saved: ${(e as Error).message}`);
    }
  };

  const label = (id: string) => backends.find((b) => b.id === id)?.label ?? (id === 'claude' ? 'Claude' : 'OpenCode');
  const def = defaults?.defaultBackend ?? 'claude';

  const signOut = async () => {
    await fetch('/terminus-logout', { method: 'POST', credentials: 'same-origin' }).catch(() => {});
    localStorage.clear();
    useApp.getState().set({ auth: 'out', chats: {} });
    router.replace('/connect');
  };

  return (
    <Screen>
      <div className="bar">
        <button type="button" className="icon" aria-label="Back" onClick={() => (router.canGoBack() ? router.back() : router.replace('/'))}>‹</button>
        <h1>Settings</h1>
      </div>
      <div className="body">
        <div className="section">Notifications</div>
        {push === 'unsupported' ? (
          <p className="hint">{isStandalone() ? 'This browser can’t do notifications.' : 'On iPhone: Share → Add to Home Screen, open Terminus from there, then turn notifications on here.'}</p>
        ) : push === 'blocked' ? (
          <p className="hint">Notifications are blocked. Turn them on in the iPhone’s Settings → Notifications → Terminus.</p>
        ) : (
          <Opt
            title={push === 'on' ? 'Notifications are on' : 'Turn on notifications'}
            sub={push === 'on' ? 'When a reply lands or an agent asks you something, while the app is closed.' : 'Hear when replies land with the app closed.'}
            right={push === 'on' ? 'Turn off' : undefined}
            onClick={() => void (push === 'on' ? turnOffNotifications() : turnOnNotifications()).then(refresh)}
          />
        )}
        {push === 'on' ? (
          <Opt
            title="Send a test"
            sub={devices != null ? `${devices} device${devices === 1 ? '' : 's'} signed up` : undefined}
            onClick={() =>
              void call<{ sent: number; errors: string[] }>('POST', '/api/pwa/push/test')
                .then((r) => toast(r.errors.length ? r.errors[0] : `Sent to ${r.sent}.`))
                .catch((e) => toast((e as Error).message))
            }
          />
        ) : null}

        <div className="section">New chats</div>
        <Opt title="Agent" sub={label(def)} onClick={() => setPickBackend(true)} />
        {SUPPORTED_BACKENDS.map((id) => (
          <Opt key={id} title={`${label(id)} model`} sub={defaults?.defaultModels?.[id] || 'The agent’s own default'} onClick={() => setPickModel(id)} />
        ))}

        <div className="section">This device</div>
        <Opt title="Clear the saved copy" sub="Chats and drafts kept on the phone for offline. The Mac keeps everything." onClick={() => {
          for (const k of Object.keys(localStorage)) if (k.startsWith('terminus.thread.')) localStorage.removeItem(k);
          toast('Cleared. Chats reload from the Mac.');
        }} />
        <Opt title="Sign out" danger onClick={() => void signOut()} />
        <p className="hint" style={{ marginTop: 24 }}>Terminus · build {BUILD} · {location.host}</p>
      </div>

      <Sheet open={pickBackend} onClose={() => setPickBackend(false)}>
        <h2>New chats use</h2>
        {SUPPORTED_BACKENDS.map((id) => {
          const b = backends.find((x) => x.id === id);
          const ready = !b || (b.available && b.authenticated);
          return (
            <Opt key={id} title={label(id)} sub={!ready ? 'Not ready on the Mac' : id === def ? 'Current' : undefined} disabled={!ready} onClick={() => {
              setPickBackend(false);
              void saveDefaults({ defaultBackend: id });
            }} />
          );
        })}
      </Sheet>

      <Sheet open={!!pickModel} onClose={() => setPickModel(null)}>
        <h2>{pickModel ? `${label(pickModel)} model` : ''}</h2>
        {pickModel && models.length === 0 ? <p className="hint">Loading…</p> : null}
        <Opt title="The agent’s own default" onClick={() => {
          const id = pickModel!;
          setPickModel(null);
          void saveDefaults({ defaultModels: { ...(defaults?.defaultModels ?? {}), [id]: '' } });
        }} />
        {models.map((m) => (
          <Opt key={m.id} title={m.label || m.id} sub={m.label && m.label !== m.id ? m.id : undefined} onClick={() => {
            const id = pickModel!;
            setPickModel(null);
            void saveDefaults({ defaultModels: { ...(defaults?.defaultModels ?? {}), [id]: m.id } });
          }} />
        ))}
      </Sheet>
    </Screen>
  );
}
